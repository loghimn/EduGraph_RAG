"""Server-owned embedding drafts; only publishing creates a Concept node."""

from app.db.queries import concept_creation as queries
from app.schemas.concept_creation import DraftRequest
import math
from uuid import uuid4

from fastapi import HTTPException
from neo4j.exceptions import ConstraintError

from app.config import settings
from app.core.embedding import embed_model
from app.core.text_utils import normalize_concept_key
from app.db import graph_repository
from app.retrieval.graph_cache import clear_course_graph_cache


async def check_duplicate(tx, course_id, keys):
    result = await graph_repository.run(tx, queries.CHECK_DUPLICATE_QUERY_1, course_id=course_id)
    for node in await result.data():
        existing = {normalize_concept_key(v) for v in [node["key"] or "", node["name"] or "", *node["aliases"]]}
        if existing.intersection(keys):
            raise HTTPException(409, detail={"message": "Concept already exists", "concept_id": node["id"]})


async def compute_draft(course_id: str, req: DraftRequest, x_actor_id: str=...):
    keys = [normalize_concept_key(v) for v in [req.name, *req.aliases]]
    async with graph_repository.session() as session:
        await graph_repository.run(session, queries.COMPUTE_DRAFT_QUERY_1)
        await check_duplicate(session, course_id, keys)
    aliases = req.aliases[:settings.CONCEPT_EMBED_MAX_ALIASES]
    text = "; ".join([req.name, *aliases])
    vectors = await embed_model.embed_batch([text])
    if len(vectors) != 1 or len(vectors[0]) != 1024 or not all(math.isfinite(v) for v in vectors[0]):
        raise HTTPException(503, "Invalid embedding model output")
    draft_id = str(uuid4())
    async with graph_repository.session() as session:
        await graph_repository.run(session, queries.COMPUTE_DRAFT_QUERY_2, id=draft_id, course_id=course_id, actor=x_actor_id, name=req.name,
            aliases=req.aliases, language=req.language, key=keys[0], embedding=vectors[0])
    return {"draft_id": draft_id, **req.model_dump(), "embedding_ready": True,
            "embedding_model": "BAAI/bge-m3", "embedding_dimensions": 1024, "expires_in_seconds": 86400}


async def publish_draft(course_id: str, draft_id: str, x_actor_id: str=...):
    async def write(tx):
        # Serialize concurrent manual publications on the course before checking duplicates.
        await graph_repository.run(tx, queries.PUBLISH_DRAFT_QUERY_1, course_id=course_id, actor=x_actor_id)
        result = await graph_repository.run(tx, queries.PUBLISH_DRAFT_QUERY_2, id=draft_id, course_id=course_id, actor=x_actor_id)
        record = await result.single()
        if not record:
            raise HTTPException(404, "Draft not found")
        draft = record["draft"]
        if draft.get("published_id"):
            result = await graph_repository.run(tx, queries.PUBLISH_DRAFT_QUERY_5, course_id=course_id)
            return {"id": draft["published_id"], "graph_version": (await result.single())["version"], "already_published": True}
        result = await graph_repository.run(tx, queries.PUBLISH_DRAFT_QUERY_3)
        if draft["expires_at"] <= (await result.single())["now"]:
            raise HTTPException(410, "Draft expired; compute embedding again")
        await check_duplicate(tx, course_id, [normalize_concept_key(v) for v in [draft["name"], *draft["aliases"]]])
        concept_id = str(uuid4())
        result = await graph_repository.run(tx, queries.PUBLISH_DRAFT_QUERY_4, **{k: draft[k] for k in ["key", "name", "aliases", "language", "embedding", "embedding_model"]},
            id=concept_id, course_id=course_id, course_key=f"{course_id}|{draft['key']}", actor=x_actor_id, draft_id=draft_id)
        return (await result.single()).data()

    async with graph_repository.session() as session:
        try:
            result = await session.execute_write(write)
        except ConstraintError:
            raise HTTPException(409, "Concept already exists; reload the graph")
    clear_course_graph_cache(course_id)
    return result

