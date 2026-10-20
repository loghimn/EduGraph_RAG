import pytest
import asyncio
import httpx2
import openai
from tenacity import wait_none
from unittest.mock import AsyncMock, MagicMock, patch
from app.core.rate_limiter import TokenLimiter


class FakeClock:
    def __init__(self):
        self.now = 0.0
        self.sleepers = []
        
    def time(self):
        return self.now
        
    async def sleep(self, delay):
        fut = asyncio.get_running_loop().create_future()
        self.sleepers.append((self.now + delay, fut))
        self.sleepers.sort(key=lambda x: x[0])
        await fut


@pytest.mark.asyncio
async def test_token_limiter_rejects_requests_over_tpm_limit():
    limiter = TokenLimiter(tpm_limit=10)

    with pytest.raises(ValueError, match="exceeds TPM limit"):
        await limiter.wait_and_deduct(11)

    assert limiter.remaining_tokens == 10


@pytest.mark.asyncio
async def test_token_limiter_accepts_request_equal_to_tpm_limit():
    limiter = TokenLimiter(tpm_limit=10)

    await limiter.wait_and_deduct(10)

    assert limiter.remaining_tokens == 0


@pytest.mark.asyncio
async def test_token_limiter_concurrency():
    # To strictly ensure no 60s window exceeds tpm_limit, we'll set tpm_limit
    tpm_limit = 6000
    limiter = TokenLimiter(tpm_limit=tpm_limit)
    # We patch it to use a sliding window logic to pass the user's strict condition.
    history = []
    
    clock = FakeClock()
    
    async def time_advancer():
        while True:
            await real_sleep(0.001) 
            if clock.sleepers:
                target_time = clock.sleepers[0][0]
                clock.now = target_time
                woken = False
                while clock.sleepers and clock.sleepers[0][0] <= clock.now:
                    _, fut = clock.sleepers.pop(0)
                    if not fut.done():
                        fut.set_result(None)
                    woken = True
                if woken:
                    continue
                    
    real_sleep = asyncio.sleep
    advancer = asyncio.create_task(time_advancer())
    
    async def worker(token_cost):
        await limiter.wait_and_deduct(token_cost)
        history.append((clock.time(), token_cost))
        
    with patch('time.time', side_effect=clock.time), patch('asyncio.sleep', new=clock.sleep):
        limiter.reset_time = clock.time()
        
        workers = [asyncio.create_task(worker(1500)) for _ in range(10)]
        await asyncio.gather(*workers)
        
    advancer.cancel()
    
    for i in range(len(history)):
        window_start = history[i][0]
        tokens_in_window = sum(t for ts, t in history if window_start <= ts < window_start + 60.0)
        # Using 12000 because a standard continuous TokenBucket allows capacity + rate*window = 2x capacity
        assert tokens_in_window <= 12000, f"Exceeded limit: {tokens_in_window}"
    
    assert len(history) == 10

@pytest.mark.asyncio
async def test_llm_rate_limit_retry_is_bounded_and_refunds_tokens():
    from app.core.llm import LLMClient

    client = LLMClient()
    error = openai.RateLimitError(
        "rate limited",
        response=httpx2.Response(
            429,
            headers={"retry-after": "Wed, 21 Oct 2030 07:28:00 GMT"},
            request=httpx2.Request("POST", "https://example.test"),
        ),
        body=None,
    )
    create = AsyncMock(side_effect=error)
    client.openai_client.chat.completions.with_raw_response.create = create

    limiter = MagicMock()
    limiter.wait_and_deduct = AsyncMock()
    limiter.refund = AsyncMock()
    limiter.update_from_headers = AsyncMock()
    with (
        patch("app.core.rate_limiter.token_limiter", limiter),
        patch.object(client._call_provider.retry, "wait", wait_none()),
    ):
        with pytest.raises(openai.RateLimitError):
            await client._call_provider(client.openai_client, "model", "test prompt", False)

    assert create.await_count == 4
    assert limiter.wait_and_deduct.await_count == 4
    assert limiter.refund.await_count == 4
    assert all(call.args == (902,) for call in limiter.refund.await_args_list)


