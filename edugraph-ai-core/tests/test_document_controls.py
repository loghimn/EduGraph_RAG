import asyncio
import threading
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi import BackgroundTasks, HTTPException

from app.api import ingest
from app.core.document_tasks import DocumentTasks, gather_owned, to_thread_owned


@pytest.fixture
def controls(monkeypatch):
    manager = DocumentTasks()
    monkeypatch.setattr(ingest, "document_tasks", manager)
    session = AsyncMock()
    client = MagicMock()
    client.driver.session.return_value.__aenter__.return_value = session
    monkeypatch.setattr(ingest, "neo4j_client", client)
    owner, document = AsyncMock(), AsyncMock()
    owner.single.return_value = {"owner_id": "u1"}
    document.single.return_value = {"status": "PROCESSING"}
    session.run.side_effect = [owner, document, AsyncMock()]
    return manager, session, document


@pytest.mark.asyncio
async def test_pause_drains_pipeline_children_before_setting_paused(controls):
    manager, session, _ = controls
    started, stopped = asyncio.Event(), asyncio.Event()

    async def child():
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            await asyncio.sleep(0)
            stopped.set()

    manager.start("100", lambda: gather_owned(child()))
    await started.wait()
    result = await ingest.pause_document_endpoint("100", "c1", "u1", "test-internal-api-key")
    assert result == {"status": "paused"}
    assert stopped.is_set()
    assert not manager.active("100")
    assert "d.status = 'PAUSED'" in session.run.call_args.args[0]


@pytest.mark.asyncio
async def test_delete_stops_active_task_before_graph_cleanup(controls, monkeypatch):
    manager, session, _ = controls
    started, stopped = asyncio.Event(), asyncio.Event()

    async def pipeline():
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            stopped.set()

    async def delete(course, doc):
        assert stopped.is_set()
        assert not manager.active(doc)

    cleanup = AsyncMock(side_effect=delete)
    monkeypatch.setattr(ingest, "delete_document", cleanup)
    manager.start("100", pipeline)
    await started.wait()
    assert await ingest.delete_document_endpoint("100", "c1", "u1", "test-internal-api-key") == {"status": "deleted"}
    cleanup.assert_awaited_once_with("c1", "100")
    assert "CANCELLED" in session.run.call_args.args[0]


@pytest.mark.asyncio
async def test_duplicate_ingest_keeps_one_task(controls, monkeypatch):
    manager, session, _ = controls
    started = asyncio.Event()

    async def pipeline(*args):
        started.set()
        await asyncio.Event().wait()

    work = AsyncMock(side_effect=pipeline)
    monkeypatch.setattr(ingest, "_background_ingest", work)
    request = ingest.IngestRequest(document_id="100", course_id="c1", user_id="u1")
    try:
        await ingest.ingest_document(request, BackgroundTasks(), "test-internal-api-key")
        await started.wait()
        # Duplicate request only checks the course owner before acknowledging.
        owner = AsyncMock()
        owner.single.return_value = {"owner_id": "u1"}
        session.run.side_effect = None
        session.run.return_value = owner
        await ingest.ingest_document(request, BackgroundTasks(), "test-internal-api-key")
        work.assert_awaited_once()
    finally:
        await manager.shutdown()


@pytest.mark.asyncio
@pytest.mark.parametrize("status,restart,allowed", [
    ("PAUSED", False, False), ("PAUSED", True, True),
    ("CANCELLED", False, False), ("CANCELLED", True, False),
])
async def test_retry_is_explicit_and_cannot_revive_cancelled(controls, monkeypatch, status, restart, allowed):
    manager, _, document = controls
    document.single.return_value = {"status": status}
    work = AsyncMock()
    monkeypatch.setattr(ingest, "_background_ingest", work)
    request = ingest.IngestRequest(document_id="100", course_id="c1", user_id="u1", restart=restart)
    try:
        if allowed:
            await ingest.ingest_document(request, BackgroundTasks(), "test-internal-api-key")
            await asyncio.sleep(0)
            work.assert_awaited_once()
        else:
            with pytest.raises(HTTPException) as error:
                await ingest.ingest_document(request, BackgroundTasks(), "test-internal-api-key")
            assert error.value.status_code == 409
            work.assert_not_awaited()
    finally:
        await manager.shutdown()


@pytest.mark.asyncio
async def test_control_wrong_owner_does_not_stop_task(controls):
    manager, session, _ = controls
    owner = AsyncMock()
    owner.single.return_value = {"owner_id": "someone-else"}
    session.run.side_effect = [owner]
    manager.stop = AsyncMock()
    with pytest.raises(HTTPException) as error:
        await ingest.pause_document_endpoint("100", "c1", "u1", "test-internal-api-key")
    assert error.value.status_code == 403
    manager.stop.assert_not_awaited()


@pytest.mark.asyncio
async def test_delayed_request_for_paused_run_cannot_restart_it(controls, monkeypatch):
    _, _, document = controls
    document.single.return_value = {"status": "PAUSED", "run_id": "job-1"}
    work = AsyncMock()
    monkeypatch.setattr(ingest, "_background_ingest", work)
    request = ingest.IngestRequest(document_id="100", course_id="c1", user_id="u1",
                                   restart=True, run_id="job-1")
    with pytest.raises(HTTPException) as error:
        await ingest.ingest_document(request, BackgroundTasks(), "test-internal-api-key")
    assert error.value.status_code == 409
    work.assert_not_awaited()


@pytest.mark.asyncio
async def test_failed_child_does_not_leave_sibling_running():
    started, stopped = asyncio.Event(), asyncio.Event()

    async def fail():
        await started.wait()
        raise RuntimeError("failed")

    async def sibling():
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            stopped.set()

    with pytest.raises(RuntimeError, match="failed"):
        await gather_owned(fail(), sibling())
    assert stopped.is_set()


@pytest.mark.asyncio
async def test_cpu_work_is_drained_before_stop_completes():
    started, release, finished = threading.Event(), threading.Event(), threading.Event()

    def work():
        started.set()
        release.wait(timeout=5)
        finished.set()

    task = asyncio.create_task(to_thread_owned(work))
    try:
        while not started.is_set():
            await asyncio.sleep(0.001)
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert finished.is_set()
    finally:
        release.set()
