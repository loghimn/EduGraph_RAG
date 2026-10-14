import pytest
from app.retrieval.concept_linker import link_concepts_from_data, extract_ngrams

def test_extract_ngrams_longest_first():
    tokens = ["gradient", "descent", "algorithm"]
    ngrams = extract_ngrams(tokens, max_n=3)
    # Longest first: length 3, then 2, then 1
    lengths = [len(ng[0].split()) for ng in ngrams]
    assert lengths[0] == 3
    assert lengths[-1] == 1
    assert ngrams[0][0] == "gradient descent algorithm"

def test_link_concepts_exact_and_unaccented():
    concepts = [
        {"id": "c1", "name": "Đại số tuyến tính", "aliases": ["Linear Algebra"]},
        {"id": "c2", "name": "Giải tích", "aliases": ["Calculus"]},
    ]
    
    # Exact có dấu
    res = link_concepts_from_data("Tôi muốn học Đại số tuyến tính", concepts)
    assert res["concept_ids"] == ["c1"]
    assert res["clarify"] is None

    # Khớp không dấu (unaccented)
    res_unaccent = link_concepts_from_data("Tôi muốn học dai so tuyen tinh", concepts)
    assert res_unaccent["concept_ids"] == ["c1"]
    assert res_unaccent["clarify"] is None

def test_link_concepts_unaccented_ambiguity_clarify():
    # 'ban' khớp cả 'bàn' và 'bản' -> SPEC yêu cầu gửi clarify, không tự chọn
    concepts = [
        {"id": "c_ban1", "name": "Bàn", "aliases": ["Table"]},
        {"id": "c_ban2", "name": "Bản", "aliases": ["Record"]},
    ]
    
    # Message dùng từ không dấu trùng cả hai
    res = link_concepts_from_data("Tìm kiếm thông tin ve ban", concepts)
    assert res["concept_ids"] == []
    assert res["clarify"] is not None
    clarify_ids = {c["id"] for c in res["clarify"]}
    assert clarify_ids == {"c_ban1", "c_ban2"}

def test_link_concepts_longest_match_first():
    concepts = [
        {"id": "c_grad", "name": "Gradient", "aliases": []},
        {"id": "c_descent", "name": "Gradient Descent", "aliases": []},
    ]
    
    res = link_concepts_from_data("Giải thích thuật toán Gradient Descent trong ML", concepts)
    # Phải match Gradient Descent (dài hơn), không match đơn lẻ Gradient
    assert res["concept_ids"] == ["c_descent"]

def test_link_concepts_alias_short_no_fuzzy():
    concepts = [
        {"id": "c_sql", "name": "Structured Query Language", "aliases": ["SQL"]},
    ]
    # 'SQL' có độ dài 3 < 4 (ALIAS_FUZZY_MIN_LEN), chỉ exact match
    res_exact = link_concepts_from_data("Học SQL cơ bản", concepts)
    assert res_exact["concept_ids"] == ["c_sql"]

    # Fuzzy lệch 'SQX' không được khớp vì alias quá ngắn
    res_fuzzy = link_concepts_from_data("Học SQX cơ bản", concepts)
    assert "c_sql" not in res_fuzzy["concept_ids"]

def test_link_concepts_fallback_cosine_top3():
    # Giả lập không match từ nào -> tính cosine embedding top-3
    concepts = [
        {"id": "c1", "name": "Database Index", "aliases": [], "embedding": [1.0, 0.0, 0.0]},
        {"id": "c2", "name": "Query Optimization", "aliases": [], "embedding": [0.8, 0.2, 0.0]},
        {"id": "c3", "name": "Transaction ACID", "aliases": [], "embedding": [0.0, 1.0, 0.0]},
        {"id": "c4", "name": "CSS Styling", "aliases": [], "embedding": [0.0, 0.0, 1.0]},
    ]
    
    msg_emb = [0.9, 0.1, 0.0] # Gần c1 và c2 nhất
    res = link_concepts_from_data("Một câu hỏi hoàn toàn lạ lẫm", concepts, message_embedding=msg_emb)
    assert res["concept_ids"] == []
    assert res["clarify"] is not None
    assert len(res["clarify"]) == 3
    top_ids = [c["id"] for c in res["clarify"]]
    assert top_ids[0] == "c1"
    assert top_ids[1] == "c2"
