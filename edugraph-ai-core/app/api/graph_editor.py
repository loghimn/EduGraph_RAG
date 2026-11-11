"""Instructor graph curation. Public role/ownership checks belong to Java.

Every mutation serializes with ingestion on Course and checks its graph version.
Rejected edges remain as tombstones so ingestion cannot recreate them.
"""
import math
from typing import Literal

import networkx as nx
from fastapi import APIRouter, Header, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field

from app.api.concept_creation import DraftRequest
from app.api.internal_auth import verify_internal_key
from app.config import settings
from app.core.embedding import embed_model
from app.core.text_utils import normalize_concept_key
from app.db.neo4j_client import neo4j_client
from app.retrieval.graph_cache import clear_course_graph_cache

router = APIRouter(prefix="/courses/{course_id}/knowledge-graph")


class EdgeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_id: str = Field(min_length=1, max_length=200)
    target_id: str = Field(min_length=1, max_length=200)
    note: str = Field(default="", max_length=2000)


class ReviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    status: Literal["CONFIRMED", "REJECTED"]
    note: str = Field(default="", max_length=2000)


async def lock_graph(tx, course_id, expected_version):
    result = await tx.run("""MATCH (c:Course {id: $course_id})
        SET c.concept_write_lock = coalesce(c.concept_write_lock, 0) + 1
        RETURN coalesce(c.version, 0) AS version""", course_id=course_id)
    row = await result.single()
    if not row:
        raise HTTPException(404, "Course graph not found")
    if row["version"] != expected_version:
        raise HTTPException(409, detail={"message": "Graph changed; reload before saving", "graph_version": row["version"]})


async def finish(tx, course_id):
    row = await (await tx.run("""MATCH (c:Course {id: $course_id})
        SET c.version = coalesce(c.version, 0) + 1
        RETURN c.version AS graph_version""", course_id=course_id)).single()
    return {"graph_version": row["graph_version"]}


async def write_graph(course_id, operation):
    async with neo4j_client.driver.session() as session:
        result = await session.execute_write(operation)
    clear_course_graph_cache(course_id)
    return result


@router.get("/review")
async def review_graph(course_id: str, x_internal_key: str = Header(...)):
    verify_internal_key(x_internal_key)
    # Neo4j uses read-committed isolation: check the version again after reading.
    async def read(tx):
        row = await (await tx.run("MATCH (c:Course {id: $course_id}) RETURN coalesce(c.version, 0) AS version",
                                 course_id=course_id)).single()
        if not row:
            return {"graph_version": 0, "nodes": [], "edges": []}
        nodes = await (await tx.run("""MATCH (n:Concept {course_id: $course_id})
            WHERE coalesce(n.status, 'ACTIVE') = 'ACTIVE'
            RETURN n.id AS id, n.name AS name, coalesce(n.aliases, []) AS aliases,
                coalesce(n.language, 'mixed') AS language, coalesce(n.review_status, 'AUTO') AS review_status,
                coalesce(n.origin, 'AUTO') AS origin ORDER BY name, id""", course_id=course_id)).data()
        edges = await (await tx.run("""MATCH (s:Concept {course_id: $course_id})-[r:PREREQUISITE_OF]->(t:Concept {course_id: $course_id})
            WHERE coalesce(s.status, 'ACTIVE') = 'ACTIVE' AND coalesce(t.status, 'ACTIVE') = 'ACTIVE'
            RETURN s.id AS source, t.id AS target, coalesce(r.status, 'AUTO') AS status,
                coalesce(r.score, 0) AS score, coalesce(r.evidence, '') AS evidence,
                r.evidence_chunk_id AS evidence_chunk_id, coalesce(r.review_note, '') AS note,
                r.reviewed_by AS reviewed_by, r.reviewed_at AS reviewed_at
            ORDER BY source, target""", course_id=course_id)).data()
        after = await (await tx.run("MATCH (c:Course {id: $course_id}) RETURN coalesce(c.version, 0) AS version",
                                   course_id=course_id)).single()
        if not after or after["version"] != row["version"]:
            raise HTTPException(409, "Graph changed while loading; refresh the graph")
        return {"graph_version": row["version"], "nodes": nodes, "edges": edges}
    async with neo4j_client.driver.session() as session:
        return await session.execute_read(read)


