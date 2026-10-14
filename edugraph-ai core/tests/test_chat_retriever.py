import pytest
from app.retrieval.chunk_retriever import compute_rrf, sanitize_fulltext_query

def test_compute_rrf_scoring_and_ranking():
    # vector results: [ch1, ch2, ch3]
    vec_results = [
        {"id": "ch1", "text": "chunk 1", "score": 0.9},
        {"id": "ch2", "text": "chunk 2", "score": 0.8},
        {"id": "ch3", "text": "chunk 3", "score": 0.7},
    ]
    # fulltext results: [ch2, ch4, ch1]
    ft_results = [
        {"id": "ch2", "text": "chunk 2", "score": 10.0},
        {"id": "ch4", "text": "chunk 4", "score": 8.0},
        {"id": "ch1", "text": "chunk 1", "score": 5.0},
    ]

    fused = compute_rrf(vec_results, ft_results, k_rrf=60)
    
    # ch2 có rank 2 ở vec (1/62) và rank 1 ở ft (1/61) -> tổng lớn nhất
    assert fused[0]["id"] == "ch2"
    # ch1 có rank 1 ở vec (1/61) và rank 3 ở ft (1/63) -> tổng lớn nhì
    assert fused[1]["id"] == "ch1"
    
    # Mọi item đều có rrf_score
    for item in fused:
        assert "rrf_score" in item
        assert item["rrf_score"] > 0

def test_sanitize_fulltext_query():
    raw_query = 'SQL: SELECT * FROM users WHERE age > 20 && name != "admin" (test/demo)'
    cleaned = sanitize_fulltext_query(raw_query)
    # Các ký tự : * & ! " ( ) / đã được bỏ
    for char in [':', '*', '&', '!', '"', '(', ')', '/']:
        assert char not in cleaned
    assert "SELECT" in cleaned
    assert "users" in cleaned
