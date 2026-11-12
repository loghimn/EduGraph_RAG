"""Owner-authorized lifecycle operations (public ownership is checked in core-service)."""

from app.db.queries import concept_lifecycle as queries
from fastapi import HTTPException
import networkx as nx

from app.db import graph_repository
from app.retrieval.graph_cache import clear_course_graph_cache


async def archived_concepts(course_id: str):
    async with graph_repository.session() as session:
        result = await graph_repository.run(session, queries.ARCHIVED_CONCEPTS_QUERY_1, course_id=course_id)
        return {"concepts": await result.data()}


async def deletion_impact(course_id: str, concept_id: str):
    async with graph_repository.session() as session:
        result = await graph_repository.run(session, queries.DELETION_IMPACT_QUERY_1, course_id=course_id, concept_id=concept_id)
        record = await result.single()
        if not record:
            raise HTTPException(404, "Concept not found in course")
        return record.data()


async def change_status(course_id, concept_id, actor, expected_version, restore):
    target = "ACTIVE" if restore else "ARCHIVED"

    async def write(tx):
        # All graph writers use the same course lock before mutating the graph.
        result = await graph_repository.run(tx, queries.CHANGE_STATUS_QUERY_3, course_id=course_id, concept_id=concept_id)
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
            result = await graph_repository.run(tx, queries.CHANGE_STATUS_QUERY_4, course_id=course_id, concept_id=concept_id)
            graph = nx.DiGraph((row["source"], row["target"]) for row in await result.data())
            if concept_id in graph:
                component = nx.node_connected_component(graph.to_undirected(), concept_id)
                try:
                    cycle = nx.find_cycle(graph.subgraph(component))
                except nx.NetworkXNoCycle:
                    pass
                else:
                    raise HTTPException(409, detail={"message": "Restoring concept would leave a cycle", "cycle": cycle})
        query = queries.CHANGE_STATUS_QUERY_1
        query += (" SET n.restored_by = $actor, n.restored_at = timestamp()" if restore
                  else " SET n.archived_by = $actor, n.archived_at = timestamp()")
        query += queries.CHANGE_STATUS_QUERY_2
        result = await graph_repository.run(tx, query, course_id=course_id, concept_id=concept_id, actor=actor, status=target)
        data = (await result.single()).data()
        return {**data, "changed": True}

    async with graph_repository.session() as session:
        data = await session.execute_write(write)
    clear_course_graph_cache(course_id)
    return data


async def archive_concept(course_id: str, concept_id: str, expected_version: int=..., x_actor_id: str=...):
    return await change_status(course_id, concept_id, x_actor_id, expected_version, False)


async def restore_concept(course_id: str, concept_id: str, expected_version: int=..., x_actor_id: str=...):
    return await change_status(course_id, concept_id, x_actor_id, expected_version, True)

