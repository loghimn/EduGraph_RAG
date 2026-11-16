import asyncio
from pathlib import Path
from copy import deepcopy
from unittest.mock import AsyncMock, MagicMock
import pytest
from fastapi import HTTPException
from app.config import settings
from app.core.document_tasks import DocumentTasks
from app.db import draft_store
from app.schemas.ingest import IngestRequest, PublishRequest, UpdateDraftRequest
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
async def test_missing_payload_has_recovery_error_and_syncs_as_failed(workspace):
    ready()
    (Path(settings.RAW_DATA_DIR) / ".graph-drafts" / "101.payload").unlink()
    with pytest.raises(HTTPException) as failure:
        await review.preview("101", "26", "4")
    assert failure.value.status_code == 409
    assert "Regenerate" in failure.value.detail
    status = await ingest.get_document_status("101", "26")
    assert status["status"] == "FAILED"
    assert "retry extraction" in status["error"]


def test_relative_storage_directory_is_independent_of_process_cwd(tmp_path, monkeypatch):
    from app.config import Settings
    monkeypatch.chdir(tmp_path)
    configured = Settings(_env_file=None, INTERNAL_API_KEY="test", RAW_DATA_DIR="data/raw")
    project = Path(__file__).resolve().parent.parent
    assert Path(configured.RAW_DATA_DIR) == (project / "data/raw").resolve()


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
@pytest.mark.parametrize("current_version", [7, 8])
async def test_preview_reports_draft_and_current_graph_versions(workspace, monkeypatch, current_version):
    ready()
    monkeypatch.setattr(review, "course_snapshot", AsyncMock(return_value={"version": current_version, "owner_id": "4"}))
    preview = await review.preview("101", "26", "4")
    assert preview["draft_version"] == 7
    assert preview["current_graph_version"] == current_version
    assert draft_store.load("101")["version"] == 7
    assert draft_store.load("101")["status"] == "AWAITING_REVIEW"


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


@pytest.mark.asyncio
@pytest.mark.parametrize("pairs,cyclic", [
    ([("a", "b"), ("b", "c")], False),
    ([("a", "b"), ("a", "c"), ("b", "d"), ("c", "d")], False),
    ([("a", "b"), ("c", "d")], False),
    ([("a", "b"), ("b", "a")], True),
    ([("a", "b"), ("b", "c"), ("c", "a")], True),
    ([("a", "a")], True),
    # A pre-existing cycle outside this document also blocks publication.
    ([("a", "b"), ("x", "y"), ("y", "x")], True),
])
async def test_publication_validates_actual_course_graph_before_commit(monkeypatch, pairs, cyclic):
    from app.db import repo
    from app.db.queries import publication as pq
    tx = AsyncMock()
    result = AsyncMock()
    result.single.return_value = {"owner_id": "4", "version": 7}
    result.data.return_value = [{"source": s, "target": t} for s, t in pairs]
    result.consume.return_value = MagicMock(counters=MagicMock(nodes_deleted=0))
    tx.run.return_value = result
    session = AsyncMock()
    async def execute(write):
        return await write(tx)
    session.execute_write.side_effect = execute
    manager = MagicMock()
    manager.__aenter__ = AsyncMock(return_value=session)
    manager.__aexit__ = AsyncMock(return_value=None)
    monkeypatch.setattr(repo.neo4j_client, "driver", MagicMock(session=lambda: manager))
    operation = repo.save_ingestion_transaction("101", "26", [], [], [], publication={
        "user_id": "4", "version": 7, "review_id": "r", "filename": "test", "edges": []})
    if cyclic:
        with pytest.raises(HTTPException) as error:
            await operation
        assert error.value.status_code == 409
        assert error.value.detail["code"] == "PREREQUISITE_CYCLE"
        witness = error.value.detail["cycle"]
        assert witness and all(tuple(edge) in pairs for edge in witness)
    else:
        await operation
    calls = [call.args[0] for call in tx.run.await_args_list]
    assert calls[0] == pq.LOCK_COURSE
    assert calls.index(pq.EDGES) < calls.index(pq.ACTIVE_EDGES)
    cleanup = next(query for query in calls if "DETACH DELETE orphan" in query)
    assert calls.index(cleanup) < calls.index(pq.ACTIVE_EDGES)
    assert (calls[-1] == pq.ACTIVE_EDGES) == cyclic


