import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.schemas.chat import GraphRelationship, PublicCourse
from app.services.course_discovery import matching_groups, select_courses, discover_courses, invitation
from app.services.chat import chat_explain_stream_generator


def concept(cid, course, name='Gradient', evidence=True, aliases=()):
    return dict(id=cid, course_id=course, name=name, has_evidence=evidence, aliases=list(aliases))


def test_empty_course_discovers_public_concept_and_prioritizes_already_joined_courses():
    concepts = [concept('a', '2'), concept('b', '3'), concept('secret', 'private')]
    catalog = {'2': PublicCourse(name='Calculus'), '3': PublicCourse(name='Machine learning', enrolled=True)}
    groups = matching_groups(concepts, 'Gradient là gì?', scope=['1'])
    rows, missing = select_courses(concepts, [], groups, ['1'], catalog, 'Gradient là gì?')
    assert missing
    assert [row['courseId'] for row in rows] == ['3', '2']
    assert rows[0]['conceptIds'] == ['b']
    assert 'Bạn có muốn chuyển qua môn Machine learning' in invitation(rows)
    assert 'Bạn có muốn học môn này không?' in invitation(rows)
    assert 'private' not in json.dumps(rows)


def test_aliases_find_the_same_concept_across_different_course_node_ids():
    concepts = [concept('old', '1', 'Đạo hàm', evidence=False),
                concept('new', '2', 'Derivative', aliases=['Đạo hàm'])]
    groups = matching_groups(concepts, 'Explain this concept', ['old'], scope=['1'])
    rows, _ = select_courses(concepts, [], groups, ['1'], {'2': PublicCourse(name='Math')}, 'Explain this concept')
    assert rows[0]['conceptIds'] == ['new']


def test_concept_with_current_course_evidence_keeps_the_current_scope():
    concepts = [concept('a', '1'), concept('b', '2')]
    groups = matching_groups(concepts, 'Gradient', scope=['1'])
    assert select_courses(concepts, [], groups, ['1'], {'2': PublicCourse(name='Other')}, 'Gradient') == ([], False)


def test_directed_relationship_requires_the_same_edge_not_just_two_concept_names():
    concepts = [concept('old-a', '1', 'Key'), concept('old-b', '1', 'Join'),
                concept('new-a', '2', 'Key'), concept('new-b', '2', 'Join')]
    edge = GraphRelationship(source_id='old-a', target_id='old-b')
    groups = matching_groups(concepts, 'Explain the relationship', relationship=edge, scope=['1'])
    catalog = {'2': PublicCourse(name='SQL', enrolled=True)}
    assert select_courses(concepts, [], groups, ['1'], catalog, 'Explain the relationship', edge) == ([], True)
    reversed_edge = [dict(source='new-b', target='new-a', course_id='2')]
    assert select_courses(concepts, reversed_edge, groups, ['1'], catalog, 'Explain the relationship', edge) == ([], True)
    rows, _ = select_courses(concepts, [dict(source='new-a', target='new-b', course_id='2')], groups,
                             ['1'], catalog, 'Explain the relationship', edge)
    assert rows[0]['graphRelationship'] == {'sourceId': 'new-a', 'targetId': 'new-b'}


def test_typed_arrow_preserves_direction_and_a_matching_local_edge_prevents_switching():
    concepts = [concept('a', '2', 'Key'), concept('b', '2', 'Join')]
    groups = matching_groups(concepts, 'Join -> Key', scope=['1'])
    catalog = {'2': PublicCourse(name='SQL')}
    edge = [dict(source='a', target='b', course_id='2')]
    assert select_courses(concepts, edge, groups, ['1'], catalog, 'Join -> Key') == ([], True)
    groups = matching_groups(concepts, 'Key -> Join', scope=['2'])
    assert select_courses(concepts, edge, groups, ['2'], catalog, 'Key -> Join') == ([], False)


