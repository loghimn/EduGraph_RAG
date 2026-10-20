import pytest
import json
from unittest.mock import patch, AsyncMock, MagicMock
from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


@patch("app.api.chat.verify_course_exists", new=AsyncMock())
@patch("app.api.chat.resolve_question", new_callable=AsyncMock)
@patch("app.api.chat.link_concepts", new_callable=AsyncMock)
@patch("app.api.chat.retrieve_chunks", new_callable=AsyncMock)
@patch("app.api.chat.llm_client")
def test_reply_uses_selected_message_without_recent_history(mock_llm, mock_retrieve, mock_link, mock_resolve):
    mock_resolve.return_value = "Java Servlet HTTP example"
    mock_link.return_value = {"concept_ids": ["servlet"], "clarify": None}
    mock_retrieve.return_value = [{"id": "ch1", "doc_id": "1", "text": "Servlet HTTP"}]

    async def answer(prompt, priority=True):
        assert "Servlet handles HTTP" in prompt
        yield "Example [1]"

    mock_llm.generate_stream = answer
    resp = client.post("/chat", headers={"X-Internal-Key": "test-internal-api-key"}, json={
        "course_id": "c1", "user_id": "u1", "message": "Give an example",
        "reply_context": [{"role": "assistant", "content": "Servlet handles HTTP [7]"}]
    })
    assert resp.status_code == 200
    mock_resolve.assert_awaited_once_with("Give an example", [],
                                          [{"role": "assistant", "content": "Servlet handles HTTP "}])
    mock_link.assert_awaited_once_with("c1", "Java Servlet HTTP example")
    assert "Example [1]" in resp.text


@patch("app.api.chat.verify_course_exists", new=AsyncMock())
@patch("app.api.chat.resolve_question", new_callable=AsyncMock)
@patch("app.api.chat.link_concepts", new_callable=AsyncMock)
@patch("app.api.chat.retrieve_chunks", new_callable=AsyncMock)
@patch("app.api.chat.llm_client")
def test_followup_links_and_retrieves_using_contextual_question(mock_llm, mock_retrieve, mock_link, mock_resolve):
    resolved = "Thứ tự học Java và HTTP để học Java Servlet?"
    mock_resolve.return_value = resolved
    mock_link.return_value = {"concept_ids": ["servlet"], "clarify": None}
    mock_retrieve.return_value = [{"id": "ch1", "doc_id": "1", "text": "Java Servlet"}]

    async def answer(prompt, priority=True):
        assert "Cần Java và HTTP" in prompt
        assert resolved in prompt
        yield "Học Java trước [1]"

    mock_llm.generate_stream = answer
    resp = client.post("/chat", headers={"X-Internal-Key": "test-internal-api-key"}, json={
        "course_id": "c1", "user_id": "u1", "message": "Học cái nào trước?",
        "history": [{"role": "assistant", "content": "Cần Java và HTTP [5]"}]
    })
    assert resp.status_code == 200
    mock_resolve.assert_awaited_once_with("Học cái nào trước?",
                                          [{"role": "assistant", "content": "Cần Java và HTTP "}])
    mock_link.assert_awaited_once_with("c1", resolved)
    assert mock_retrieve.call_args.kwargs["query"] == resolved
    assert "Học Java trước [1]" in resp.text
    assert "event: done" in resp.text


@patch("app.api.chat.verify_course_exists", new=AsyncMock())
@patch("app.api.chat.resolve_question", new_callable=AsyncMock)
@patch("app.api.chat.link_concepts", new_callable=AsyncMock)
@patch("app.api.chat.llm_client")
def test_changed_topic_still_returns_no_data(mock_llm, mock_link, mock_resolve):
    from app.api.chat import NO_DATA_MESSAGE
    mock_resolve.return_value = "weather tomorrow"
    mock_link.return_value = {"concept_ids": [], "clarify": None}
    resp = client.post("/chat", headers={"X-Internal-Key": "test-internal-api-key"}, json={
        "course_id": "c1", "user_id": "u1", "message": "weather tomorrow",
        "history": [{"role": "assistant", "content": "Learn Java Servlet"}]
    })
    mock_link.assert_awaited_once_with("c1", "weather tomorrow")
    assert NO_DATA_MESSAGE in resp.text
    mock_llm.generate_stream.assert_not_called()


@pytest.mark.parametrize("concept_ids", [None, []])
@patch("app.api.chat.verify_course_exists", new=AsyncMock())
@patch("app.api.chat.link_concepts", new_callable=AsyncMock)
@patch("app.api.chat.retrieve_chunks", new_callable=AsyncMock)
@patch("app.api.chat.llm_client")
def test_chat_no_concepts_returns_message_without_retrieval_or_llm(
    mock_llm, mock_retrieve, mock_link, concept_ids
):
    from app.api.chat import NO_DATA_MESSAGE
    mock_link.return_value = {"concept_ids": [], "clarify": None}
    resp = client.post(
        "/chat", headers={"X-Internal-Key": "test-internal-api-key"},
        json={"course_id": "c1", "user_id": "u1", "message": "weather tomorrow",
              "concept_ids": concept_ids}
    )
    assert resp.status_code == 200
    assert "event: clarify" not in resp.text
    assert NO_DATA_MESSAGE in resp.text
    assert "event: sources\ndata: []" in resp.text
    assert resp.text.endswith("event: done\ndata: {}\n\n")
    mock_retrieve.assert_not_awaited()
    mock_llm.generate_stream.assert_not_called()
    if concept_ids is None:
        mock_link.assert_awaited_once_with("c1", "weather tomorrow")
    else:
        mock_link.assert_not_awaited()