@pytest.mark.asyncio
async def test_replacement_cleans_orphaned_auto_cycle_before_validation(monkeypatch):
    from app.db import repo
    from app.db.queries import publication as pq
    pairs = [("x", "y"), ("y", "x")]
    tx = AsyncMock()
    result = AsyncMock()
    result.single.return_value = {"owner_id": "4", "version": 7}
    result.consume.return_value = MagicMock(counters=MagicMock(nodes_deleted=0))

    async def run(query, **params):
        if "DETACH DELETE orphan" in query:
            pairs.clear()
            cleaned = AsyncMock()
            cleaned.consume.return_value = MagicMock(counters=MagicMock(nodes_deleted=2))
            return cleaned
        if query == pq.ACTIVE_EDGES:
            result.data.return_value = [{"source": s, "target": t} for s, t in pairs]
        return result

    tx.run.side_effect = run
    session = AsyncMock()
    async def execute(write):
        return await write(tx)
    session.execute_write.side_effect = execute
    manager = MagicMock()
    manager.__aenter__ = AsyncMock(return_value=session)
    manager.__aexit__ = AsyncMock(return_value=None)
    monkeypatch.setattr(repo.neo4j_client, "driver", MagicMock(session=lambda: manager))
    saved = await repo.save_ingestion_transaction("101", "26", [], [], [], publication={
        "user_id": "4", "version": 7, "review_id": "r", "filename": "test", "edges": []})
    assert saved["deleted_orphans"] == 2
    assert not pairs


@pytest.mark.asyncio
async def test_cycle_failure_keeps_private_draft_available_for_corrected_selection(workspace, monkeypatch):
    ready()
    save = AsyncMock(side_effect=HTTPException(409, detail={"code": "PREREQUISITE_CYCLE"}))
    monkeypatch.setattr("app.db.repo.save_ingestion_transaction", save)
    with pytest.raises(HTTPException):
        await review.publish("101", "26", "4", PublishRequest(review_id="review-1"))
    draft = draft_store.load("101")
    assert draft["status"] == "AWAITING_REVIEW" and draft["payload"] == payload()
    save.side_effect = None
    await review.publish("101", "26", "4", PublishRequest(review_id="review-1", excluded_edges=["a:b"]))
    assert save.await_args.kwargs["publication"]["edges"] == []
    assert draft_store.load("101")["status"] == "DONE"


def updateable(doc_id="101"):
    draft = ready()
    draft["document_id"] = doc_id
    draft["payload"]["extracted_concepts"] = [dict(canonical="Class", embedding=[1.0],
        evidence_chunk_id="101:0", evidence_start=0, evidence_end=5, language="en", aliases=[])]
    draft_store.save(draft)
    return draft


