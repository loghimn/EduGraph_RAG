import asyncio
from unittest.mock import AsyncMock, patch

import pytest

from app.chat.streaming import with_sse_heartbeats


@pytest.mark.asyncio
async def test_heartbeats_do_not_cancel_or_restart_pending_work():
    release = asyncio.Event()
    closed = asyncio.Event()
    calls = 0

    async def events():
        nonlocal calls
        calls += 1
        try:
            await release.wait()
            yield "event: token\ndata: {}\n\n"
            yield "event: done\ndata: {}\n\n"
        finally:
            closed.set()

    stream = with_sse_heartbeats(events(), 0.01)
    try:
        for _ in range(3):
            assert await asyncio.wait_for(anext(stream), 5) == ": heartbeat\n\n"
        assert calls == 1
        assert not closed.is_set()
        release.set()
        frames = [frame async for frame in stream]
        assert frames == ["event: token\ndata: {}\n\n", "event: done\ndata: {}\n\n"]
        assert closed.is_set()
    finally:
        await stream.aclose()


@pytest.mark.asyncio
async def test_closing_after_heartbeat_cancels_and_closes_pending_generator():
    closed = asyncio.Event()

    async def events():
        try:
            await asyncio.Event().wait()
            yield "never"
        finally:
            closed.set()

    stream = with_sse_heartbeats(events(), 0.01)
    assert await asyncio.wait_for(anext(stream), 5) == ": heartbeat\n\n"
    await stream.aclose()
    assert closed.is_set()


@pytest.mark.asyncio
async def test_cancellation_during_wait_is_propagated_and_cleans_up():
    started = asyncio.Event()
    closed = asyncio.Event()

    async def events():
        try:
            started.set()
            await asyncio.Event().wait()
            yield "never"
        finally:
            closed.set()

    stream = with_sse_heartbeats(events(), 10)
    pending = asyncio.create_task(anext(stream))
    try:
        await asyncio.wait_for(started.wait(), 5)
        pending.cancel()
        with pytest.raises(asyncio.CancelledError):
            await pending
        assert closed.is_set()
    finally:
        pending.cancel()
        await stream.aclose()


@pytest.mark.asyncio
async def test_upstream_exception_is_not_hidden_by_heartbeat_wrapper():
    async def events():
        raise ValueError("upstream error")
        yield "never"

    stream = with_sse_heartbeats(events(), 10)
    with pytest.raises(ValueError, match="upstream error"):
        await anext(stream)


@pytest.mark.asyncio
@pytest.mark.parametrize("stage", ["context", "linking", "retrieval", "first_token"])
async def test_chat_emits_heartbeats_during_each_pre_token_wait(stage, monkeypatch):
    from app.api.chat import chat_explain_stream_generator
    from app.config import settings

    monkeypatch.setattr(settings, "CHAT_HEARTBEAT_INTERVAL_SECONDS", 0.01)
    release = asyncio.Event()
    started = asyncio.Event()

    async def wait_at(expected):
        if stage == expected:
            started.set()
            await release.wait()

    async def resolve(*args):
        await wait_at("context")
        return "Java Servlet?"

    async def link(*args):
        await wait_at("linking")
        return {"concept_ids": ["servlet"], "clarify": None}

    async def retrieve(**kwargs):
        await wait_at("retrieval")
        return [{"id": "1:0", "doc_id": "1", "text": "Java Servlet"}]

    async def generate(*args, **kwargs):
        await wait_at("first_token")
        yield "Answer [1]"

    with patch("app.api.chat.resolve_question", new=AsyncMock(side_effect=resolve)), \
         patch("app.api.chat.link_concepts", new=AsyncMock(side_effect=link)), \
         patch("app.api.chat.retrieve_chunks", new=AsyncMock(side_effect=retrieve)), \
         patch("app.api.chat.llm_client") as llm:
        llm.generate_stream = generate
        history = [{"role": "assistant", "content": "Java Servlet"}] if stage == "context" else []
        stream = chat_explain_stream_generator("c1", "Java Servlet?", history=history)
        try:
            frames = []
            while True:
                frame = await asyncio.wait_for(anext(stream), 5)
                frames.append(frame)
                if frame == ": heartbeat\n\n":
                    break
            assert started.is_set()
            release.set()
            frames += [frame async for frame in stream]
            assert '"token": "Answer [1]"' in "".join(frames)
            assert frames[-1] == "event: done\ndata: {}\n\n"
        finally:
            release.set()
            await stream.aclose()
