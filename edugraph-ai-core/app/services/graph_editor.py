"""Instructor graph curation. Public role/ownership checks belong to Java.

Every mutation serializes with ingestion on Course and checks its graph version.
Rejected edges remain as tombstones so ingestion cannot recreate them.
"""

from app.db.queries import graph_editor as queries
from app.schemas.graph_editor import EdgeRequest, ReviewRequest
import math

import networkx as nx
from fastapi import HTTPException

from app.schemas.concept_creation import DraftRequest
from app.config import settings
from app.core.embedding import embed_model
from app.core.text_utils import normalize_concept_key
from app.db import graph_repository
from app.retrieval.graph_cache import clear_course_graph_cache


async def lock_graph(tx, course_id, expected_version):
    result = await graph_repository.run(tx, queries.LOCK_GRAPH_QUERY_1, course_id=course_id)
    row = await result.single()
    if not row:
        raise HTTPException(404, "Course graph not found")
    if row["version"] != expected_version:
        raise HTTPException(409, detail={"message": "Graph changed; reload before saving", "graph_version": row["version"]})


async def finish(tx, course_id):
    row = await (await graph_repository.run(tx, queries.FINISH_QUERY_1, course_id=course_id)).single()
    return {"graph_version": row["graph_version"]}


async def write_graph(course_id, operation):
    async with graph_repository.session() as session:
        result = await session.execute_write(operation)
    clear_course_graph_cache(course_id)
    return result


async def review_graph(course_id: str):
    # Neo4j uses read-committed isolation: check the version again after reading.
    async def read(tx):
        row = await (await graph_repository.run(tx, queries.REVIEW_GRAPH_QUERY_1,
                                 course_id=course_id)).single()
        if not row:
            return {"graph_version": 0, "nodes": [], "edges": []}
        nodes = await (await graph_repository.run(tx, queries.REVIEW_GRAPH_QUERY_2, course_id=course_id)).data()
        edges = await (await graph_repository.run(tx, queries.REVIEW_GRAPH_QUERY_3, course_id=course_id)).data()
        after = await (await graph_repository.run(tx, queries.REVIEW_GRAPH_QUERY_4,
                                   course_id=course_id)).single()
        if not after or after["version"] != row["version"]:
            raise HTTPException(409, "Graph changed while loading; refresh the graph")
        return {"graph_version": row["version"], "nodes": nodes, "edges": edges}
    async with graph_repository.session() as session:
        return await session.execute_read(read)


def _validate_edge_pair(nodes, existing, pair, old, creating_or_updating):
    if old and old not in existing:
        raise HTTPException(404, "Relationship not found")
    if pair[0] not in nodes or pair[1] not in nodes:
        raise HTTPException(404, "Both concepts must be active and belong to this course")
    if pair[0] == pair[1]:
        raise HTTPException(422, "A concept cannot be its own prerequisite")
    if creating_or_updating and pair != old and pair in existing and existing[pair] != "REJECTED":
        raise HTTPException(409, "Relationship already exists")


def _validate_edge_topology(existing, pair, old, target_status):
    if target_status == "REJECTED":
        return
    graph = nx.DiGraph((s, t) for (s, t), st in existing.items() if st != "REJECTED" and (s, t) != old)
    graph.add_edge(*pair)
    if not nx.is_directed_acyclic_graph(graph):
        raise HTTPException(409, detail={"message": "Relationship would create a prerequisite cycle", "cycle": nx.find_cycle(graph)})