@pytest.mark.asyncio
async def test_update_reuses_saved_extraction_changes_review_id_and_invalidates_old_approval(workspace, monkeypatch):
    registry, _, _ = workspace
    original = updateable()
    rebuilt = payload()
    rebuilt["extracted_concepts"] = original["payload"]["extracted_concepts"]
    rebuild = AsyncMock(return_value=rebuilt)
    monkeypatch.setattr("app.concept_extraction.pipeline.rebuild_draft_payload", rebuild)
    monkeypatch.setattr(review, "infer_edges", AsyncMock(return_value=rebuilt["edges"]))
    monkeypatch.setattr(review, "course_snapshot", AsyncMock(return_value={"version": 8, "owner_id": "4"}))
    write = AsyncMock()
    monkeypatch.setattr("app.db.repo.save_ingestion_transaction", write)
    assert await review.update_draft("101", "26", "4", UpdateDraftRequest(review_id="review-1")) == {"status": "processing"}
    assert draft_store.load("101")["update_status"] == "PROCESSING"
    await registry.tasks["101"]
    updated = draft_store.load("101")
    assert updated["version"] == 8 and updated["review_id"] != "review-1"
    assert updated["status"] == "AWAITING_REVIEW" and updated["update_status"] is None
    rebuild.assert_awaited_once_with("101", "26", original["payload"]["chunks"], original["payload"]["extracted_concepts"])
    write.assert_not_awaited()
    with pytest.raises(HTTPException) as error:
        await review.publish("101", "26", "4", PublishRequest(review_id="review-1"))
    assert error.value.status_code == 409
    await review.publish("101", "26", "4", PublishRequest(review_id=updated["review_id"]))
    assert write.await_args.kwargs["publication"]["version"] == 8


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["graph_changed", "inference_failed"])
async def test_update_failure_preserves_original_payload_and_version(workspace, monkeypatch, failure):
    registry, _, _ = workspace
    original = updateable()
    monkeypatch.setattr("app.concept_extraction.pipeline.rebuild_draft_payload", AsyncMock(return_value=payload()))
    infer = AsyncMock(return_value=[])
    if failure == "inference_failed":
        infer.side_effect = RuntimeError("upstream unavailable")
    monkeypatch.setattr(review, "infer_edges", infer)
    monkeypatch.setattr(review, "course_snapshot", AsyncMock(side_effect=[
        {"version": 8, "owner_id": "4"}, {"version": 9, "owner_id": "4"}]))
    await review.update_draft("101", "26", "4", UpdateDraftRequest(review_id="review-1"))
    await registry.tasks["101"]
    current = draft_store.load("101")
    assert current["payload"] == original["payload"]
    assert current["version"] == 7 and current["review_id"] == "review-1"
    assert current["update_status"] == "FAILED" and current["update_error"]
    assert current["status"] == "AWAITING_REVIEW"


@pytest.mark.asyncio
async def test_pending_update_blocks_publication_and_duplicate_update_is_idempotent(workspace, monkeypatch):
    registry, _, _ = workspace
    updateable()
    gate = asyncio.Event()
    async def rebuild(*args):
        await gate.wait()
        return payload()
    worker = AsyncMock(side_effect=rebuild)
    monkeypatch.setattr("app.concept_extraction.pipeline.rebuild_draft_payload", worker)
    monkeypatch.setattr(review, "infer_edges", AsyncMock(return_value=[]))
    monkeypatch.setattr(review, "course_snapshot", AsyncMock(return_value={"version": 8, "owner_id": "4"}))
    request = UpdateDraftRequest(review_id="review-1")
    await review.update_draft("101", "26", "4", request)
    task = registry.tasks["101"]
    try:
        await review.update_draft("101", "26", "4", request)
        assert registry.tasks["101"] is task
        with pytest.raises(HTTPException) as error:
            await review.publish("101", "26", "4", PublishRequest(review_id="review-1"))
        assert error.value.status_code == 409
    finally:
        gate.set()
        await task
    worker.assert_awaited_once()


@pytest.mark.asyncio
async def test_old_draft_requires_one_regeneration_and_update_checks_ownership(workspace):
    ready()
    with pytest.raises(HTTPException) as error:
        await review.update_draft("101", "26", "4", UpdateDraftRequest(review_id="review-1"))
    assert error.value.status_code == 409 and "older draft" in error.value.detail
    with pytest.raises(HTTPException) as error:
        await review.update_draft("101", "26", "9", UpdateDraftRequest(review_id="review-1"))
    assert error.value.status_code == 404


def test_interrupted_update_keeps_original_review_available(workspace):
    draft = updateable()
    draft["update_status"] = "PROCESSING"
    draft_store.save(draft)
    draft_store.recover()
    current = draft_store.load("101")
    assert current["status"] == "AWAITING_REVIEW"
    assert current["update_status"] == "FAILED" and current["update_error"]
    assert current["payload"] == draft["payload"]


