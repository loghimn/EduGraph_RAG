import json
from unittest.mock import AsyncMock, patch

import pytest

from app.chat.context import prepare_history, resolve_question
from app.chat.answer import build_explain_prompt


def test_prepare_history_bounds_context_and_removes_old_citations():
    history = [{"role": "user", "content": "older"}] * 15
    history += [{"role": "assistant", "content": "Java Servlet [99]"}]
    result = prepare_history(history)
    assert len(result) == 12
    assert result[-1]["content"] == "Java Servlet "
    long_history = [{"role": "assistant", "content": "x" * 5_000}] * 12
    assert sum(len(item["content"]) for item in prepare_history(long_history)) <= 12_000


@pytest.mark.asyncio
@patch("app.chat.context.llm_client")
async def test_first_question_does_not_call_context_llm(mock_llm):
    assert await resolve_question("Java Servlet?", []) == "Java Servlet?"
    mock_llm.generate.assert_not_called()


@pytest.mark.asyncio
@patch("app.chat.context.llm_client")
async def test_followup_uses_previous_answer_to_form_search_question(mock_llm):
    mock_llm.generate = AsyncMock(return_value={"query": "Thứ tự học Java, HTTP để học Java Servlet?"})
    history = [{"role": "user", "content": "Để học Java Servlet cần học những gì?"},
               {"role": "assistant", "content": "Cần Java và HTTP."}]
    message = "Trong những kiến thức vừa liệt kê, học cái nào trước?"
    result = await resolve_question(message, history)
    assert "Java Servlet" in result
    kwargs = mock_llm.generate.call_args.kwargs
    assert kwargs["input_data"] == {"history": history, "question": message}
    assert kwargs["json_mode"] is True


@pytest.mark.asyncio
@patch("app.chat.context.llm_client")
async def test_invalid_context_result_fails_instead_of_using_invented_query(mock_llm):
    mock_llm.generate = AsyncMock(return_value={"answer": "unusable"})
    with pytest.raises(ValueError, match="Unable to resolve"):
        await resolve_question("Học cái nào trước?", [{"role": "assistant", "content": "Java và HTTP"}])


def test_answer_prompt_keeps_history_separate_from_current_evidence():
    history = [{"role": "assistant", "content": "Cần Java và HTTP."}]
    prompt = build_explain_prompt("Học cái nào trước?", [{"text": "Java fundamentals"}],
                                  history=history, resolved_question="Thứ tự học Java Servlet?")
    assert json.dumps(history, ensure_ascii=False) in prompt
    assert "Thứ tự học Java Servlet?" in prompt
    assert "[1] Java fundamentals" in prompt
    assert "không phải nguồn bằng chứng" in prompt
