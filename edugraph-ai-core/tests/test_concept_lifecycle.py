from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api import concept_lifecycle as api
from app.concept_extraction.merger import merge_concepts
from app.retrieval import graph_cache
import networkx as nx

app = FastAPI()
app.include_router(api.router)
client = TestClient(app)
BASE = "/courses/22/knowledge-graph"
HEADERS = {"X-Internal-Key": "test-internal-api-key", "X-Actor-Id": "15"}


class Record(dict):
    def data(self):
        return dict(self)


def result(record=None, rows=None):
    value = MagicMock()
    value.single = AsyncMock(return_value=Record(record) if record is not None else None)
    value.data = AsyncMock(return_value=rows or [])
    return value


def database(monkeypatch, results):
    session = MagicMock()
    session.__aenter__ = AsyncMock(return_value=session)
    session.__aexit__ = AsyncMock(return_value=None)
    session.run = AsyncMock(side_effect=results)

    async def execute_write(fn):
        return await fn(session)

    session.execute_write = execute_write
    monkeypatch.setattr(api.neo4j_client, "driver", MagicMock(session=MagicMock(return_value=session)))
    clear = MagicMock()
    monkeypatch.setattr(api, "clear_course_graph_cache", clear)
    return session, clear


def test_archive_changes_status_and_version_without_deleting_sources(monkeypatch):
    session, clear = database(monkeypatch, [result({"status": "ACTIVE", "version": 8}),
        result({"id": "a", "status": "ARCHIVED", "graph_version": 9})])
    response = client.delete(BASE + "/concepts/a?expected_version=8", headers=HEADERS)
    assert response.status_code == 200
    assert response.json() == {"id": "a", "status": "ARCHIVED", "graph_version": 9, "changed": True}
    query, kwargs = session.run.call_args
    assert kwargs["actor"] == "15"
    assert kwargs["status"] == "ARCHIVED"
    assert "DELETE" not in query[0]
    clear.assert_called_once_with("22")


def test_stale_preview_does_not_archive(monkeypatch):
    session, clear = database(monkeypatch, [result({"status": "ACTIVE", "version": 9})])
    response = client.delete(BASE + "/concepts/a?expected_version=8", headers=HEADERS)
    assert response.status_code == 409
    assert response.json()["detail"]["graph_version"] == 9
    assert session.run.await_count == 1
    clear.assert_not_called()


def test_repeated_archive_is_harmless(monkeypatch):
    session, _ = database(monkeypatch, [result({"status": "ARCHIVED", "version": 9})])
    response = client.delete(BASE + "/concepts/a?expected_version=8", headers=HEADERS)
    assert response.json()["changed"] is False
    assert session.run.await_count == 1


def test_restore_rejects_retained_edges_that_create_cycle(monkeypatch):
    session, clear = database(monkeypatch, [result({"status": "ARCHIVED", "version": 9}),
        result(rows=[{"source": "a", "target": "b"}, {"source": "b", "target": "a"}])])
    response = client.post(BASE + "/concepts/a/restore?expected_version=9", headers=HEADERS)
    assert response.status_code == 409
    assert response.json()["detail"]["cycle"]
    assert session.run.await_count == 2
    clear.assert_not_called()


@pytest.mark.parametrize("edges", [[], [{"source": "a", "target": "b"}]])
def test_restore_isolated_or_connected_concept(monkeypatch, edges):
    _, clear = database(monkeypatch, [result({"status": "ARCHIVED", "version": 9}), result(rows=edges),
        result({"id": "a", "status": "ACTIVE", "graph_version": 10})])
    response = client.post(BASE + "/concepts/a/restore?expected_version=9", headers=HEADERS)
    assert response.json()["status"] == "ACTIVE"
    assert response.json()["graph_version"] == 10
    clear.assert_called_once_with("22")


def test_impact_returns_sources_edges_and_version(monkeypatch):
    data = {"id": "a", "status": "ACTIVE", "source_chunks": 2, "incoming_edges": 1,
            "outgoing_edges": 3, "graph_version": 8}
    database(monkeypatch, [result(data)])
    assert client.get(BASE + "/concepts/a/deletion-impact", headers=HEADERS).json() == data


def test_archived_list(monkeypatch):
    database(monkeypatch, [result(rows=[{"id": "a", "name": "HTTP"}])])
    assert client.get(BASE + "/archived-concepts", headers=HEADERS).json()["concepts"][0]["id"] == "a"


def test_missing_or_other_course_concept_returns_404(monkeypatch):
    database(monkeypatch, [result()])
    assert client.delete(BASE + "/concepts/a?expected_version=8", headers=HEADERS).status_code == 404


def test_version_is_required():
    assert client.delete(BASE + "/concepts/a", headers=HEADERS).status_code == 422


def test_invalid_internal_key():
    headers = {**HEADERS, "X-Internal-Key": "invalid"}
    assert client.delete(BASE + "/concepts/a?expected_version=8", headers=headers).status_code == 403


def test_ingestion_maps_alias_to_archived_concept_without_recreating():
    extracted = {"canonical": "HTTP Request", "aliases": [], "language": "en"}
    archived = {"id": "a", "key": "http requests", "name": "HTTP Requests", "aliases": ["HTTP Request"],
                "language": "en", "status": "ARCHIVED"}
    new, _ = merge_concepts([extracted], [archived])
    assert new == []
    assert extracted["key"] == "http requests"
    assert archived["status"] == "ARCHIVED"


@pytest.mark.asyncio
async def test_other_worker_version_change_reloads_cached_graph(monkeypatch):
    old = nx.DiGraph()
    old.add_node("a")
    new = nx.DiGraph()
    monkeypatch.setattr(graph_cache, "_COURSE_GRAPH_CACHE", {})
    monkeypatch.setattr(graph_cache, "get_course_version", AsyncMock(side_effect=[8, 9]))
    load = AsyncMock(side_effect=[(old, {}, []), (new, {}, [])])
    monkeypatch.setattr(graph_cache, "load_graph_from_neo4j", load)
    before, _, _, _ = await graph_cache.get_course_graph("22")
    after, version, _, _ = await graph_cache.get_course_graph("22")
    assert "a" in before
    assert "a" not in after
    assert version == 9
    assert load.await_count == 2
