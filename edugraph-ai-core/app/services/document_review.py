"""Extract privately, review explicitly, publish one atomic graph transaction."""
import asyncio
import uuid
import networkx as nx
from fastapi import HTTPException
from app.core.document_tasks import document_tasks, gather_owned
from app.db import draft_store, graph_repository
from app.db.queries import publication as queries
from app.retrieval.graph_cache import clear_course_graph_cache


def owned(doc_id, course_id, user_id):
    draft = draft_store.load(doc_id)
    if not draft or draft["course_id"] != course_id or draft["user_id"] != user_id:
        raise HTTPException(404, "Document draft not found")
    return draft


async def course_snapshot(course_id):
    async with graph_repository.session() as session:
        return await (await graph_repository.run(session, queries.SNAPSHOT, course_id=course_id)).single()


async def resolve_receipt(draft):
    if draft.get("publish_attempted") and draft["status"] == "AWAITING_REVIEW":
        async with graph_repository.session() as session:
            receipt = await (await graph_repository.run(session, queries.RECEIPT,
                       doc_id=draft["document_id"], course_id=draft["course_id"])).single()
        if receipt and receipt["review_id"] == draft["review_id"]:
            clear_course_graph_cache(draft["course_id"])
            draft.update(status="DONE", payload=None, current_step=None, progress=None)
            draft_store.save(draft)
    return draft


async def start(req):
    async with document_tasks.lock(req.document_id):
        old = draft_store.load(req.document_id)
        if old:
            owned(req.document_id, req.course_id, req.user_id)
            await resolve_receipt(old)
            if old["status"] == "CANCELLED":
                raise HTTPException(409, "Document has been cancelled")
            if document_tasks.active(req.document_id):
                return {"status": "processing"}
            if old["status"] in {"AWAITING_REVIEW", "DONE"} and not req.restart:
                return {"status": old["status"]}
            if old["status"] == "DONE":
                raise HTTPException(409, "Published documents cannot be reprocessed")
            if old["status"] == "PAUSED" and (not req.restart or old.get("run_id") == req.run_id):
                raise HTTPException(409, "Document is paused; explicit retry required")
        snapshot = await course_snapshot(req.course_id)
        if snapshot["owner_id"] not in (None, req.user_id):
            raise HTTPException(403, "Permission denied: user is not course owner")
        draft = dict(document_id=req.document_id, course_id=req.course_id, user_id=req.user_id,
                     filename=req.filename, run_id=req.run_id, review_id=str(uuid.uuid4()),
                     status="PROCESSING", error=None, progress=None, current_step=None,
                     version=snapshot["version"])
        draft_store.save(draft)
        document_tasks.start(req.document_id, lambda: extract(draft))
    return {"status": "processing"}


async def extract(draft):
    from app.concept_extraction.pipeline import process_document_pipeline, draft_heartbeat
    def heartbeat(**updates):
        if updates.get("current_step"):
            draft["progress"] = None
        draft.update({k: v for k, v in updates.items() if v is not None})
        draft_store.save(draft)
    token = draft_heartbeat.set(heartbeat)
    try:
        payload = await process_document_pipeline(draft["document_id"], draft["course_id"],
                                                  draft["filename"], draft=True)
        heartbeat(current_step="INFERRING_PREREQUISITES")
        payload["edges"] = await infer_edges(draft["course_id"], payload)
        draft.update(payload=payload, status="AWAITING_REVIEW", current_step=None, progress=None)
        draft_store.save(draft)
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        draft.update(status="FAILED", error=str(exc)[:1000])
        draft_store.save(draft)
    finally:
        draft_heartbeat.reset(token)


