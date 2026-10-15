import networkx as nx
from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)
INTERNAL_HEADERS = {"X-Internal-Key": "test-internal-api-key"}


def test_knowledge_graph_returns_concepts_and_prerequisite_edges(monkeypatch):
    graph = nx.DiGraph()
    graph.add_node(
        "concept-a",
        key="concept a",
        name="Concept A",
        aliases=["A"],
        language="en",
        order=1,
    )
    graph.add_node("concept-b", name="Concept B", order=2)
    graph.add_edge("concept-a", "concept-b", status="CONFIRMED", score=0.9)

    async def fake_get_course_graph(course_id):
        assert course_id == "course-1"
        return graph, 7, {}, []

    monkeypatch.setattr("app.api.knowledge_graph.get_course_graph", fake_get_course_graph)

    response = client.get(
        "/courses/course-1/knowledge-graph",
        headers=INTERNAL_HEADERS,
    )

    assert response.status_code == 200
    assert response.json() == {
        "course_id": "course-1",
        "graph_version": 7,
        "nodes": [
            {
                "id": "concept-a",
                "key": "concept a",
                "name": "Concept A",
                "aliases": ["A"],
                "language": "en",
                "order": 1,
            },
            {
                "id": "concept-b",
                "key": None,
                "name": "Concept B",
                "aliases": [],
                "language": "mixed",
                "order": 2,
            },
        ],
        "edges": [
            {
                "id": "concept-a->concept-b",
                "source": "concept-a",
                "target": "concept-b",
                "type": "PREREQUISITE_OF",
                "status": "CONFIRMED",
                "score": 0.9,
            }
        ],
        "stats": {"concepts": 2, "edges": 1},
    }


def test_knowledge_graph_concept_detail_includes_sources(monkeypatch):
    concept = {
        "id": "concept-a",
        "name": "Concept A",
        "aliases": ["A"],
        "language": "en",
        "sources": [{"filename": "source.pdf", "page": 4, "snippet": "Evidence"}],
    }

    async def fake_get_concept(course_id, concept_id):
        assert (course_id, concept_id) == ("course-1", "concept-a")
        return concept

    monkeypatch.setattr("app.api.knowledge_graph.get_knowledge_graph_concept", fake_get_concept)

    response = client.get(
        "/courses/course-1/knowledge-graph/concepts/concept-a",
        headers=INTERNAL_HEADERS,
    )

    assert response.status_code == 200
    assert response.json() == concept


def test_knowledge_graph_concept_detail_returns_404_when_missing(monkeypatch):
    async def fake_get_concept(course_id, concept_id):
        return None

    monkeypatch.setattr("app.api.knowledge_graph.get_knowledge_graph_concept", fake_get_concept)

    response = client.get(
        "/courses/course-1/knowledge-graph/concepts/missing",
        headers=INTERNAL_HEADERS,
    )

    assert response.status_code == 404
