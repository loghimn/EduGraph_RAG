import json
from unittest.mock import AsyncMock, patch

import networkx as nx
import pytest

from app.retrieval.prerequisite_context import missing_prerequisites, merge_prerequisite_chunks
from app.schemas.chat import CourseAiSettings
from app.services.chat import chat_explain_stream_generator


def test_mastery_stops_traversal_without_assuming_unrelated_ancestors_are_known():
    graph = nx.DiGraph([('basic', 'known'), ('known', 'goal'), ('other', 'goal'), ('deeper', 'other')])
    missing, mastered = missing_prerequisites(graph, ['goal'], ['known'], 3)
    assert [item['id'] for item in missing] == ['other', 'deeper']
    assert [item['id'] for item in mastered] == ['known']
    assert missing_prerequisites(graph, ['goal'], [], 0) == ([], [])
    assert [item['id'] for item in missing_prerequisites(graph, ['goal'], [], 1)[0]] == ['known', 'other']


def test_traversal_bounds_cycles_and_total_concepts():
    graph = nx.DiGraph([('a', 'b'), ('b', 'a'), ('b', 'goal')])
    assert len(missing_prerequisites(graph, ['goal'], [], 6)[0]) == 2
    assert len(missing_prerequisites(graph, ['goal'], [], 6, limit=1)[0]) == 1


def test_evidence_budget_keeps_primary_answer_and_deduplicates_prerequisites():
    base = [{'id': str(i)} for i in range(6)]
    prereqs = [{'id': str(i), 'prerequisite_evidence': True} for i in range(2, 8)]
    result = merge_prerequisite_chunks(base, prereqs, 6)
    assert len(result) == len({row['id'] for row in result}) == 6
    assert result[0]['id'] == '0'
    assert any(row.get('prerequisite_evidence') for row in result)


@pytest.mark.asyncio
async def test_explanation_receives_mastery_config_and_cites_prerequisite_sources():
    evidence = [{'id': 'primary', 'text': 'Goal evidence'},
                {'id': 'prior', 'text': 'Prerequisite evidence', 'prerequisite_evidence': True}]
    context = [{'course_id': '7', 'missing': [{'id': 'prior', 'name': 'Basic'}], 'mastered': []}]
    config = {'7': CourseAiSettings(traversalDepth=2, promptTemplate='Use simple examples')}
    async def generate(prompt, **kwargs):
        assert 'Basic' in prompt and 'Use simple examples' in prompt
        yield 'First learn Basic [2], then Goal [1].'
    with patch('app.services.chat.classify_chat_intent', AsyncMock(side_effect=ValueError())), \
         patch('app.services.chat.retrieve_chunks', AsyncMock(return_value=evidence[:1])) as chunks, \
         patch('app.services.chat.retrieve_prerequisite_context', AsyncMock(return_value=(context, evidence[1:], []))) as retrieve, \
         patch('app.services.chat.llm_client.generate_stream', generate):
        frames = [frame async for frame in chat_explain_stream_generator(
            '7', 'Explain Goal', ['goal'], ['7'], known_concepts_by_course={'7': ['known']},
            course_settings_by_course=config)]
    retrieve.assert_awaited_once_with(['goal'], ['7'], {'7': ['known']}, config)
    assert chunks.call_args.kwargs['course_settings'] == config
    sources = next(json.loads(frame.splitlines()[1][6:]) for frame in frames if frame.startswith('event: sources'))
    assert sources[1]['citation_type'] == 'PREREQUISITE_SURFACED'


@pytest.mark.asyncio
async def test_database_traversal_uses_only_authorized_targets_and_same_course_passages(monkeypatch):
    from unittest.mock import MagicMock
    from app.retrieval import prerequisite_context as module
    result = MagicMock()
    result.data = AsyncMock(side_effect=[
        [{'id': 'goal', 'name': 'Goal', 'course_id': '7'}],
        [{'id': 'proof', 'text': 'Basic evidence', 'concept_id': 'basic', 'course_id': '7'}],
    ])
    session = MagicMock()
    session.__aenter__ = AsyncMock(return_value=session)
    session.__aexit__ = AsyncMock(return_value=None)
    session.run = AsyncMock(return_value=result)
    monkeypatch.setattr(module.graph_repository.neo4j_client, 'driver', MagicMock(session=MagicMock(return_value=session)))
    graph = nx.DiGraph([('basic', 'goal')])
    load = AsyncMock(return_value=(graph, 1, {}, []))
    monkeypatch.setattr(module, 'get_course_graph', load)
    context, chunks, targets = await module.retrieve_prerequisite_context(['goal', 'unauthorized'], ['7'])
    assert session.run.call_args_list[0].kwargs['courses'] == ['7']
    assert 'c.course_id IN $courses' in session.run.call_args_list[0].args[0]
    assert 'ch:Chunk {course_id: $course}' in session.run.call_args_list[1].args[0]
    assert session.run.call_args_list[1].kwargs['course'] == '7'
    load.assert_awaited_once_with('7')
    assert chunks[0]['prerequisite_evidence'] is True
    assert context[0]['missing'][0]['id'] == 'basic'
    assert [row['id'] for row in targets] == ['goal']


@pytest.mark.asyncio
async def test_empty_authorized_scope_never_loads_graph_or_queries_database():
    with patch('app.retrieval.prerequisite_context.graph_repository.session') as session:
        from app.retrieval.prerequisite_context import retrieve_prerequisite_context
        assert await retrieve_prerequisite_context(['private'], []) == ([], [], [])
        session.assert_not_called()
