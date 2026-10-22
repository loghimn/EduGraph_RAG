"""Owner-authorized lifecycle operations (public ownership is checked in core-service)."""
from fastapi import APIRouter, Header, HTTPException, Query
import networkx as nx

from app.api.internal_auth import verify_internal_key
from app.db.neo4j_client import neo4j_client
from app.retrieval.graph_cache import clear_course_graph_cache

router = APIRouter(prefix="/courses/{course_id}/knowledge-graph")


@router.get("/archived-concepts")
async def archived_concepts(course_id: str, x_internal_key: str = Header(...)):
    verify_internal_key(x_internal_key)
    async with neo4j_client.driver.session() as session:
        result = await session.run("""
            MATCH (n:Concept {course_id: $course_id, status: 'ARCHIVED'})
            RETURN n.id AS id, n.name AS name, n.archived_by AS archived_by,
                n.archived_at AS archived_at ORDER BY n.archived_at DESC, n.id
        """, course_id=course_id)
        return {"concepts": await result.data()}


@router.get("/concepts/{concept_id}/deletion-impact")
async def deletion_impact(course_id: str, concept_id: str, x_internal_key: str = Header(...)):
    verify_internal_key(x_internal_key)
    async with neo4j_client.driver.session() as session:
        result = await session.run("""
            MATCH (n:Concept {id: $concept_id, course_id: $course_id})
            MATCH (course:Course {id: $course_id})
            OPTIONAL MATCH (ch:Chunk {course_id: $course_id})-[:MENTIONS]->(n)
            WITH n, course, count(DISTINCT ch) AS source_chunks
            OPTIONAL MATCH (src:Concept {course_id: $course_id})-[incoming:PREREQUISITE_OF]->(n)
            WHERE coalesce(src.status, 'ACTIVE') = 'ACTIVE' AND coalesce(incoming.status, 'AUTO') <> 'REJECTED'
            WITH n, course, source_chunks, count(incoming) AS incoming_edges
            OPTIONAL MATCH (n)-[outgoing:PREREQUISITE_OF]->(tgt:Concept {course_id: $course_id})
            WHERE coalesce(tgt.status, 'ACTIVE') = 'ACTIVE' AND coalesce(outgoing.status, 'AUTO') <> 'REJECTED'
            RETURN n.id AS id, n.name AS name, coalesce(n.status, 'ACTIVE') AS status,
                source_chunks, incoming_edges, count(outgoing) AS outgoing_edges,
                coalesce(course.version, 0) AS graph_version
        """, course_id=course_id, concept_id=concept_id)
        record = await result.single()
        if not record:
            raise HTTPException(404, "Concept not found in course")
        return record.data()


async def change_status(course_id, concept_id, actor, expected_version, restore):
    target = "ACTIVE" if restore else "ARCHIVED"

    async def write(tx):
        # All graph writers use the same course lock before mutating the graph.
        result = await tx.run("""
            MATCH (course:Course {id: $course_id})
            SET course.concept_write_lock = coalesce(course.concept_write_lock, 0) + 1
            WITH course
            MATCH (n:Concept {course_id: $course_id, id: $concept_id})
            RETURN coalesce(n.status, 'ACTIVE') AS status, coalesce(course.version, 0) AS version
        """, course_id=course_id, concept_id=concept_id)
        record = await result.single()
        if not record:
            raise HTTPException(404, "Concept not found in course")
        # Retry of an already completed action is harmless, even with its old version.
        if record["status"] == target:
            return {"id": concept_id, "status": target, "graph_version": record["version"], "changed": False}
        if record["version"] != expected_version:
            raise HTTPException(409, detail={"message": "Graph changed; reload deletion impact", "graph_version": record["version"]})
        if restore:
            # Restoring retained edges must not introduce a cycle in the active graph.
            result = await tx.run("""
                MATCH (src:Concept {course_id: $course_id})-[r:PREREQUISITE_OF]->(tgt:Concept {course_id: $course_id})
                WHERE (coalesce(src.status, 'ACTIVE') = 'ACTIVE' OR src.id = $concept_id)
                  AND (coalesce(tgt.status, 'ACTIVE') = 'ACTIVE' OR tgt.id = $concept_id)
                  AND coalesce(r.status, 'AUTO') <> 'REJECTED'
                RETURN src.id AS source, tgt.id AS target
            """, course_id=course_id, concept_id=concept_id)
            graph = nx.DiGraph((row["source"], row["target"]) for row in await result.data())
            if concept_id in graph:
                component = nx.node_connected_component(graph.to_undirected(), concept_id)
                try:
                    cycle = nx.find_cycle(graph.subgraph(component))
                except nx.NetworkXNoCycle:
                    pass
                else:
                    raise HTTPException(409, detail={"message": "Restoring concept would leave a cycle", "cycle": cycle})
        query = """
            MATCH (course:Course {id: $course_id}), (n:Concept {course_id: $course_id, id: $concept_id})
            SET n.status = $status,
                course.version = coalesce(course.version, 0) + 1
        """
        query += (" SET n.restored_by = $actor, n.restored_at = timestamp()" if restore
                  else " SET n.archived_by = $actor, n.archived_at = timestamp()")
        query += " RETURN n.id AS id, n.status AS status, course.version AS graph_version"
        result = await tx.run(query, course_id=course_id, concept_id=concept_id, actor=actor, status=target)
        data = (await result.single()).data()
        return {**data, "changed": True}

    async with neo4j_client.driver.session() as session:
        data = await session.execute_write(write)
    clear_course_graph_cache(course_id)
    return data


@router.delete("/concepts/{concept_id}")
async def archive_concept(course_id: str, concept_id: str, expected_version: int = Query(..., ge=0),
                          x_internal_key: str = Header(...), x_actor_id: str = Header(...)):
    verify_internal_key(x_internal_key)
    return await change_status(course_id, concept_id, x_actor_id, expected_version, False)


@router.post("/concepts/{concept_id}/restore")
async def restore_concept(course_id: str, concept_id: str, expected_version: int = Query(..., ge=0),
                          x_internal_key: str = Header(...), x_actor_id: str = Header(...)):
    verify_internal_key(x_internal_key)
    return await change_status(course_id, concept_id, x_actor_id, expected_version, True)