@pytest.mark.parametrize('arrow', ['->', '→'])
@pytest.mark.parametrize('reverse_rows', [False, True])
@pytest.mark.parametrize('reverse_direction', [False, True])
@pytest.mark.parametrize('use_alias', [False, True])
def test_arrow_endpoints_use_non_overlapping_name_matches(arrow, reverse_rows, reverse_direction, use_alias):
    concepts = [concept('key', '2', 'Key'),
                concept('value', '2', 'Map entry' if use_alias else 'Key value',
                        aliases=['Key value'] if use_alias else [])]
    if reverse_rows:
        concepts.reverse()
    left, right = ('Key', 'Key value') if reverse_direction else ('Key value', 'Key')
    source_id, target_id = ('key', 'value') if reverse_direction else ('value', 'key')
    query = f'{left} {arrow} {right}'
    groups = matching_groups(concepts, query, scope=['1'])
    assert left.lower() in groups[0]
    assert right.lower() in groups[1]
    catalog = {'2': PublicCourse(name='Data structures')}
    edge = dict(source=source_id, target=target_id, course_id='2')
    rows, missing = select_courses(concepts, [edge], groups, ['1'], catalog, query)
    assert missing
    assert rows[0]['graphRelationship'] == {'sourceId': source_id, 'targetId': target_id}
    reversed_edge = dict(source=target_id, target=source_id, course_id='2')
    assert select_courses(concepts, [reversed_edge], groups, ['1'], catalog, query) == ([], True)


def test_unrelated_questions_and_courses_without_source_data_are_not_recommended():
    concepts = [concept('a', '2', evidence=False)]
    assert matching_groups(concepts, 'Tell me about networking', scope=['1']) == []
    groups = matching_groups(concepts, 'Gradient', scope=['1'])
    assert select_courses(concepts, [], groups, ['1'], {'2': PublicCourse(name='Empty')}, 'Gradient') == ([], True)


def test_long_concept_names_and_resolved_natural_language_relationships():
    from app.chat.intent import RelationshipIntent
    concepts = [concept('a', '2', 'Long short term memory neural network'), concept('b', '2', 'Backpropagation')]
    groups = matching_groups(concepts, 'Explain Long short term memory neural network', scope=['1'])
    rows, _ = select_courses(concepts, [], groups, ['1'], {'2': PublicCourse(name='Neural networks')}, 'Explain this')
    assert rows[0]['conceptIds'] == ['a']
    relation = RelationshipIntent(source='Backpropagation', target='Long short term memory neural network')
    groups = matching_groups(concepts, 'Why does this network require Backpropagation?', scope=['1'], relationship_names=relation)
    rows, _ = select_courses(concepts, [dict(source='b', target='a', course_id='2')], groups,
                             ['1'], {'2': PublicCourse(name='Neural networks')}, 'Why?', relation)
    assert rows[0]['graphRelationship'] == {'sourceId': 'b', 'targetId': 'a'}


@pytest.mark.asyncio
async def test_database_lookup_is_scoped_to_current_and_currently_public_courses_and_rejects_rejected_edges(monkeypatch):
    from app.db import graph_repository
    result = MagicMock()
    result.data = AsyncMock(side_effect=[[concept('a', '2')], []])
    session = MagicMock()
    session.__aenter__ = AsyncMock(return_value=session)
    session.__aexit__ = AsyncMock(return_value=None)
    session.run = AsyncMock(return_value=result)
    monkeypatch.setattr(graph_repository.neo4j_client, 'driver', MagicMock(session=MagicMock(return_value=session)))
    rows, _ = await discover_courses(['1'], {'2': PublicCourse(name='Math')}, 'Gradient')
    assert rows[0]['courseId'] == '2'
    assert all(call.kwargs['courses'] == ['1', '2'] for call in session.run.call_args_list)
    assert "coalesce(c.status, 'ACTIVE') = 'ACTIVE'" in session.run.call_args_list[0].args[0]
    assert "ch.course_id = c.course_id" in session.run.call_args_list[0].args[0]
    assert "<> 'REJECTED'" in session.run.call_args_list[1].args[0]
    assert 'ch.text AS' not in session.run.call_args_list[0].args[0]


