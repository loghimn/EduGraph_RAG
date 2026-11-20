import json
from unittest.mock import AsyncMock, patch

import pytest

from app.chat.answer import build_explain_prompt
from app.chat.intent import ChatIntent, RelationshipIntent
from app.retrieval.named_relationship import resolve_relationship_names, resolve_named_relationship
from app.schemas.chat import GraphRelationship, PublicCourse
from app.services.chat import chat_explain_stream_generator


def events(frames):
    return {frame.splitlines()[0][7:]: json.loads(frame.splitlines()[1][6:])
            for frame in frames if frame.startswith('event:')}


def source(cid='proof', text='SELECT ... WHERE filters matching rows.'):
    return dict(id=cid, text=text, doc_id='1', page=1, index=0)


@pytest.mark.asyncio
@pytest.mark.parametrize('missing', [False, True])
async def test_unannotated_in_scope_passages_take_priority_over_public_course_discovery(missing):
    async def generate(*args, **kwargs):
        yield 'WHERE filters rows [1].'
    with patch('app.services.chat.classify_chat_intent', AsyncMock(side_effect=ValueError())), \
         patch('app.services.chat.link_concepts', AsyncMock(return_value={'concept_ids': []})), \
         patch('app.services.chat.retrieve_chunks', AsyncMock(return_value=[source()])) as retrieve, \
         patch('app.services.chat.discover_courses', AsyncMock(return_value=([], missing))) as discover, \
         patch('app.services.chat.llm_client.generate_stream', generate):
        result = events([frame async for frame in chat_explain_stream_generator('1', 'Explain SELECT WHERE',
            authorized_course_ids=['1'], public_courses={'2': PublicCourse(name='Other SQL course')})])
    retrieve.assert_awaited_once()
    assert retrieve.call_args.kwargs['authorized_course_ids'] == ['1']
    discover.assert_not_awaited()
    assert result['sources'][0]['chunk_id'] == 'proof'
    assert 'course_suggestions' not in result
    assert result['answer']['content'] == 'WHERE filters rows [1].'


@pytest.mark.asyncio
@pytest.mark.parametrize('has_recommendation', [False, True])
async def test_discovery_runs_only_after_in_scope_retrieval_returns_no_evidence(has_recommendation):
    calls = []
    recommendation = dict(courseId='2', courseName='SQL', enrolled=False, subject='SELECT', conceptIds=['select2'],
                          question='SELECT?', graphRelationship=None)
    async def retrieve(**kwargs):
        calls.append('retrieve'); assert kwargs['authorized_course_ids'] == ['1']; return []
    async def discover(*args):
        calls.append('discover'); return ([recommendation] if has_recommendation else []), True
    with patch('app.services.chat.classify_chat_intent', AsyncMock(side_effect=ValueError())), \
         patch('app.services.chat.link_concepts', AsyncMock(return_value={'concept_ids': []})), \
         patch('app.services.chat.retrieve_chunks', retrieve), patch('app.services.chat.discover_courses', discover), \
         patch('app.services.chat.llm_client.generate_stream') as llm:
        result = events([frame async for frame in chat_explain_stream_generator('1', 'SELECT?',
            authorized_course_ids=['1'], public_courses={'2': PublicCourse(name='SQL')})])
    assert calls == ['retrieve', 'discover']
    assert ('course_suggestions' in result) is has_recommendation
    assert result['sources'] == []
    llm.assert_not_called()


