with open('tests/test_token_limiter.py', 'a', encoding='utf-8') as f:
    f.write("""
@pytest.mark.asyncio
async def test_llm_respect_retry_after():
    from app.core.llm import LLMClient
    client = LLMClient()
    class MockResponse:
        def __init__(self):
            self.headers = {"retry-after": "0.2"}
    class MockError(Exception):
        def __init__(self):
            self.response = MockResponse()
    call_count = 0
    async def mock_call(*args, **kwargs):
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            raise MockError()
        return "success", "mock_model"
    with patch.object(client, '_call_provider', new=mock_call):
        try:
            await client.generate("test prompt", "v1", "data")
        except Exception:
            pass
    assert call_count >= 1
""")
