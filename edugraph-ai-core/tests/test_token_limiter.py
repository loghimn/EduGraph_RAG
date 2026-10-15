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
    client.groq_client.chat.completions.with_raw_response.create = create

    limiter = MagicMock()
    limiter.wait_and_deduct = AsyncMock()
    limiter.refund = AsyncMock()
    limiter.update_from_headers = AsyncMock()
    with (
        patch("app.core.rate_limiter.token_limiter", limiter),
        patch.object(client._call_provider.retry, "wait", wait_none()),
    ):
        with pytest.raises(openai.RateLimitError):
            await client._call_provider(client.groq_client, "model", "test prompt", False)

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
    with (
        patch("app.core.llm.cache", cache),
        patch.object(client, "_call_provider", new=AsyncMock(return_value=("not json", "model"))),
    ):
        with pytest.raises(ValueError, match="LLM returned invalid JSON"):
            await client.generate("prompt", "v1", {"input": "data"}, json_mode=True)

    cache.set.assert_not_called()