@pytest.mark.asyncio
async def test_discovery_invitation_does_not_answer_from_other_courses_or_call_the_llm():
    recommendation = {'courseId': '2', 'courseName': 'Math', 'enrolled': False, 'subject': 'Gradient',
                      'conceptIds': ['other'], 'question': 'Gradient?', 'graphRelationship': None}
    with patch('app.services.chat.classify_chat_intent', AsyncMock(side_effect=ValueError())), \
         patch('app.services.chat.discover_courses', AsyncMock(return_value=([recommendation], True))), \
         patch('app.services.chat.link_concepts', AsyncMock(return_value={'concept_ids': []})), \
         patch('app.services.chat.retrieve_chunks', AsyncMock(return_value=[])) as retrieve, \
         patch('app.services.chat.build_chat_path', AsyncMock()) as path, \
         patch('app.services.chat.llm_client.generate_stream') as llm:
        frames = [frame async for frame in chat_explain_stream_generator('1', 'Gradient?',
            authorized_course_ids=['1'], public_courses={'2': PublicCourse(name='Math')})]
    assert any(frame.startswith('event: course_suggestions') for frame in frames)
    assert 'Bạn có muốn học môn này không?' in ''.join(frames)
    assert 'event: sources\ndata: []' in ''.join(frames)
    retrieve.assert_awaited_once()
    assert retrieve.call_args.kwargs['authorized_course_ids'] == ['1']
    path.assert_not_awaited(); llm.assert_not_called()


@pytest.mark.parametrize('groups', [[], [{'key'}]])
def test_incomplete_edge_endpoints_are_unavailable_without_indexing_missing_groups(groups):
    relation = GraphRelationship(source_id='missing', target_id='also-missing')
    assert select_courses([concept('a', '2', 'Key')], [], groups, ['1'],
                          {'2': PublicCourse(name='SQL')}, 'Key -> Key', relation) == ([], True)


@pytest.mark.parametrize('arrow', ['->', '→'])
@pytest.mark.parametrize('groups', [[], [{'key'}], [{'join'}], [{'key'}, {'join'}, {'table'}]])
def test_explicit_arrow_never_recommends_concepts_when_endpoint_groups_are_incomplete(arrow, groups):
    concepts = [concept('key', '2', 'Key'), concept('join', '2', 'Join')]
    assert select_courses(concepts, [], groups, ['1'], {'2': PublicCourse(name='SQL')},
                          f'Key {arrow} Join') == ([], True)


@pytest.mark.parametrize('arrow', ['->', '→'])
@pytest.mark.parametrize('matched_name', ['Key', 'Join'])
def test_partial_arrow_match_does_not_fall_back_to_single_concept_suggestion(arrow, matched_name):
    concepts = [concept('matched', '2', matched_name)]
    catalog = {'2': PublicCourse(name='SQL')}
    query = f'Key {arrow} Join'
    groups = matching_groups(concepts, query, scope=['1'])
    assert len(groups) == 1
    assert select_courses(concepts, [], groups, ['1'], catalog, query) == ([], True)
    ordinary_query = f'Explain {matched_name}'
    groups = matching_groups(concepts, ordinary_query, scope=['1'])
    rows, missing = select_courses(concepts, [], groups, ['1'], catalog, ordinary_query)
    assert missing
    assert rows[0]['conceptIds'] == ['matched']
    assert rows[0]['graphRelationship'] is None


def test_edge_evidence_must_belong_to_its_actual_endpoints_and_later_valid_edges_are_considered():
    concepts = [concept('key-with-proof', '2', 'Key'), concept('key-without-proof', '2', 'Key', evidence=False),
                concept('join-without-proof', '2', 'Join', evidence=False)]
    groups = [{'key'}, {'join'}]
    edges = [dict(source='key-without-proof', target='join-without-proof', course_id='2')]
    catalog = {'2': PublicCourse(name='SQL')}
    assert select_courses(concepts, edges, groups, ['1'], catalog, 'Key -> Join') == ([], True)
    edges.append(dict(source='key-with-proof', target='join-without-proof', course_id='2'))
    rows, missing = select_courses(concepts, edges, groups, ['1'], catalog, 'Key -> Join')
    assert missing
    assert rows[0]['conceptIds'] == ['key-with-proof', 'join-without-proof']
