"""Server-owned embedding drafts; only publishing creates a Concept node."""
import math
from typing import Literal
from uuid import uuid4

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator
from neo4j.exceptions import ConstraintError

from app.api.internal_auth import verify_internal_key
from app.config import settings
from app.core.embedding import embed_model
from app.core.text_utils import normalize_concept_key
from app.db.neo4j_client import neo4j_client
from app.retrieval.graph_cache import clear_course_graph_cache

router = APIRouter(prefix="/courses/{course_id}/knowledge-graph")


class DraftRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=200)
    aliases: list[str] = Field(default_factory=list, max_length=50)
    language: Literal["vi", "en", "mixed"] = "mixed"

    @field_validator("name")
    @classmethod
    def clean_name(cls, value):
        value = " ".join(value.split())
        if not normalize_concept_key(value):
            raise ValueError("Name must contain letters or numbers")
        return value

    @field_validator("aliases")
    @classmethod
    def clean_aliases(cls, values):
        result, seen = [], set()
        for value in values:
            value = " ".join(value.split())
            key = normalize_concept_key(value)
            if not key or len(value) > 200:
                raise ValueError("Aliases must contain text and be at most 200 characters")
            if key not in seen:
                seen.add(key)
                result.append(value)
        return result


async def check_duplicate(tx, course_id, keys):
    result = await tx.run("""
        MATCH (n:Concept {course_id: $course_id})
        RETURN n.id AS id, n.key AS key, n.name AS name, coalesce(n.aliases, []) AS aliases
    """, course_id=course_id)
    for node in await result.data():
        existing = {normalize_concept_key(v) for v in [node["key"] or "", node["name"] or "", *node["aliases"]]}
        if existing.intersection(keys):
            raise HTTPException(409, detail={"message": "Concept already exists", "concept_id": node["id"]})


@router.post("/concept-drafts", status_code=201)
async def compute_draft(course_id: str, req: DraftRequest,
                        x_internal_key: str = Header(...), x_actor_id: str = Header(...)):
    verify_internal_key(x_internal_key)
    keys = [normalize_concept_key(v) for v in [req.name, *req.aliases]]
    async with neo4j_client.driver.session() as session:
        await session.run("""MATCH (d:ConceptDraft)
            WHERE d.expires_at < timestamp() AND d.published_id IS NULL DELETE d""")
        await check_duplicate(session, course_id, keys)
    aliases = req.aliases[:settings.CONCEPT_EMBED_MAX_ALIASES]
    text = "; ".join([req.name, *aliases])
    vectors = await embed_model.embed_batch([text])
    if len(vectors) != 1 or len(vectors[0]) != 1024 or not all(math.isfinite(v) for v in vectors[0]):
        raise HTTPException(503, "Invalid embedding model output")
    draft_id = str(uuid4())
    async with neo4j_client.driver.session() as session:
        await session.run("""
            CREATE (d:ConceptDraft {id: $id, course_id: $course_id, created_by: $actor,
                name: $name, aliases: $aliases, language: $language, key: $key,
                embedding: $embedding, embedding_model: 'BAAI/bge-m3', created_at: timestamp(),
                expires_at: timestamp() + 86400000})
        """, id=draft_id, course_id=course_id, actor=x_actor_id, name=req.name,
            aliases=req.aliases, language=req.language, key=keys[0], embedding=vectors[0])
    return {"draft_id": draft_id, **req.model_dump(), "embedding_ready": True,
            "embedding_model": "BAAI/bge-m3", "embedding_dimensions": 1024, "expires_in_seconds": 86400}


@router.post("/concept-drafts/{draft_id}/publish")
async def publish_draft(course_id: str, draft_id: str,
                        x_internal_key: str = Header(...), x_actor_id: str = Header(...)):
    verify_internal_key(x_internal_key)

    async def write(tx):
        # Serialize concurrent manual publications on the course before checking duplicates.
        await tx.run("""MERGE (c:Course {id: $course_id})
            SET c.concept_write_lock = coalesce(c.concept_write_lock, 0) + 1""", course_id=course_id)
        result = await tx.run("""MATCH (d:ConceptDraft {id: $id, course_id: $course_id, created_by: $actor})
            RETURN properties(d) AS draft""", id=draft_id, course_id=course_id, actor=x_actor_id)
        record = await result.single()
        if not record:
            raise HTTPException(404, "Draft not found")
        draft = record["draft"]
        if draft.get("published_id"):
            result = await tx.run("MATCH (c:Course {id: $course_id}) RETURN c.version AS version", course_id=course_id)
            return {"id": draft["published_id"], "graph_version": (await result.single())["version"], "already_published": True}
        result = await tx.run("RETURN timestamp() AS now")
        if draft["expires_at"] <= (await result.single())["now"]:
            raise HTTPException(410, "Draft expired; compute embedding again")
        await check_duplicate(tx, course_id, [normalize_concept_key(v) for v in [draft["name"], *draft["aliases"]]])
        concept_id = str(uuid4())
        result = await tx.run("""
            MATCH (course:Course {id: $course_id})
            OPTIONAL MATCH (existing:Concept {course_id: $course_id})
            WITH course, coalesce(max(existing.order), 0) + 1 AS next_order
            CREATE (n:Concept {id: $id, course_id: $course_id, course_key: $course_key,
                key: $key, name: $name, aliases: $aliases, language: $language,
                embedding: $embedding, embedding_model: $embedding_model, order: next_order,
                origin: 'MANUAL', status: 'ACTIVE', created_by: $actor, created_at: timestamp()})
            SET course.version = coalesce(course.version, 0) + 1
            WITH n, course
            MATCH (d:ConceptDraft {id: $draft_id})
            SET d.published_id = n.id
            REMOVE d.embedding
            RETURN n.id AS id, n.name AS name, n.aliases AS aliases, n.language AS language,
                n.order AS order, n.key AS key, course.version AS graph_version
        """, **{k: draft[k] for k in ["key", "name", "aliases", "language", "embedding", "embedding_model"]},
            id=concept_id, course_id=course_id, course_key=f"{course_id}|{draft['key']}", actor=x_actor_id, draft_id=draft_id)
        return (await result.single()).data()

    async with neo4j_client.driver.session() as session:
        try:
            result = await session.execute_write(write)
        except ConstraintError:
            raise HTTPException(409, "Concept already exists; reload the graph")
    clear_course_graph_cache(course_id)
    return result