async def mutate_edge(course_id, actor, expected_version, req=None, old=None, status=None, note=""):
    async def write(tx):
        await lock_graph(tx, course_id, expected_version)
        rows = await (await graph_repository.run(tx, queries.MUTATE_EDGE_QUERY_3, course_id=course_id)).data()
        nodes = {r["id"] for r in rows}
        rows = await (await graph_repository.run(tx, queries.MUTATE_EDGE_QUERY_4, course_id=course_id)).data()
        existing = {(r["source"], r["target"]): r["status"] for r in rows}
        pair = (req.source_id, req.target_id) if req else old
        _validate_edge_pair(nodes, existing, pair, old, req is not None)
        target_status = "MANUAL" if req else status
        _validate_edge_topology(existing, pair, old, target_status)
        if old and pair != old:
            await graph_repository.run(tx, queries.MUTATE_EDGE_QUERY_2,
                course_id=course_id, source=old[0], target=old[1], actor=actor)
        await graph_repository.run(tx, queries.MUTATE_EDGE_QUERY_1, course_id=course_id, source=pair[0], target=pair[1], status=target_status,
            note=req.note if req else note, actor=actor)
        return {**await finish(tx, course_id), "source": pair[0], "target": pair[1], "status": target_status}
    return await write_graph(course_id, write)


async def create_edge(course_id: str, req: EdgeRequest, expected_version: int=..., x_actor_id: str=...):
    return await mutate_edge(course_id, x_actor_id, expected_version, req=req)


async def update_edge(course_id: str, source_id: str, target_id: str, req: EdgeRequest, expected_version: int=..., x_actor_id: str=...):
    return await mutate_edge(course_id, x_actor_id, expected_version, req=req, old=(source_id, target_id))


async def review_edge(course_id: str, source_id: str, target_id: str, req: ReviewRequest, expected_version: int=..., x_actor_id: str=...):
    return await mutate_edge(course_id, x_actor_id, expected_version, old=(source_id, target_id), status=req.status, note=req.note)


async def delete_edge(course_id: str, source_id: str, target_id: str, expected_version: int=..., x_actor_id: str=...):
    return await mutate_edge(course_id, x_actor_id, expected_version, old=(source_id, target_id), status="REJECTED")


async def update_concept(course_id: str, concept_id: str, req: DraftRequest, expected_version: int=..., x_actor_id: str=...):
    vectors = await embed_model.embed_batch(["; ".join([req.name, *req.aliases[:settings.CONCEPT_EMBED_MAX_ALIASES]])])
    if len(vectors) != 1 or len(vectors[0]) != 1024 or not all(math.isfinite(v) for v in vectors[0]):
        raise HTTPException(503, "Invalid embedding model output")
    async def write(tx):
        await lock_graph(tx, course_id, expected_version)
        rows = await (await graph_repository.run(tx, queries.UPDATE_CONCEPT_QUERY_2, course_id=course_id)).data()
        current = next((n for n in rows if n["id"] == concept_id and n["status"] == "ACTIVE"), None)
        if not current:
            raise HTTPException(404, "Active concept not found")
        # Retain the stable lookup key and old name as alias: ingestion and saved paths keep the same identity.
        aliases = list(dict.fromkeys([*req.aliases, *([current["name"]] if current["name"] != req.name else [])]))
        keys = {normalize_concept_key(v) for v in [current["key"] or "", req.name, *aliases]} - {""}
        for node in rows:
            if node["id"] != concept_id and keys.intersection(normalize_concept_key(v) for v in [node["key"] or "", node["name"], *node["aliases"]]):
                raise HTTPException(409, "Name or alias belongs to another concept (including archived concepts)")
        await graph_repository.run(tx, queries.UPDATE_CONCEPT_QUERY_1,
            course_id=course_id, id=concept_id, name=req.name, aliases=aliases, language=req.language, embedding=vectors[0], actor=x_actor_id)
        return {"id": concept_id, **await finish(tx, course_id)}
    return await write_graph(course_id, write)


async def confirm_concept(course_id: str, concept_id: str, expected_version: int=..., x_actor_id: str=...):
    async def write(tx):
        await lock_graph(tx, course_id, expected_version)
        row = await (await graph_repository.run(tx, queries.CONFIRM_CONCEPT_QUERY_1, course_id=course_id, id=concept_id, actor=x_actor_id)).single()
        if not row:
            raise HTTPException(404, "Active concept not found")
        return {"id": concept_id, **await finish(tx, course_id)}
    return await write_graph(course_id, write)