async def mutate_edge(course_id, actor, expected_version, req=None, old=None, status=None, note=""):
    async def write(tx):
        await lock_graph(tx, course_id, expected_version)
        rows = await (await tx.run("""MATCH (n:Concept {course_id: $course_id})
            WHERE coalesce(n.status, 'ACTIVE') = 'ACTIVE' RETURN n.id AS id""", course_id=course_id)).data()
        nodes = {r["id"] for r in rows}
        rows = await (await tx.run("""MATCH (s:Concept {course_id: $course_id})-[r:PREREQUISITE_OF]->(t:Concept {course_id: $course_id})
            WHERE coalesce(s.status, 'ACTIVE') = 'ACTIVE' AND coalesce(t.status, 'ACTIVE') = 'ACTIVE'
            RETURN s.id AS source, t.id AS target, coalesce(r.status, 'AUTO') AS status""", course_id=course_id)).data()
        existing = {(r["source"], r["target"]): r["status"] for r in rows}
        if old and old not in existing:
            raise HTTPException(404, "Relationship not found")
        pair = (req.source_id, req.target_id) if req else old
        if pair[0] not in nodes or pair[1] not in nodes:
            raise HTTPException(404, "Both concepts must be active and belong to this course")
        if pair[0] == pair[1]:
            raise HTTPException(422, "A concept cannot be its own prerequisite")
        if req and pair != old and pair in existing and existing[pair] != "REJECTED":
            raise HTTPException(409, "Relationship already exists")
        target_status = "MANUAL" if req else status
        graph = nx.DiGraph((s, t) for (s, t), st in existing.items() if st != "REJECTED" and (s, t) != old)
        if target_status != "REJECTED":
            graph.add_edge(*pair)
            if not nx.is_directed_acyclic_graph(graph):
                raise HTTPException(409, detail={"message": "Relationship would create a prerequisite cycle", "cycle": nx.find_cycle(graph)})
        if old and pair != old:
            await tx.run("""MATCH (s:Concept {course_id: $course_id, id: $source})-[r:PREREQUISITE_OF]->(t:Concept {course_id: $course_id, id: $target})
                SET r.status = 'REJECTED', r.reviewed_by = $actor, r.reviewed_at = timestamp(), r.review_note = 'Replaced by instructor'""",
                course_id=course_id, source=old[0], target=old[1], actor=actor)
        await tx.run("""MATCH (s:Concept {course_id: $course_id, id: $source}), (t:Concept {course_id: $course_id, id: $target})
            MERGE (s)-[r:PREREQUISITE_OF]->(t)
            SET r.status = $status, r.review_note = $note, r.reviewed_by = $actor, r.reviewed_at = timestamp()
            """, course_id=course_id, source=pair[0], target=pair[1], status=target_status,
            note=req.note if req else note, actor=actor)
        return {**await finish(tx, course_id), "source": pair[0], "target": pair[1], "status": target_status}
    return await write_graph(course_id, write)


@router.post("/edges")
async def create_edge(course_id: str, req: EdgeRequest, expected_version: int = Query(..., ge=0),
                      x_internal_key: str = Header(...), x_actor_id: str = Header(...)):
    verify_internal_key(x_internal_key)
    return await mutate_edge(course_id, x_actor_id, expected_version, req=req)


@router.put("/edges/{source_id}/{target_id}")
async def update_edge(course_id: str, source_id: str, target_id: str, req: EdgeRequest,
                      expected_version: int = Query(..., ge=0), x_internal_key: str = Header(...), x_actor_id: str = Header(...)):
    verify_internal_key(x_internal_key)
    return await mutate_edge(course_id, x_actor_id, expected_version, req=req, old=(source_id, target_id))


