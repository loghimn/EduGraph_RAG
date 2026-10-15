from fastapi import APIRouter, Header, HTTPException

from app.api.internal_auth import verify_internal_key
from app.db.repo import get_knowledge_graph_concept
from app.retrieval.graph_cache import get_course_graph

router = APIRouter()


@router.get("/courses/{course_id}/knowledge-graph")
async def get_knowledge_graph(
    course_id: str,
    x_internal_key: str = Header(...),
):
    verify_internal_key(x_internal_key)

    graph, graph_version, _, _ = await get_course_graph(course_id)
    nodes = [
        {
            "id": concept_id,
            "key": data.get("key"),
            "name": data.get("name", concept_id),
            "aliases": data.get("aliases", []),
            "language": data.get("language", "mixed"),
            "order": data.get("order", 0),
        }
        for concept_id, data in graph.nodes(data=True)
    ]
    edges = [
        {
            "id": f"{source}->{target}",
            "source": source,
            "target": target,
            "type": "PREREQUISITE_OF",
            "status": data.get("status", "AUTO"),
            "score": data.get("score", 0.0),
        }
        for source, target, data in graph.edges(data=True)
    ]
    return {
        "course_id": course_id,
        "graph_version": graph_version,
        "nodes": nodes,
        "edges": edges,
        "stats": {"concepts": len(nodes), "edges": len(edges)},
    }


@router.get("/courses/{course_id}/knowledge-graph/concepts/{concept_id}")
async def get_knowledge_graph_concept_detail(
    course_id: str,
    concept_id: str,
    x_internal_key: str = Header(...),
):
    verify_internal_key(x_internal_key)

    concept = await get_knowledge_graph_concept(course_id, concept_id)
    if concept is None:
        raise HTTPException(status_code=404, detail="Concept not found in course")
    return concept
