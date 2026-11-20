import networkx as nx
import pytest
from pydantic import ValidationError

from app.retrieval.path_engine import generate_learning_path
from app.schemas.concept_creation import DraftRequest


def test_difficulty_prioritizes_ready_concepts_without_breaking_prerequisites():
    graph = nx.DiGraph([('hard-root', 'easy-goal'), ('easy-root', 'easy-goal')])
    graph.nodes['hard-root'].update(difficulty=5, order=1)
    graph.nodes['easy-root'].update(difficulty=1, order=100)
    graph.nodes['easy-goal'].update(difficulty=1, order=0)
    result = generate_learning_path(graph, {}, mode='goal', goal_concept_ids=['easy-goal'])
    assert result['linear_order'] == ['easy-root', 'hard-root', 'easy-goal']
    assert [row['concept_id'] for row in result['stages'][0]['steps']] == ['easy-root', 'hard-root']
    assert result['validation']['violations'] == 0
    assert result['stages'][0]['steps'][0]['difficulty'] == 1


def test_unrated_concepts_use_neutral_difficulty_and_stable_original_order():
    graph = nx.DiGraph()
    graph.add_node('b', order=2)
    graph.add_node('a', order=1)
    result = generate_learning_path(graph, {})
    assert result['linear_order'] == ['a', 'b']
    assert result['stages'][0]['steps'][0]['difficulty'] == 3
    assert result['stages'][0]['steps'][0]['difficulty_rated'] is False


@pytest.mark.parametrize('value', [0, 6, -1])
def test_difficulty_rating_must_be_between_one_and_five(value):
    with pytest.raises(ValidationError):
        DraftRequest(name='Concept', difficulty=value)