@pytest.mark.asyncio
async def test_four_drafts_from_same_version_update_and_publish_without_duplicate_concepts(workspace, monkeypatch):
    from app.concept_extraction import pipeline
    registry, _, _ = workspace
    graph_concepts, graph_edges = {}, []
    version = 0
    async def snapshot(*args):
        return {"version": version, "owner_id": "4"}
    async def get_concepts(*args):
        return deepcopy(list(graph_concepts.values()))
    async def infer(course_id, payload):
        by_key = {c["key"]: c for c in payload["all_concepts"]}
        keys = {m["concept_key"] for m in payload["mentions"]} - {"class"}
        target = by_key[next(iter(keys))]["id"]
        source = by_key["class"]["id"]
        return [dict(id=f"{source}:{target}", source_id=source, target_id=target, score=.9)]
    async def save(doc_id, course_id, chunks, concepts, mentions, *, publication):
        nonlocal version
        if publication["version"] != version:
            raise HTTPException(409, "graph changed")
        for concept in concepts:
            assert concept["key"] not in graph_concepts
            graph_concepts[concept["key"]] = {**deepcopy(concept), "has_reviewed_edge": True}
        known_ids = {c["id"] for c in graph_concepts.values()}
        assert all(e["source_id"] in known_ids and e["target_id"] in known_ids for e in publication["edges"])
        graph_edges.extend(publication["edges"])
        version += 1
    monkeypatch.setattr(review, "course_snapshot", snapshot)
    monkeypatch.setattr(pipeline, "get_course_concepts", get_concepts)
    monkeypatch.setattr(review, "infer_edges", infer)
    monkeypatch.setattr("app.db.repo.save_ingestion_transaction", save)
    embed = AsyncMock(side_effect=AssertionError("An update must not embed again"))
    extract = AsyncMock(side_effect=AssertionError("An update must not extract again"))
    monkeypatch.setattr(pipeline.embed_model, "embed_batch", embed)
    monkeypatch.setattr(pipeline, "process_document_pipeline", extract)
    doc_ids = ["201", "202", "203", "204"]
    for doc_id, name in zip(doc_ids, ["Object", "Method", "Inheritance", "Polymorphism"]):
        chunks = [dict(id=f"{doc_id}:0", text=f"Class {name}", page=1, embedding=[1.0])]
        raw = [dict(canonical=word, aliases=[], language="en", embedding=[1.0],
                    evidence_chunk_id=chunks[0]["id"], evidence_start=0, evidence_end=5)
               for word in ["Class", name]]
        built = await pipeline.rebuild_draft_payload(doc_id, "26", chunks, raw)
        built["edges"] = await infer("26", built)
        draft_store.save(dict(document_id=doc_id, course_id="26", user_id="4", filename="test.pdf",
                              review_id=f"review-{doc_id}", version=0, status="AWAITING_REVIEW", payload=built))
    for index, doc_id in enumerate(doc_ids):
        draft = draft_store.load(doc_id)
        if index:
            with pytest.raises(HTTPException) as error:
                await review.publish(doc_id, "26", "4", PublishRequest(review_id=draft["review_id"]))
            assert error.value.status_code == 409
            await review.update_draft(doc_id, "26", "4", UpdateDraftRequest(review_id=draft["review_id"]))
            await registry.tasks[doc_id]
            updated = draft_store.load(doc_id)
            assert updated["version"] == index
            assert next(c["id"] for c in updated["payload"]["all_concepts"] if c["key"] == "class") == graph_concepts["class"]["id"]
            assert updated["payload"]["chunks"] == draft["payload"]["chunks"]
            draft = updated
        await review.publish(doc_id, "26", "4", PublishRequest(review_id=draft["review_id"]))
        assert draft_store.load(doc_id)["status"] == "DONE"
    assert version == 4 and len(graph_concepts) == 5 and len(graph_edges) == 4
    embed.assert_not_awaited()
    extract.assert_not_awaited()