@router.post("/edges/{source_id}/{target_id}/review")
async def review_edge(course_id: str, source_id: str, target_id: str, req: ReviewRequest,
                      expected_version: int = Query(..., ge=0), x_internal_key: str = Header(...), x_actor_id: str = Header(...)):
    verify_internal_key(x_internal_key)
    return await mutate_edge(course_id, x_actor_id, expected_version, old=(source_id, target_id), status=req.status, note=req.note)


@router.delete("/edges/{source_id}/{target_id}")
async def delete_edge(course_id: str, source_id: str, target_id: str, expected_version: int = Query(..., ge=0),
                      x_internal_key: str = Header(...), x_actor_id: str = Header(...)):
    verify_internal_key(x_internal_key)
    return await mutate_edge(course_id, x_actor_id, expected_version, old=(source_id, target_id), status="REJECTED")


@router.put("/concepts/{concept_id}")
async def update_concept(course_id: str, concept_id: str, req: DraftRequest, expected_version: int = Query(..., ge=0),
                         x_internal_key: str = Header(...), x_actor_id: str = Header(...)):
    verify_internal_key(x_internal_key)
    vectors = await embed_model.embed_batch(["; ".join([req.name, *req.aliases[:settings.CONCEPT_EMBED_MAX_ALIASES]])])
    if len(vectors) != 1 or len(vectors[0]) != 1024 or not all(math.isfinite(v) for v in vectors[0]):
        raise HTTPException(503, "Invalid embedding model output")
    async def write(tx):
        await lock_graph(tx, course_id, expected_version)
        rows = await (await tx.run("""MATCH (n:Concept {course_id: $course_id})
            RETURN n.id AS id, n.key AS key, n.name AS name, coalesce(n.aliases, []) AS aliases,
                coalesce(n.status, 'ACTIVE') AS status""", course_id=course_id)).data()
        current = next((n for n in rows if n["id"] == concept_id and n["status"] == "ACTIVE"), None)
        if not current:
            raise HTTPException(404, "Active concept not found")
        # Retain the stable lookup key and old name as alias: ingestion and saved paths keep the same identity.
        aliases = list(dict.fromkeys([*req.aliases, *([current["name"]] if current["name"] != req.name else [])]))
        keys = {normalize_concept_key(v) for v in [current["key"] or "", req.name, *aliases]} - {""}
        for node in rows:
            if node["id"] != concept_id and keys.intersection(normalize_concept_key(v) for v in [node["key"] or "", node["name"], *node["aliases"]]):
                raise HTTPException(409, "Name or alias belongs to another concept (including archived concepts)")
        await tx.run("""MATCH (n:Concept {course_id: $course_id, id: $id})
            SET n.name = $name, n.aliases = $aliases, n.language = $language, n.embedding = $embedding,
                n.curated = true, n.review_status = 'CONFIRMED', n.reviewed_by = $actor, n.reviewed_at = timestamp()""",
            course_id=course_id, id=concept_id, name=req.name, aliases=aliases, language=req.language, embedding=vectors[0], actor=x_actor_id)
        return {"id": concept_id, **await finish(tx, course_id)}
    return await write_graph(course_id, write)


@router.post("/concepts/{concept_id}/review")
async def confirm_concept(course_id: str, concept_id: str, expected_version: int = Query(..., ge=0),
                          x_internal_key: str = Header(...), x_actor_id: str = Header(...)):
    verify_internal_key(x_internal_key)
    async def write(tx):
        await lock_graph(tx, course_id, expected_version)
        row = await (await tx.run("""MATCH (n:Concept {course_id: $course_id, id: $id})
            WHERE coalesce(n.status, 'ACTIVE') = 'ACTIVE'
            SET n.curated = true, n.review_status = 'CONFIRMED', n.reviewed_by = $actor, n.reviewed_at = timestamp()
            RETURN n.id AS id""", course_id=course_id, id=concept_id, actor=x_actor_id)).single()
        if not row:
            raise HTTPException(404, "Active concept not found")
        return {"id": concept_id, **await finish(tx, course_id)}
    return await write_graph(course_id, write)
