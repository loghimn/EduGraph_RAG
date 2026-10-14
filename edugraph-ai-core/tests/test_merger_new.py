import pytest
from app.concept_extraction.merger import merge_concepts

def test_merger_rules():
    db_concepts = [
        {"id": "c1", "key": "soft deletion", "canonical": "soft deletion", "language": "en", "aliases": []},
        {"id": "c2", "key": "view", "canonical": "view", "language": "en", "aliases": []},
        {"id": "c3", "key": "order by clause", "canonical": "ORDER BY clause", "language": "en", "aliases": []},
        {"id": "c4", "key": "gradient", "canonical": "gradient", "language": "en", "aliases": []},
        {"id": "c5", "key": "bàn", "canonical": "bàn", "language": "vi", "aliases": []}
    ]
    
    new_concepts = [
        # Match by alias of db concept or alias of new concept
        {"canonical": "Soft Deletions", "language": "en", "aliases": ["soft deletion"]},
        # English plural rule
        {"canonical": "views", "language": "en", "aliases": []},
        # Generic head nouns
        {"canonical": "ORDER BY", "language": "en", "aliases": []},
        # Should not merge (subset)
        {"canonical": "gradient descent", "language": "en", "aliases": []},
        # Should not merge (different letters)
        {"canonical": "bản", "language": "vi", "aliases": []}
    ]
    
    merged, cands = merge_concepts(new_concepts, db_concepts)
    
    # Soft Deletions -> merges with c1 because of alias
    assert new_concepts[0].get("merge_target_id") == "c1"
    # views -> merges with c2 because of plural
    assert new_concepts[1].get("merge_target_id") == "c2"
    # ORDER BY -> merges with c3 because of generic head noun
    assert new_concepts[2].get("merge_target_id") == "c3"
    
    # gradient descent -> does NOT merge
    assert "merge_target_id" not in new_concepts[3]
    # bản -> does NOT merge
    assert "merge_target_id" not in new_concepts[4]