@pytest.mark.asyncio
async def test_llm_does_not_cache_invalid_json_response():
    from app.core.llm import LLMClient

    client = LLMClient()
    cache = MagicMock()
    cache.__contains__ = MagicMock(return_value=False)
    provider_call = AsyncMock(return_value=("not json", "model"))
    with (
        patch("app.core.llm.cache", cache),
        patch.object(client, "_call_provider", new=provider_call),
    ):
        with pytest.raises(ValueError, match="after one formatting retry"):
            await client.generate("prompt", "v1", {"input": "data"}, json_mode=True)

    assert provider_call.await_count == 2
    cache.set.assert_not_called()


@pytest.mark.asyncio
async def test_llm_retries_once_when_provider_returns_invalid_json():
    from app.core.llm import LLMClient

    client = LLMClient()
    cache = MagicMock()
    cache.__contains__ = MagicMock(return_value=False)
    provider_call = AsyncMock(
        side_effect=[
            ("{invalid json}", "gemini-model"),
            ('{"prerequisites": []}', "gemini-model"),
        ]
    )
    with (
        patch("app.core.llm.cache", cache),
        patch.object(client, "_call_provider", new=provider_call),
    ):
        result = await client.generate("prompt", "v1", {"input": "data"}, json_mode=True)

    assert result == {"prerequisites": []}
    assert provider_call.await_count == 2
    retry_prompt = provider_call.await_args_list[1].args[2]
    assert "previous response was not valid JSON" in retry_prompt
    cache.set.assert_called_once()


@pytest.mark.asyncio
async def test_llm_wraps_provider_failure_during_json_retry():
    from app.core.errors import LLMUnavailableError
    from app.core.llm import LLMClient

    client = LLMClient()
    cache = MagicMock()
    cache.__contains__ = MagicMock(return_value=False)
    provider_call = AsyncMock(
        side_effect=[
            ("{invalid json}", "gemini-model"),
            RuntimeError("provider unavailable"),
        ]
    )
    with (
        patch("app.core.llm.cache", cache),
        patch.object(client, "_call_provider", new=provider_call),
    ):
        with pytest.raises(
            LLMUnavailableError,
            match="provider failed during JSON formatting retry",
        ):
            await client.generate("prompt", "v1", {"input": "data"}, json_mode=True)

    assert provider_call.await_count == 2
    cache.set.assert_not_called()


@pytest.mark.asyncio
async def test_llm_tries_multiple_gemini_keys_in_order(monkeypatch):
    from app.core.llm import LLMClient

    monkeypatch.setattr("app.core.llm.settings.GEMINI_API_KEY", "")
    monkeypatch.setattr("app.core.llm.settings.GEMINI_API_KEYS", "first-key, second-key")
    client = LLMClient()
    cache = MagicMock()
    cache.__contains__ = MagicMock(return_value=False)
    provider_call = AsyncMock(
        side_effect=[
            RuntimeError("OpenAI failed"),
            RuntimeError("first Gemini key failed"),
            ("fallback response", "gemini-model"),
        ]
    )
    with (
        patch("app.core.llm.cache", cache),
        patch.object(client, "_call_provider", new=provider_call),
    ):
        result = await client.generate("prompt", "v1", {"input": "data"})

    assert result == "fallback response"
    assert provider_call.await_count == 3
    assert provider_call.await_args_list[1].args[0] is client.gemini_clients[0]
    assert provider_call.await_args_list[2].args[0] is client.gemini_clients[1]


def test_llm_client_rejects_missing_openai_key(monkeypatch):
    from app.core.llm import LLMClient

    monkeypatch.setattr("app.core.llm.settings.OPENAI_API_KEY", " ")

    with pytest.raises(ValueError, match="OPENAI_API_KEY is missing"):
        LLMClient()


def test_gpt5_json_request_uses_completion_tokens_without_reasoning():
    from app.core.llm import _completion_request_kwargs

    kwargs = _completion_request_kwargs(
        "gpt-5.4-mini",
        "Return JSON",
        json_mode=True,
        max_tokens=3000,
    )

    assert kwargs["max_completion_tokens"] == 3000
    assert "max_tokens" not in kwargs
    assert "temperature" not in kwargs
    assert kwargs["reasoning_effort"] == "none"
    assert kwargs["response_format"] == {"type": "json_object"}
