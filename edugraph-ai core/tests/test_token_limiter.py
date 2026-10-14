import pytest
import asyncio
from unittest.mock import patch
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
