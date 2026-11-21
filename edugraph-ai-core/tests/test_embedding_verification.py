import math
import pytest
from app.config import settings
from app.prerequisite.candidate_pairs import compute_cosine_similarity, candidate_pairs_from_data
from app.prerequisite.fusion import fuse_scores


@pytest.mark.parametrize("a,b,expected", [([2,0],[5,0],1), ([1,0],[0,3],0), ([1,0],[-1,0],-1),
    (None,[1],None), ([0,0],[1,0],None), ([1],[1,0],None), ([math.nan],[1],None)])
def test_embedding_validation_normalizes_vectors(a,b,expected):
    assert compute_cosine_similarity(a,b) == expected


def test_embedding_gate_rejects_unrelated_or_missing_vectors_and_preserves_direction():
    llm = [{"id": "related", "confidence": .8}, {"id": "unrelated", "confidence": .99},
           {"id": "missing", "confidence": .99}]
    result = fuse_scores("target", llm, {}, {"related": .9, "unrelated": 0, "missing": None})
    assert len(result) == 1
    assert result[0]["source_id"] == "related"
    assert result[0]["target_id"] == "target"
    assert result[0]["embedding_score"] == .9
    assert result[0]["source"] == "HYBRID"
    assert fuse_scores("target", [], {}, {"related": 1}) == []


def test_embedding_signal_changes_final_acceptance_not_just_candidates(monkeypatch):
    monkeypatch.setattr(settings, 'EDGE_MIN_SCORE', .55)
    llm = [{"id": "candidate", "confidence": .6}]
    assert not fuse_scores("target", llm, {}, {"candidate": .2})
    assert fuse_scores("target", llm, {}, {"candidate": 1})
    concepts = [{"id":"a","embedding":[2.,0.]},{"id":"b","embedding":[3.,0.]}]
    rows = candidate_pairs_from_data(concepts, {}, {})
    assert rows['a'][0]['embedding_score'] == 1
    assert 'embedding_score' not in concepts[0]
