import asyncio
import json
from unittest.mock import AsyncMock, patch

import networkx as nx
import pytest

from app.chat.intent import classify_chat_intent
from app.config import settings
from app.core.cache import generate_cache_key
from app.services.chat import chat_explain_stream_generator


def classification(intent="learning_path", target="NULL", query="Lộ trình học NULL", confidence=.95):
    return dict(intent=intent, target=target, query=query, confidence=confidence)


async def frames(question, **kwargs):
    return [frame async for frame in chat_explain_stream_generator("7", question, authorized_course_ids=["7"], **kwargs)]


def answer(events):
    return next(json.loads(frame.splitlines()[1][6:])["content"] for frame in events if frame.startswith("event: answer"))


@pytest.mark.asyncio
async def test_classifier_uses_bounded_history_and_explicit_reply_without_graph_authority():
    reply = [{"role": "assistant", "content": "Older answer about NULL [9]"}]
    history = [{"role": "assistant", "content": "Recent topic SQL [2]"}]
    with patch("app.chat.intent.llm_client.generate", AsyncMock(return_value=classification("prerequisites"))) as model:
        result = await classify_chat_intent("Trước đó cần biết gì?", history, reply)
    assert result.target == "NULL"
    data = json.loads(model.call_args.kwargs["input_data"])
    assert data["reply_context"][-1]["content"] == "Older answer about NULL "
    assert data["history"][-1]["content"] == "Recent topic SQL "
    assert set(data) == {"question", "history", "reply_context"}
    assert model.call_args.kwargs["json_mode"] is True


@pytest.mark.asyncio
@pytest.mark.parametrize("context_field", ["history", "reply_context"])
async def test_classifier_cache_key_preserves_conversation_order(context_field):
    messages = [
        {"role": "assistant", "content": "Topic NULL"},
        {"role": "assistant", "content": "Topic SQL"},
    ]
    with patch("app.chat.intent.llm_client.generate", AsyncMock(return_value=classification())) as model:
        await classify_chat_intent("What does it mean?", **{context_field: messages})
        await classify_chat_intent("What does it mean?", **{context_field: list(reversed(messages))})
        await classify_chat_intent("What does it mean?", **{context_field: messages})

    keys = []
    for call in model.call_args_list:
        payload = call.kwargs["input_data"]
        # Cache serialization must preserve the conversation actually sent to the model.
        assert json.loads(call.args[0].split("CONVERSATION DATA:\n", 1)[1]) == json.loads(payload)
        keys.append(generate_cache_key(settings.MODEL_MAIN, call.kwargs["prompt_version"], payload))
    assert keys[0] != keys[1]
    assert keys[0] == keys[2]


@pytest.mark.asyncio
@pytest.mark.parametrize("invalid", [
    {"intent": "run_query", "query": "NULL", "confidence": .99},
    {**classification(), "course_id": "private-course"},
    {**classification(), "confidence": 5},
    {**classification(), "target": 123},
    {**classification(), "query": " "},
])
async def test_classifier_rejects_invalid_or_authority_bearing_output(invalid):
    with patch("app.chat.intent.llm_client.generate", AsyncMock(return_value=invalid)):
        with pytest.raises(ValueError):
            await classify_chat_intent("Tui muốn hiểu NULL, bắt đầu từ đâu?")


@pytest.mark.asyncio
@pytest.mark.parametrize("question,route,history", [
    ("Tui muốn hiểu NULL, bắt đầu từ đâu?", "learning_path", []),
    ("Trước đó cần biết gì?", "prerequisites", [{"role": "assistant", "content": "NULL là con trỏ rỗng."}]),
    ("NULL", "learning_path", [{"role": "user", "content": "Mình muốn lộ trình học."},
                                {"role": "assistant", "content": "Bạn muốn lộ trình cho khái niệm nào?"}]),
])
async def test_natural_requests_and_followups_use_actual_scoped_topology(question, route, history):
    graph = nx.DiGraph()
    graph.add_node("pointer", name="Con trỏ", order=0)
    graph.add_node("null", name="NULL", order=1)
    graph.add_edge("pointer", "null", status="CONFIRMED")
    concepts = [{"id": "null", "name": "NULL", "aliases": [], "course_id": "7"}]
    with patch("app.chat.intent.llm_client.generate", AsyncMock(return_value=classification(route))) as classify, \
         patch("app.services.chat_paths.get_course_concepts_for_linking", AsyncMock(return_value=concepts)), \
         patch("app.services.chat_paths.get_course_graph", AsyncMock(return_value=(graph, 1, {}, []))) as load, \
         patch("app.services.chat.retrieve_chunks", AsyncMock()) as retrieve, \
         patch("app.services.chat.llm_client.generate_stream") as stream:
        result = await frames(question, history=history)
    assert "**Con trỏ**" in answer(result) and "**NULL**" in answer(result)
    assert "### Giai đoạn" in answer(result)
    load.assert_awaited_once_with("7")
    classify.assert_awaited_once()
    retrieve.assert_not_awaited()
    stream.assert_not_called()