@pytest.mark.asyncio
@pytest.mark.parametrize('public', [False, True])
@pytest.mark.parametrize('recorded', [False, True])
@pytest.mark.parametrize('intent', ['explain', 'prerequisites'])
async def test_classified_edge_names_resolve_to_scoped_ids_and_exact_direction_reaches_prompt(public, recorded, intent):
    question = 'Why does Gradient descent require Linear algebra?'
    names = RelationshipIntent(source='Linear algebra', target='Gradient descent')
    classification = ChatIntent(intent=intent, target=None, query=question, confidence=.99, relationship=names)
    concepts = [dict(id='linear1', name='Linear algebra', aliases=[], course_id='1'),
                dict(id='gradient1', name='Gradient descent', aliases=[], course_id='1')]
    context = {'recorded': recorded}
    async def generate(prompt, **kwargs):
        assert f'"recorded": {str(recorded).lower()}' in prompt
        yield 'This is the verified graph result [1].'
    with patch('app.services.chat.classify_chat_intent', AsyncMock(return_value=classification)), \
         patch('app.retrieval.named_relationship.get_course_concepts_for_linking', AsyncMock(return_value=concepts)) as load, \
         patch('app.services.chat.retrieve_chunks', AsyncMock(return_value=[source()])) as chunks, \
         patch('app.services.chat.retrieve_relationship', AsyncMock(return_value=(context, []))) as retrieve, \
         patch('app.services.chat.build_explain_prompt', wraps=build_explain_prompt) as prompt, \
         patch('app.services.chat.retrieve_prerequisite_context', AsyncMock()) as prerequisites, \
         patch('app.services.chat.discover_courses', AsyncMock(return_value=([], False))) as discover, \
         patch('app.services.chat.build_chat_path', AsyncMock()) as path, \
         patch('app.services.chat.llm_client.generate_stream', generate):
        frames = [frame async for frame in chat_explain_stream_generator('1', question, authorized_course_ids=['1'],
            public_courses={'2': PublicCourse(name='Other')} if public else {})]
    load.assert_awaited_once_with(['1'])
    retrieve.assert_awaited_once_with(GraphRelationship(source_id='linear1', target_id='gradient1'), ['1'])
    assert chunks.call_args.kwargs['concept_ids'] == ['linear1', 'gradient1']
    assert prompt.call_args.kwargs['relationship_context'] == context
    prerequisites.assert_not_awaited(); discover.assert_not_awaited(); path.assert_not_awaited()
    assert 'error' not in events(frames)


def test_name_resolution_uses_aliases_but_never_pairs_concepts_from_different_or_unauthorized_courses():
    names = RelationshipIntent(source='Dai so tuyen tinh', target='Gradient descent')
    rows = [dict(id='a1', course_id='1', name='Linear algebra', aliases=['Đại số tuyến tính']),
            dict(id='b2', course_id='2', name='Gradient descent', aliases=[])]
    relationship, context, ids = resolve_relationship_names(names, rows, ['1'])
    assert relationship is None and context['recorded'] is False and ids == ['a1']
    assert resolve_relationship_names(names, rows, ['1', '2'])[0] is None
    rows.append(dict(id='b1', course_id='1', name='Gradient descent', aliases=[]))
    relationship, context, ids = resolve_relationship_names(names, rows, ['1'])
    assert relationship == GraphRelationship(source_id='a1', target_id='b1')
    assert context is None and ids == ['a1', 'b1']
    rows.append(dict(id='b1-other', course_id='1', name='Gradient descent', aliases=[]))
    assert resolve_relationship_names(names, rows, ['1'])[1]['resolution'] == 'ambiguous'


@pytest.mark.asyncio
async def test_name_resolution_without_access_does_not_query_the_graph():
    with patch('app.retrieval.named_relationship.get_course_concepts_for_linking', AsyncMock()) as load:
        relationship, context, ids = await resolve_named_relationship(RelationshipIntent(source='A', target='B'), [])
    assert relationship is None and context['recorded'] is False and ids == []
    load.assert_not_awaited()


@pytest.mark.asyncio
async def test_exact_edge_evidence_prevents_public_fallback_even_without_ordinary_search_results():
    relationship = GraphRelationship(source_id='a', target_id='b')
    async def generate(*args, **kwargs):
        yield 'The edge is supported [1].'
    with patch('app.services.chat.retrieve_chunks', AsyncMock(return_value=[])), \
         patch('app.services.chat.retrieve_relationship', AsyncMock(return_value=({'recorded': True}, [source('edge')]))), \
         patch('app.services.chat.discover_courses', AsyncMock()) as discover, \
         patch('app.services.chat.llm_client.generate_stream', generate):
        result = events([frame async for frame in chat_explain_stream_generator('1', 'A -> B', ['a', 'b'], ['1'],
            graph_relationship=relationship, public_courses={'2': PublicCourse(name='Other')})])
    discover.assert_not_awaited()
    assert result['sources'][0]['chunk_id'] == 'edge'
