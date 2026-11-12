from app.db import graph_repository
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api import graph_editor as routes
from app.services import graph_editor as api

app = FastAPI()
app.include_router(routes.router)
client = TestClient(app)
BASE = '/courses/22/knowledge-graph'
HEADERS = {'X-Internal-Key': 'test-internal-api-key', 'X-Actor-Id': '15'}


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
    async def execute(fn):
        return await fn(session)
    session.execute_write = execute
    session.execute_read = execute
    monkeypatch.setattr(graph_repository.neo4j_client, 'driver', MagicMock(session=MagicMock(return_value=session)))
    clear = MagicMock()
    monkeypatch.setattr(api, 'clear_course_graph_cache', clear)
    return session, clear


def edge_setup(monkeypatch, edges=(), nodes=('a', 'b', 'c'), tail=()):
    return database(monkeypatch, [result({'version': 8}), result(rows=[{'id': n} for n in nodes]),
        result(rows=list(edges)), *tail])


def test_create_edge_increments_version_and_records_actor(monkeypatch):
    session, clear = edge_setup(monkeypatch, tail=[result(), result({'graph_version': 9})])
    response = client.post(BASE + '/edges?expected_version=8', headers=HEADERS,
                           json={'source_id': 'a', 'target_id': 'b', 'note': 'Foundation'})
    assert response.status_code == 200
    assert response.json()['status'] == 'MANUAL'
    assert response.json()['graph_version'] == 9
    kwargs = session.run.call_args_list[3].kwargs
    assert kwargs['actor'] == '15' and kwargs['note'] == 'Foundation'
    clear.assert_called_once_with('22')


def test_cycle_is_rejected_before_any_edge_write(monkeypatch):
    session, clear = edge_setup(monkeypatch, [{'source': 'a', 'target': 'b', 'status': 'CONFIRMED'},
                                            {'source': 'b', 'target': 'c', 'status': 'AUTO'}])
    response = client.post(BASE + '/edges?expected_version=8', headers=HEADERS,
                           json={'source_id': 'c', 'target_id': 'a'})
    assert response.status_code == 409
    assert response.json()['detail']['cycle']
    assert session.run.await_count == 3
    clear.assert_not_called()


@pytest.mark.parametrize('source,target,code', [('a', 'a', 422), ('a', 'foreign', 404)])
def test_self_loop_and_cross_course_nodes_rejected(monkeypatch, source, target, code):
    edge_setup(monkeypatch)
    assert client.post(BASE + '/edges?expected_version=8', headers=HEADERS,
        json={'source_id': source, 'target_id': target}).status_code == code


def test_stale_version_cannot_mutate(monkeypatch):
    session, clear = database(monkeypatch, [result({'version': 9})])
    response = client.post(BASE + '/edges?expected_version=8', headers=HEADERS,
                           json={'source_id': 'a', 'target_id': 'b'})
    assert response.status_code == 409
    assert session.run.await_count == 1
    clear.assert_not_called()


def test_delete_keeps_rejected_tombstone(monkeypatch):
    session, _ = edge_setup(monkeypatch, [{'source': 'a', 'target': 'b', 'status': 'AUTO'}],
                           tail=[result(), result({'graph_version': 9})])
    assert client.delete(BASE + '/edges/a/b?expected_version=8', headers=HEADERS).status_code == 200
    call = session.run.call_args_list[3]
    assert call.kwargs['status'] == 'REJECTED'
    assert 'DELETE' not in call.args[0]


def test_change_direction_rejects_old_pair_and_adds_new_atomically(monkeypatch):
    session, _ = edge_setup(monkeypatch, [{'source': 'a', 'target': 'b', 'status': 'AUTO'}],
                           tail=[result(), result(), result({'graph_version': 9})])
    response = client.put(BASE + '/edges/a/b?expected_version=8', headers=HEADERS,
                          json={'source_id': 'b', 'target_id': 'a', 'note': 'Correct direction'})
    assert response.status_code == 200
    assert "'REJECTED'" in session.run.call_args_list[3].args[0]
    assert session.run.call_args_list[4].kwargs['status'] == 'MANUAL'


def test_confirm_rejected_edge_checks_cycle(monkeypatch):
    edge_setup(monkeypatch, [{'source': 'a', 'target': 'b', 'status': 'CONFIRMED'},
                            {'source': 'b', 'target': 'a', 'status': 'REJECTED'}])
    response = client.post(BASE + '/edges/b/a/review?expected_version=8', headers=HEADERS,
                           json={'status': 'CONFIRMED'})
    assert response.status_code == 409