async def infer_edges(course_id, payload):
    from app.prerequisite.candidate_pairs import candidate_pairs_from_data, get_existing_directed_edges
    from app.prerequisite.pipeline import get_concept_evidence
    from app.prerequisite.llm_judge import judge_prerequisites
    from app.prerequisite.rule_signals import evaluate_rule_signals
    from app.prerequisite.fusion import fuse_scores
    concepts = {c["id"]: c for c in payload["all_concepts"] if c.get("status", "ACTIVE") == "ACTIVE"}
    by_key = {c["key"]: c for c in concepts.values()}
    chunks = {c["id"]: c for c in payload["chunks"]}
    evidence = await get_concept_evidence(course_id)
    cooccurrences = {}
    touched = set()
    for chunk in chunks.values():
        ids = {by_key[m["concept_key"]]["id"] for m in payload["mentions"]
               if m["chunk_id"] == chunk["id"] and m["concept_key"] in by_key}
        touched.update(ids)
        for cid in ids:
            cooccurrences.setdefault(cid, set()).update(ids - {cid})
            evidence.setdefault(cid, []).append(chunk["text"])
    existing = await get_existing_directed_edges(course_id)
    candidates = candidate_pairs_from_data(list(concepts.values()), cooccurrences, existing)
    async def judge(tid, cands):
        if not cands:
            return []
        target = concepts[tid]
        texts = evidence.get(tid, [])
        result = await judge_prerequisites(target, cands, texts)
        rules = {c["id"]: evaluate_rule_signals(target["name"], c["name"], texts) for c in cands}
        return fuse_scores(tid, result, rules)
    results = await gather_owned(*(judge(tid, cands) for tid, cands in candidates.items()
                                   if tid in touched or any(c["id"] in touched for c in cands)))
    graph = nx.DiGraph()
    for target, sources in existing.items():
        graph.add_edges_from((source, target) for source, status in sources.items() if status != "REJECTED")
    edges = []
    for edge in sorted((e for batch in results for e in batch), key=lambda e: -e["score"]):
        source, target = edge["source_id"], edge["target_id"]
        if source == target or (target in graph and source in graph and nx.has_path(graph, target, source)):
            continue
        graph.add_edge(source, target)
        edge["id"] = f"{source}:{target}"
        edges.append(edge)
    return edges


async def preview(doc_id, course_id, user_id):
    draft = owned(doc_id, course_id, user_id)
    if draft["status"] != "AWAITING_REVIEW":
        raise HTTPException(409, "Document is not awaiting review")
    payload = draft["payload"]
    keys = {m["concept_key"] for m in payload["mentions"]}
    endpoints = {e[k] for e in payload["edges"] for k in ("source_id", "target_id")}
    concepts = [{k: c.get(k) for k in ("id", "key", "name", "aliases", "language")}
                for c in payload["all_concepts"] if c["key"] in keys or c["id"] in endpoints]
    chunks = {c["id"]: c for c in payload["chunks"]}
    for concept in concepts:
        concept["sources"] = [{"page": chunks[m["chunk_id"]].get("page"),
                               "snippet": chunks[m["chunk_id"]]["text"][:500]}
                              for m in payload["mentions"] if m["concept_key"] == concept["key"]][:5]
    return {"review_id": draft["review_id"], "filename": draft["filename"],
            "concepts": concepts, "edges": payload["edges"]}


async def publish(doc_id, course_id, user_id, request):
    from app.db.repo import save_ingestion_transaction
    async with document_tasks.lock(doc_id):
        draft = owned(doc_id, course_id, user_id)
        if draft["review_id"] != request.review_id:
            raise HTTPException(409, "Draft changed; reload the review")
        if draft["status"] == "DONE":
            return {"status": "DONE"}
        if draft["status"] != "AWAITING_REVIEW":
            raise HTTPException(409, "Document is not awaiting review")
        # Recover an ACK lost after Neo4j committed, before the private receipt was saved.
        async with graph_repository.session() as session:
            receipt = await (await graph_repository.run(session, queries.RECEIPT,
                       doc_id=doc_id, course_id=course_id)).single()
        if not receipt or receipt["review_id"] != draft["review_id"]:
            payload = draft["payload"]
            excluded = set(request.excluded_concepts)
            known = {c["key"] for c in payload["all_concepts"]}
            edge_ids = {e["id"] for e in payload["edges"]}
            if not excluded <= known or not set(request.excluded_edges) <= edge_ids:
                raise HTTPException(422, "Unknown concept or relationship in review")
            removed_ids = {c["id"] for c in payload["all_concepts"] if c["key"] in excluded}
            mentions = [m for m in payload["mentions"] if m["concept_key"] not in excluded]
            concepts = [c for c in payload["concepts"] if c["key"] not in excluded]
            edges = [e for e in payload["edges"] if e["id"] not in request.excluded_edges
                     and not {e["source_id"], e["target_id"]} & removed_ids]
            draft["publish_attempted"] = True
            draft_store.save(draft)
            await save_ingestion_transaction(doc_id, course_id, payload["chunks"], concepts, mentions,
                       publication={**{k: draft[k] for k in ("review_id", "version", "filename", "user_id")},
                                    "edges": edges})
        clear_course_graph_cache(course_id)
        draft.update(status="DONE", payload=None, current_step=None, progress=None)
        draft_store.save(draft)
        return {"status": "DONE"}
