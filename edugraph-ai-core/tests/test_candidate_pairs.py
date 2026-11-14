from app.config import settings
from app.prerequisite.candidate_pairs import candidate_pairs_from_data


def test_candidates_prioritize_cooccurrence_without_reopening_reviewed_relationships(monkeypatch):
    monkeypatch.setattr(settings, "PREREQ_CANDIDATES", 2)
    concepts = [{"id": cid, "name": cid, "embedding": vector} for cid, vector in [
        ("a", [1., 0.]), ("b", [.99, .01]), ("c", [.98, .02]), ("d", [0., 1.]), ("e", [.9, .1])]]
    candidates = candidate_pairs_from_data(concepts, {"a": ["missing", "a", "d", "d", "b", "c"]},
                                          {"a": {"b": "CONFIRMED", "c": "REJECTED", "e": "AUTO"}})
    assert [c["id"] for c in candidates["a"]] == ["d", "e"]


def test_missing_embeddings_and_empty_course_do_not_produce_candidates():
    assert candidate_pairs_from_data([], {}, {}) == {}
    assert candidate_pairs_from_data([{"id": "a", "embedding": None}], {}, {}) == {"a": []}