@patch("app.api.chat.verify_course_exists", new=AsyncMock())
@patch("app.api.chat.link_concepts", new_callable=AsyncMock)
@patch("app.api.chat.retrieve_chunks", new_callable=AsyncMock)
@patch("app.api.chat.llm_client")
def test_chat_concept_without_evidence_returns_no_data(mock_llm, mock_retrieve, mock_link):
    from app.api.chat import NO_DATA_MESSAGE
    mock_link.return_value = {"concept_ids": ["c1"], "clarify": None}
    mock_retrieve.return_value = []
    resp = client.post(
        "/chat", headers={"X-Internal-Key": "test-internal-api-key"},
        json={"course_id": "c1", "user_id": "u1", "message": "OPTIONS?"}
    )
    assert resp.status_code == 200
    assert NO_DATA_MESSAGE in resp.text
    assert "event: sources\ndata: []" in resp.text
    mock_llm.generate_stream.assert_not_called()

@patch("app.api.chat.neo4j_client")
def test_chat_auth_403(mock_neo4j):
    # Sai header X-Internal-Key
    resp = client.post(
        "/chat",
        headers={"X-Internal-Key": "wrong_key"},
        json={"course_id": "c1", "user_id": "u1", "message": "hello"}
    )
    assert resp.status_code == 403
    mock_neo4j.driver.session.assert_not_called()

@patch("app.api.chat.neo4j_client")
def test_chat_missing_internal_key_is_rejected(mock_neo4j):
    resp = client.post(
        "/chat",
        json={"course_id": "c1", "user_id": "u1", "message": "hello"}
    )
    assert resp.status_code == 422
    mock_neo4j.driver.session.assert_not_called()

@patch("app.api.chat.neo4j_client")
def test_chat_course_404_not_found(mock_neo4j):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session
    mock_res = AsyncMock()
    mock_res.single.return_value = None
    mock_session.run.return_value = mock_res

    resp = client.post(
        "/chat",
        headers={"X-Internal-Key": "test-internal-api-key"},
        json={"course_id": "non_existent", "user_id": "u1", "message": "hello"}
    )
    assert resp.status_code == 404

@patch("app.api.chat.neo4j_client")
@patch("app.api.chat.link_concepts", new_callable=AsyncMock)
def test_chat_enrolled_user_authorized_by_java(mock_link, mock_neo4j):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session
    mock_res = AsyncMock()
    mock_res.single.return_value = {"id": "c1"}
    mock_link.return_value = {"clarify": [{"id": "concept1", "name": "Concept"}]}
    mock_session.run.return_value = mock_res

    resp = client.post(
        "/chat",
        headers={"X-Internal-Key": "test-internal-api-key"},
        json={"course_id": "c1", "user_id": "u1", "message": "hello"}
    )
    assert resp.status_code == 200
    assert "event: clarify" in resp.text
    mock_link.assert_awaited_once_with("c1", "hello")

@patch("app.api.chat.verify_course_exists", new=AsyncMock())
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
        headers={"X-Internal-Key": "test-internal-api-key"},
        json={"course_id": "c1", "user_id": "u1", "message": "ban la gi"}
    )
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers["content-type"]
    
    content = resp.text
    assert "event: clarify" in content
    assert "event: done" in content
    assert "Bàn dữ liệu" in content

@patch("app.api.chat.verify_course_exists", new=AsyncMock())
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
        headers={"X-Internal-Key": "test-internal-api-key"},
        json={"course_id": "c1", "user_id": "u1", "message": "Index là gì?"}
    )
    assert resp.status_code == 200
    
    content = resp.text
    assert "event: token" in content
    assert "event: sources" in content
    assert "event: done" in content
    # Kiểm tra sources chứa chunk ch1
    assert "ch1" in content

@patch("app.api.chat.verify_course_exists", new=AsyncMock())
@patch("app.api.chat.link_concepts")
@patch("app.api.chat.retrieve_chunks")
@patch("app.api.chat.llm_client")
def test_chat_concept_ids_bypasses_linker(mock_llm, mock_retrieve, mock_link):
    # Khi truyền concept_ids tường minh
    mock_retrieve.return_value = [{"id": "ch1", "text": "Reference"}]
    async def fake_stream(prompt, priority=True):
        yield "Đã hiểu."
    mock_llm.generate_stream = fake_stream

    resp = client.post(
        "/chat",
        headers={"X-Internal-Key": "test-internal-api-key"},
        json={"course_id": "c1", "user_id": "u1", "message": "Giải thích", "concept_ids": ["c1", "c2"]}
    )
    assert resp.status_code == 200
    # Không được gọi link_concepts
    mock_link.assert_not_called()
    mock_retrieve.assert_called_once()
    assert mock_retrieve.call_args[1]["concept_ids"] == ["c1", "c2"]