@pytest.mark.asyncio
@pytest.mark.parametrize("question", ["Nó có nghĩa gì?", "Đừng tạo lộ trình, chỉ giải thích NULL cho tôi."])
async def test_followup_definition_uses_rag_with_resolved_subject_not_path_engine(question):
    async def tokens(*args, **kwargs):
        yield "NULL là con trỏ rỗng [1]."
    with patch("app.chat.intent.llm_client.generate", AsyncMock(return_value=classification("explain", query="NULL có nghĩa gì?"))), \
         patch("app.services.chat.build_chat_path", AsyncMock()) as path, \
         patch("app.services.chat.link_concepts", AsyncMock(return_value={"concept_ids": ["null"]})), \
         patch("app.services.chat.retrieve_chunks", AsyncMock(return_value=[{"id": "1:0", "doc_id": "1", "text": "NULL"}])) as retrieve, \
         patch("app.services.chat.llm_client.generate_stream", tokens):
        result = await frames(question, history=[{"role": "assistant", "content": "Học NULL sau Con trỏ."}])
    assert "con trỏ rỗng" in answer(result)
    assert retrieve.call_args.kwargs["query"] == "NULL có nghĩa gì?"
    assert retrieve.call_args.kwargs["authorized_course_ids"] == ["7"]
    path.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize("output", [classification(target=None), classification(confidence=.4),
                                        classification("clarify", target=None)])
async def test_missing_or_uncertain_subject_asks_before_computing_any_path(output):
    with patch("app.chat.intent.llm_client.generate", AsyncMock(return_value=output)), \
         patch("app.services.chat.build_chat_path", AsyncMock()) as path, \
         patch("app.services.chat.retrieve_chunks", AsyncMock()) as retrieve:
        result = await frames("Mình nên bắt đầu từ đâu?")
    assert "khái niệm cụ thể" in answer(result)
    path.assert_not_awaited()
    retrieve.assert_not_awaited()


@pytest.mark.asyncio
async def test_classified_target_cannot_escape_authorized_sources():
    with patch("app.chat.intent.llm_client.generate", AsyncMock(return_value=classification())), \
         patch("app.services.chat_paths.get_course_concepts_for_linking", AsyncMock(return_value=[
             {"id": "secret", "name": "NULL", "aliases": [], "course_id": "private"}])), \
         patch("app.services.chat_paths.get_course_graph", AsyncMock()) as load:
        result = await frames("Tui muốn hiểu NULL, bắt đầu từ đâu?")
    assert "Chưa tìm thấy concept" in answer(result)
    load.assert_not_awaited()


@pytest.mark.asyncio
async def test_classifier_timeout_cancels_call_and_falls_back_to_regular_chat(monkeypatch):
    cancelled = asyncio.Event()
    async def slow(*args):
        try:
            await asyncio.sleep(60)
        finally:
            cancelled.set()
    monkeypatch.setattr(settings, "CHAT_INTENT_TIMEOUT_SECONDS", .01)
    with patch("app.services.chat.classify_chat_intent", slow), \
         patch("app.services.chat.link_concepts", AsyncMock(return_value={"concept_ids": []})), \
         patch("app.services.chat.retrieve_chunks", AsyncMock(return_value=[])):
        result = await frames("Một câu hỏi tự nhiên")
    assert cancelled.is_set()
    assert result[-1].startswith("event: done")
    assert not any(frame.startswith("event: error") for frame in result)


@pytest.mark.asyncio
async def test_clear_path_request_skips_semantic_model():
    with patch("app.services.chat.classify_chat_intent", AsyncMock()) as classify, \
         patch("app.services.chat.build_chat_path", AsyncMock(return_value={"answer": "Path", "chunks": []})):
        await frames("Cho tôi lộ trình học NULL")
    classify.assert_not_awaited()


@pytest.mark.asyncio
async def test_no_authorized_scope_does_not_send_question_to_classifier():
    with patch("app.services.chat.classify_chat_intent", AsyncMock()) as classify:
        result = [f async for f in chat_explain_stream_generator("7", "Tui muốn hiểu NULL, bắt đầu từ đâu?",
                                                               authorized_course_ids=[])]
    classify.assert_not_awaited()
    assert result[-1].startswith("event: done")


@pytest.mark.asyncio
async def test_clarification_discards_model_suggested_subject():
    with patch("app.chat.intent.llm_client.generate", AsyncMock(return_value=classification("clarify"))):
        result = await classify_chat_intent("Mình nên bắt đầu từ đâu?")
    assert result.target is None


@pytest.fixture(autouse=True)
def isolate_prerequisite_database_in_transport_tests(monkeypatch):
    # Traversal has its own database/authorization tests. Keep routing and SSE unit tests offline.
    monkeypatch.setattr("app.services.chat.retrieve_prerequisite_context", AsyncMock(return_value=([], [], [])))
