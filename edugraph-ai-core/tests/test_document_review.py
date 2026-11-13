import asyncio
from copy import deepcopy
from unittest.mock import AsyncMock, MagicMock
import pytest
from fastapi import HTTPException
from app.config import settings
from app.core.document_tasks import DocumentTasks
from app.db import draft_store
from app.schemas.ingest import IngestRequest, PublishRequest
from app.services import document_review as review, ingest


@pytest.fixture
def workspace(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "RAW_DATA_DIR", str(tmp_path))
    registry = DocumentTasks()
    monkeypatch.setattr(review, "document_tasks", registry)
    monkeypatch.setattr(ingest, "document_tasks", registry)
    session = AsyncMock()
    result = AsyncMock()
    result.single.return_value = None
    session.run.return_value = result
    manager = MagicMock()
    manager.__aenter__ = AsyncMock(return_value=session)
    manager.__aexit__ = AsyncMock(return_value=None)
    monkeypatch.setattr(review.graph_repository, "session", lambda: manager)
    return registry, session, result


def payload():
    concepts = [dict(id="a", key="class", name="Class", aliases=[], language="en"),
                dict(id="b", key="object", name="Object", aliases=[], language="en")]
    return dict(chunks=[dict(id="101:0", text="Class creates Object", page=1)],
                concepts=deepcopy(concepts), all_concepts=concepts,
                mentions=[dict(chunk_id="101:0", concept_key=c["key"]) for c in concepts],
                edges=[dict(id="a:b", source_id="a", target_id="b", evidence="Class creates Object", score=.9)])


def ready():
    draft = dict(document_id="101", course_id="26", user_id="4", filename="oop.txt",
                 review_id="review-1", status="AWAITING_REVIEW", version=7, payload=payload())
    draft_store.save(draft)
    return draft


@pytest.mark.asyncio
async def test_default_ingest_stages_without_writing_any_neo4j_nodes(workspace, monkeypatch):
    _, session, _ = workspace
    monkeypatch.setattr(review, "course_snapshot", AsyncMock(return_value={"version": 7, "owner_id": "4"}))
    extract = AsyncMock(return_value=payload())
    infer = AsyncMock(return_value=payload()["edges"])
    monkeypatch.setattr("app.concept_extraction.pipeline.process_document_pipeline", extract)
    monkeypatch.setattr(review, "infer_edges", infer)
    ack = await ingest.ingest_document(IngestRequest(document_id="101", course_id="26", user_id="4"))
    assert ack == {"status": "processing"}
    for _ in range(20):
        if draft_store.load("101")["status"] == "AWAITING_REVIEW":
            break
        await asyncio.sleep(.01)
    assert draft_store.load("101")["status"] == "AWAITING_REVIEW"
    extract.assert_awaited_once_with("101", "26", "unknown.pdf", draft=True)
    session.run.assert_not_awaited()
    preview = await review.preview("101", "26", "4")
    assert len(preview["concepts"]) == 2
    assert "chunks" not in preview
    assert "embedding" not in str(preview)


@pytest.mark.asyncio
async def test_approval_publishes_only_selected_nodes_edges_and_is_idempotent(workspace, monkeypatch):
    ready()
    save = AsyncMock()
    monkeypatch.setattr("app.db.repo.save_ingestion_transaction", save)
    request = PublishRequest(review_id="review-1", excluded_concepts=["object"])
    await review.publish("101", "26", "4", request)
    args = save.await_args
    assert [c["id"] for c in args.args[3]] == ["a"]
    assert [m["concept_key"] for m in args.args[4]] == ["class"]
    assert args.kwargs["publication"]["edges"] == []
    assert args.kwargs["publication"]["version"] == 7
    assert draft_store.load("101")["status"] == "DONE"
    assert draft_store.load("101")["payload"] is None
    await review.publish("101", "26", "4", request)
    save.assert_awaited_once()


@pytest.mark.asyncio
@pytest.mark.parametrize("course,user", [("27", "4"), ("26", "9")])
async def test_other_user_or_course_cannot_preview_or_publish(workspace, monkeypatch, course, user):
    ready()
    save = AsyncMock()
    monkeypatch.setattr("app.db.repo.save_ingestion_transaction", save)
    for operation in [review.preview("101", course, user), review.publish("101", course, user, PublishRequest(review_id="review-1"))]:
        with pytest.raises(HTTPException) as error:
            await operation
        assert error.value.status_code == 404
    save.assert_not_awaited()


@pytest.mark.asyncio
async def test_stale_or_invalid_selection_cannot_publish(workspace, monkeypatch):
    ready()
    save = AsyncMock()
    monkeypatch.setattr("app.db.repo.save_ingestion_transaction", save)
    for request, status in [(PublishRequest(review_id="old"), 409),
                            (PublishRequest(review_id="review-1", excluded_edges=["unknown"]), 422)]:
        with pytest.raises(HTTPException) as error:
            await review.publish("101", "26", "4", request)
        assert error.value.status_code == status
    save.assert_not_awaited()
    save.side_effect = HTTPException(409, "graph changed")
    with pytest.raises(HTTPException):
        await review.publish("101", "26", "4", PublishRequest(review_id="review-1"))
    assert draft_store.load("101")["status"] == "AWAITING_REVIEW"


@pytest.mark.asyncio
async def test_lost_publish_ack_recovers_using_transactional_receipt(workspace, monkeypatch):
    _, _, result = workspace
    ready()
    result.single.return_value = {"review_id": "review-1"}
    save = AsyncMock()
    monkeypatch.setattr("app.db.repo.save_ingestion_transaction", save)
    await review.publish("101", "26", "4", PublishRequest(review_id="review-1"))
    save.assert_not_awaited()
    assert draft_store.load("101")["status"] == "DONE"


@pytest.mark.asyncio
async def test_discard_and_restart_never_publish_and_cannot_revive_cancelled(workspace, monkeypatch):
    _, session, _ = workspace
    ready()
    await ingest.delete_document_endpoint("101", "26", "4")
    assert draft_store.load("101")["status"] == "CANCELLED"
    with pytest.raises(HTTPException) as error:
        await review.start(IngestRequest(document_id="101", course_id="26", user_id="4", restart=True))
    assert error.value.status_code == 409
    session.run.assert_not_awaited()


def test_private_draft_survives_restart_and_only_orphaned_processing_fails(workspace):
    draft = ready()
    draft_store.recover()
    assert draft_store.load("101")["status"] == "AWAITING_REVIEW"
    draft.update(status="PROCESSING")
    draft_store.save(draft)
    draft_store.recover()
    assert draft_store.load("101")["status"] == "FAILED"


@pytest.mark.asyncio
async def test_publish_checks_course_version_inside_write_transaction_before_any_document_write(workspace, monkeypatch):
    from app.db import repo
    tx = AsyncMock()
    row = AsyncMock()
    row.single.return_value = {"owner_id": "4", "version": 8}
    tx.run.return_value = row
    session = MagicMock()
    async def execute(write):
        return await write(tx)
    session.execute_write = execute
    manager = MagicMock()
    manager.__aenter__ = AsyncMock(return_value=session)
    manager.__aexit__ = AsyncMock(return_value=None)
    monkeypatch.setattr(repo.neo4j_client, "driver", MagicMock(session=lambda: manager))
    with pytest.raises(HTTPException) as error:
        await repo.save_ingestion_transaction("101", "26", [], [], [], publication={
            "user_id": "4", "version": 7, "review_id": "review-1", "filename": "oop.txt", "edges": []})
    assert error.value.status_code == 409
    assert tx.run.await_count == 1
