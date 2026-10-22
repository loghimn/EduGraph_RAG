from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api import concept_creation as api

app = FastAPI()
app.include_router(api.router)
client = TestClient(app)
HEADERS = {"X-Internal-Key": "test-internal-api-key", "X-Actor-Id": "15"}
BASE = "/courses/22/knowledge-graph/concept-drafts"


class Result:
    def __init__(self, record=None, rows=None):
        self.record, self.rows = record, rows or []

    async def single(self):
        return self.record

    async def data(self):
        return self.rows


class Session:
    def __init__(self, results):
        self.results = iter(results)
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        pass

    async def run(self, query, **params):
        self.calls.append((query, params))
        return next(self.results)

    async def execute_write(self, fn):
        return await fn(self)


def setup_session(monkeypatch, results):
    session = Session(results)
    monkeypatch.setattr(api.neo4j_client, "driver", type("Driver", (), {"session": lambda self: session})())
    return session


def test_compute_uses_existing_model_and_saves_only_draft(monkeypatch):
    session = setup_session(monkeypatch, [Result(), Result(), Result()])
    embed = AsyncMock(return_value=[[0.1] * 1024])
    monkeypatch.setattr(api.embed_model, "embed_batch", embed)
    response = client.post(BASE, headers=HEADERS, json={"name": " HTTP Requests ", "aliases": ["Request", "request"]})
    assert response.status_code == 201
    assert response.json()["embedding_ready"] is True
    assert "embedding" not in response.json()
    embed.assert_awaited_once_with(["HTTP Requests; Request"])
    query, params = session.calls[-1]
    assert "CREATE (d:ConceptDraft" in query
    assert params["actor"] == "15"
    assert params["key"] == "http requests"


@pytest.mark.parametrize("payload", [{"name": "   "}, {"name": "???"}, {"name": "A", "embedding": [1]}, {"name": "A", "language": "xx"}])
def test_invalid_or_client_generated_fields_are_rejected(payload):
    assert client.post(BASE, headers=HEADERS, json=payload).status_code == 422


def test_duplicate_does_not_call_embedding(monkeypatch):
    setup_session(monkeypatch, [Result(), Result(rows=[{"id": "existing", "key": "http requests", "name": "HTTP Requests", "aliases": []}])])
    embed = AsyncMock()
    monkeypatch.setattr(api.embed_model, "embed_batch", embed)
    assert client.post(BASE, headers=HEADERS, json={"name": "HTTP Requests"}).status_code == 409
    embed.assert_not_awaited()


def test_publish_is_idempotent(monkeypatch):
    session = setup_session(monkeypatch, [Result(), Result({"draft": {"published_id": "concept-1"}}), Result({"version": 7})])
    calls = []
    monkeypatch.setattr(api, "clear_course_graph_cache", calls.append)
    response = client.post(BASE + "/draft-1/publish", headers=HEADERS)
    assert response.json() == {"id": "concept-1", "graph_version": 7, "already_published": True}
    assert calls == ["22"]
    assert session.calls[1][1]["actor"] == "15"


def test_expired_draft_cannot_publish(monkeypatch):
    setup_session(monkeypatch, [Result(), Result({"draft": {"expires_at": 1}}), Result({"now": 2})])
    assert client.post(BASE + "/draft-1/publish", headers=HEADERS).status_code == 410


def test_other_actors_draft_not_found(monkeypatch):
    setup_session(monkeypatch, [Result(), Result()])
    assert client.post(BASE + "/draft-1/publish", headers=HEADERS).status_code == 404


def test_publish_writes_manual_node_and_version_in_one_transaction(monkeypatch):
    class Record(dict):
        def data(self):
            return dict(self)

    draft = {"name": "HTTP Requests", "key": "http requests", "aliases": [],
             "language": "en", "embedding": [0.1] * 1024,
             "embedding_model": "BAAI/bge-m3", "expires_at": 10}
    session = setup_session(monkeypatch, [Result(), Result({"draft": draft}),
        Result({"now": 2}), Result(), Result(Record(id="new", graph_version=8))])
    monkeypatch.setattr(api, "clear_course_graph_cache", lambda _: None)
    response = client.post(BASE + "/draft-1/publish", headers=HEADERS)
    assert response.status_code == 200
    assert response.json()["graph_version"] == 8
    query, params = session.calls[-1]
    assert "origin: 'MANUAL'" in query
    assert "course.version = coalesce(course.version, 0) + 1" in query
    assert params["embedding"] == draft["embedding"]
    assert params["course_key"] == "22|http requests"


def test_internal_auth_required():
    assert client.post(BASE, headers={"X-Internal-Key": "wrong", "X-Actor-Id": "15"}, json={"name": "A"}).status_code == 403
