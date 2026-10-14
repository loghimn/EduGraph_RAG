import pytest
from app.chat.answer import build_explain_prompt, filter_citations, build_sources_payload

def test_build_explain_prompt():
    chunks = [
        {"id": "c1", "text": "SQL là ngôn ngữ truy vấn."},
        {"id": "c2", "text": "Index giúp tăng tốc truy vấn."},
    ]
    prompt = build_explain_prompt("Index là gì?", chunks)
    assert "[1] SQL là ngôn ngữ truy vấn." in prompt
    assert "[2] Index giúp tăng tốc truy vấn." in prompt
    assert "Index là gì?" in prompt

def test_filter_citations_strips_out_of_bounds():
    # Giả sử chỉ có 3 chunks, LLM trả về cả [1], [2], [4], [99], [0]
    raw_answer = "Theo tài liệu [1] và [2], Index rất hữu ích. Ngoài ra [4] và [99] cũng đề cập."
    
    cleaned, cited = filter_citations(raw_answer, max_chunk_index=3)
    
    # [1] và [2] được giữ lại
    assert "[1]" in cleaned
    assert "[2]" in cleaned
    # [4] và [99] bị loại bỏ khỏi văn bản
    assert "[4]" not in cleaned
    assert "[99]" not in cleaned
    
    assert cited == [1, 2]

def test_build_sources_payload():
    chunks = [
        {"id": "ch1", "doc_id": "d1", "page": 1, "index": 0, "text": "Text chunk 1"},
        {"id": "ch2", "doc_id": "d1", "page": 2, "index": 1, "text": "Text chunk 2"},
        {"id": "ch3", "doc_id": "d2", "page": 1, "index": 0, "text": "Text chunk 3"},
    ]
    
    # Chỉ trích dẫn 2 và 3
    sources = build_sources_payload([2, 3], chunks)
    assert len(sources) == 2
    assert sources[0]["citation"] == 2
    assert sources[0]["chunk_id"] == "ch2"
    assert sources[1]["citation"] == 3
    assert sources[1]["chunk_id"] == "ch3"
