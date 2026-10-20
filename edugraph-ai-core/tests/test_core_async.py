import pytest
import asyncio
from typing import AsyncGenerator
from unittest.mock import AsyncMock, MagicMock, patch
from app.core.embedding import EmbeddingModel
from app.core.llm import LLMClient

@pytest.fixture
def mock_embedding_model():
    with patch("app.core.embedding.SentenceTransformer") as mock_st:
        mock_instance = MagicMock()
        mock_instance.encode.return_value = [[0.1] * 1024, [0.2] * 1024]
        mock_st.return_value = mock_instance
        
        # Reset singleton cho test
        EmbeddingModel._instance = None
        
        model = EmbeddingModel()
        model.load()
        yield model, mock_instance
        
        EmbeddingModel._instance = None

@pytest.mark.asyncio
async def test_embedding_singleton(mock_embedding_model):
    model1, mock_st = mock_embedding_model
    model2 = EmbeddingModel()
    
    assert model1 is model2, "Không phải là singleton!"
    
    # Hàm load() chỉ gọi SentenceTransformer 1 lần ở setup fixture
    model2.load()
    # mock_st được gọi 1 lần khi model1.load() ở fixture
    assert mock_st.encode.called

@pytest.mark.asyncio
async def test_embed_batch_mock(mock_embedding_model):
    model, _ = mock_embedding_model
    res = await model.embed_batch(["test 1", "test 2"])
    assert len(res) == 2
    assert len(res[0]) == 1024

@pytest.mark.asyncio
async def test_embed_batch_retries_on_cuda_oom_message(monkeypatch):
    model = EmbeddingModel()
    model._model = MagicMock()
    model._model.encode.side_effect = [
        RuntimeError("CUDA out of memory. Tried to allocate 1.00 GiB."),
        [[0.1, 0.2]],
    ]
    monkeypatch.setattr("app.core.embedding.settings.EMBED_BATCH_SIZE", 4)
    monkeypatch.setattr("app.core.embedding.torch.cuda.is_available", lambda: False)

    result = await model.embed_batch(["test"])

    assert result == [[0.1, 0.2]]
    assert [call.kwargs["batch_size"] for call in model._model.encode.call_args_list] == [4, 2]

@pytest.mark.slow
@pytest.mark.asyncio
async def test_embed_batch_real():
    # Test với model thật, phải có marker slow
    EmbeddingModel._instance = None
    model = EmbeddingModel()
    model.load()
    res = await model.embed_batch(["this is a real test"])
    assert len(res) == 1
    assert len(res[0]) == 1024

@pytest.mark.asyncio
async def test_llm_stream_error_after_first_token():
    # Test lỗi xảy ra SAU token đầu thì không fallback mà raise/yield lỗi
    client = LLMClient()
    
    async def mock_stream_openai():
        class MockDelta:
            def __init__(self, content):
                self.content = content
        class MockChoice:
            def __init__(self, content):
                self.delta = MockDelta(content)
        class MockChunk:
            def __init__(self, content):
                self.choices = [MockChoice(content)]
        
        yield MockChunk("first token")
        raise Exception("Random error after first token")
        
    client.openai_client.chat.completions.create = AsyncMock(return_value=mock_stream_openai())
    client.gemini_client.chat.completions.create = AsyncMock() # Nên không được gọi
    
    chunks = []
    async for chunk in client.generate_stream("hello"):
        chunks.append(chunk)
        
    assert chunks[0] == "first token"
    assert "ERROR: Stream interrupted" in chunks[1], "Không thấy lỗi stream được yield"
    assert not client.gemini_client.chat.completions.create.called, "Không được fallback nếu lỗi sau token đầu"

@pytest.mark.asyncio
async def test_llm_stream_first_token_timeout_uses_fallback(monkeypatch):
    client = LLMClient()
    stalled = asyncio.Event()

    async def openai_stream():
        await stalled.wait()
        yield None

    async def gemini_stream():
        class MockDelta:
            content = "fallback token"
        class MockChoice:
            delta = MockDelta()
        class MockChunk:
            choices = [MockChoice()]
        yield MockChunk()

    client.openai_client.chat.completions.create = AsyncMock(return_value=openai_stream())
    client.gemini_client.chat.completions.create = AsyncMock(return_value=gemini_stream())
    monkeypatch.setattr("app.core.llm.settings.GEMINI_API_KEY", "configured-test-key")
    monkeypatch.setattr("app.core.llm.settings.STREAM_FIRST_TOKEN_TIMEOUT", 0.01)
    monkeypatch.setattr(
        "app.core.rate_limiter.token_limiter.wait_and_deduct",
        AsyncMock(),
    )

    chunks = [chunk async for chunk in client.generate_stream("hello")]

    assert chunks == ["fallback token"]
    client.gemini_client.chat.completions.create.assert_awaited_once()
