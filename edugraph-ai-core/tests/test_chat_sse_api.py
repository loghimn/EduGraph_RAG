import pytest
import json
from unittest.mock import patch, AsyncMock, MagicMock
from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

def test_chat_auth_403():
    # Sai header X-Internal-Key
    resp = client.post(
        "/chat",
        headers={"X-Internal-Key": "wrong_key"},
        json={"course_id": "c1", "user_id": "u1", "message": "hello"}
    )
    assert resp.status_code == 403

@patch("app.api.chat.neo4j_client")
def test_chat_course_404_not_found(mock_neo4j):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session
    mock_res = AsyncMock()
    mock_res.single.return_value = None
    mock_session.run.return_value = mock_res

    resp = client.post(
        "/chat",
        headers={"X-Internal-Key": "correct"},
        json={"course_id": "non_existent", "user_id": "u1", "message": "hello"}
    )
    assert resp.status_code == 404

@patch("app.api.chat.neo4j_client")
def test_chat_course_403_not_owner(mock_neo4j):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session
    mock_res = AsyncMock()
    mock_res.single.return_value = {"owner_id": "owner_different"}
    mock_session.run.return_value = mock_res

    resp = client.post(
        "/chat",
        headers={"X-Internal-Key": "correct"},
        json={"course_id": "c1", "user_id": "u1", "message": "hello"}
    )
    assert resp.status_code == 403

@patch("app.api.chat.verify_course_ownership", new=AsyncMock())
@patch("app.api.chat.link_concepts")
def test_chat_clarify_sse(mock_link):
    # Giả lập linker trả về clarify
    mock_link.return_value = {
        "concept_ids": [],
        "clarify": [
            {"id": "c1", "name": "Bàn dữ liệu"},
            {"id": "c2", "name": "Bản ghi"}
        ]
    }

    resp = client.post(
        "/chat",
        headers={"X-Internal-Key": "correct"},
        json={"course_id": "c1", "user_id": "u1", "message": "ban la gi"}
    )
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers["content-type"]
    
    content = resp.text
    assert "event: clarify" in content
    assert "event: done" in content
    assert "Bàn dữ liệu" in content

@patch("app.api.chat.verify_course_ownership", new=AsyncMock())
@patch("app.api.chat.link_concepts")
@patch("app.api.chat.retrieve_chunks")
@patch("app.api.chat.llm_client")
def test_chat_explain_normal_sse_stream(mock_llm, mock_retrieve, mock_link):
    mock_link.return_value = {"concept_ids": ["c1"], "clarify": None}
    mock_retrieve.return_value = [
        {"id": "ch1", "doc_id": "d1", "page": 1, "index": 0, "text": "Index trong SQL giúp tăng tốc độ tìm kiếm."}
    ]
    
    # Mock LLM stream trả về từng token có kèm trích dẫn [1]
    async def fake_stream(prompt, priority=True):
        tokens = ["Index ", "trong ", "SQL ", "rất ", "hữu ", "ích [1]."]
        for t in tokens:
            yield t
    mock_llm.generate_stream = fake_stream

    resp = client.post(
        "/chat",
        headers={"X-Internal-Key": "correct"},
        json={"course_id": "c1", "user_id": "u1", "message": "Index là gì?"}
    )
    assert resp.status_code == 200
    
    content = resp.text
    assert "event: token" in content
    assert "event: sources" in content
    assert "event: done" in content
    # Kiểm tra sources chứa chunk ch1
    assert "ch1" in content

@patch("app.api.chat.verify_course_ownership", new=AsyncMock())
@patch("app.api.chat.link_concepts")
@patch("app.api.chat.retrieve_chunks")
@patch("app.api.chat.llm_client")
def test_chat_concept_ids_bypasses_linker(mock_llm, mock_retrieve, mock_link):
    # Khi truyền concept_ids tường minh
    mock_retrieve.return_value = []
    async def fake_stream(prompt, priority=True):
        yield "Đã hiểu."
    mock_llm.generate_stream = fake_stream

    resp = client.post(
        "/chat",
        headers={"X-Internal-Key": "correct"},
        json={"course_id": "c1", "user_id": "u1", "message": "Giải thích", "concept_ids": ["c1", "c2"]}
    )
    assert resp.status_code == 200
    # Không được gọi link_concepts
    mock_link.assert_not_called()
    mock_retrieve.assert_called_once()
    assert mock_retrieve.call_args[1]["concept_ids"] == ["c1", "c2"]