def test_review_snapshot_includes_rejected_edges(monkeypatch):
    database(monkeypatch, [result({'version': 8}), result(rows=[{'id': 'a'}]),
        result(rows=[{'source': 'a', 'target': 'b', 'status': 'REJECTED'}]), result({'version': 8})])
    response = client.get(BASE + '/review', headers=HEADERS)
    assert response.status_code == 200
    assert response.json()['edges'][0]['status'] == 'REJECTED'


def test_update_concept_keeps_identity_and_protects_manual_changes(monkeypatch):
    monkeypatch.setattr(api.embed_model, 'embed_batch', AsyncMock(return_value=[[0.1] * 1024]))
    session, _ = database(monkeypatch, [result({'version': 8}),
        result(rows=[{'id': 'a', 'key': 'old name', 'name': 'Old name', 'aliases': [], 'status': 'ACTIVE'}]),
        result(), result({'graph_version': 9})])
    response = client.put(BASE + '/concepts/a?expected_version=8', headers=HEADERS,
                          json={'name': 'New name', 'aliases': [], 'language': 'en'})
    assert response.status_code == 200
    write = session.run.call_args_list[2]
    assert 'n.curated = true' in write.args[0]
    assert 'SET n.key' not in write.args[0]
    assert write.kwargs['aliases'] == ['Old name']


def test_update_concept_rejects_duplicate_archived_name(monkeypatch):
    monkeypatch.setattr(api.embed_model, 'embed_batch', AsyncMock(return_value=[[0.1] * 1024]))
    session, _ = database(monkeypatch, [result({'version': 8}), result(rows=[
        {'id': 'a', 'key': 'a', 'name': 'A', 'aliases': [], 'status': 'ACTIVE'},
        {'id': 'b', 'key': 'b', 'name': 'B', 'aliases': [], 'status': 'ARCHIVED'}])])
    assert client.put(BASE + '/concepts/a?expected_version=8', headers=HEADERS,
        json={'name': 'B'}).status_code == 409
    assert session.run.await_count == 2


def test_confirm_concept_missing_or_archived(monkeypatch):
    database(monkeypatch, [result({'version': 8}), result()])
    assert client.post(BASE + '/concepts/a/review?expected_version=8', headers=HEADERS).status_code == 404


def test_auth_and_version_are_required():
    assert client.post(BASE + '/edges?expected_version=8', json={'source_id': 'a', 'target_id': 'b'}).status_code == 422
    assert client.get(BASE + '/review', headers={'X-Internal-Key': 'invalid'}).status_code == 403
    assert client.post(BASE + '/edges', headers=HEADERS, json={'source_id': 'a', 'target_id': 'b'}).status_code == 422


@pytest.mark.asyncio
async def test_ingestion_rechecks_cycles_after_concurrent_instructor_change(monkeypatch):
    from app.prerequisite.pipeline import save_prerequisite_edges
    # The LLM proposed b->a before the instructor confirmed a->b.
    # At persistence time the same course lock must protect a final cycle check.
    session, _ = database(monkeypatch, [result(), result(rows=[
        {'source': 'a', 'target': 'b', 'status': 'CONFIRMED', 'score': 0.9},
        {'source': 'b', 'target': 'a', 'status': 'AUTO', 'score': 0.7}]), result()])
    await save_prerequisite_edges('22', [], [], [])
    cleanup = session.run.call_args_list[2]
    assert cleanup.kwargs['source'] == 'b' and cleanup.kwargs['target'] == 'a'
    assert "status: 'AUTO'" in cleanup.args[0]


def test_review_rejects_mixed_version_snapshot(monkeypatch):
    database(monkeypatch, [result({'version': 8}), result(rows=[]), result(rows=[]), result({'version': 9})])
    response = client.get(BASE + '/review', headers=HEADERS)
    assert response.status_code == 409


def test_renamed_concept_matches_ingestion_by_current_display_name():
    from app.concept_extraction.merger import merge_concepts
    extracted = {'canonical': 'New name', 'aliases': [], 'language': 'en'}
    curated = {'id': 'a', 'key': 'old name', 'name': 'New name', 'aliases': ['Old name'],
               'language': 'en', 'has_reviewed_edge': True}
    new, _ = merge_concepts([extracted], [curated])
    assert new == []
    assert extracted['key'] == 'old name'
    assert curated['name'] == 'New name'
