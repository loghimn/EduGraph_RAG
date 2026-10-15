import pytest
import asyncio
import time
from unittest.mock import patch, AsyncMock
from app.core.rate_limiter import TokenLimiter

@pytest.mark.asyncio
async def test_token_limiter_concurrency():
    limiter = TokenLimiter(tpm_limit=6000)
    
    # We will simulate 10 workers, each requesting 1500 tokens.
    # Total 15000 tokens. The limit is 6000 TPM.
    
    history = [] # Records (timestamp, tokens)
    
    current_time = 0.0
    
    def fake_time():
        return current_time
        
    async def fake_sleep(secs):
        nonlocal current_time
        current_time += secs
        
    async def worker(token_cost, worker_id):
        await limiter.wait_and_deduct(token_cost)
        # Record the time when the token was successfully granted
        history.append((current_time, token_cost))
        
    with patch('time.time', side_effect=fake_time), patch('asyncio.sleep', new=fake_sleep):
        limiter.reset_time = fake_time()
        
        workers = [asyncio.create_task(worker(1500, i)) for i in range(10)]
        await asyncio.gather(*workers)
        
    # Now check the history.
    # For ANY window of 60 seconds, the sum of tokens granted should NOT exceed 6000.
    for i in range(len(history)):
        window_start = history[i][0]
        tokens_in_window = 0
        for j in range(i, len(history)):
            if history[j][0] - window_start < 60.0:
                tokens_in_window += history[j][1]
        assert tokens_in_window <= 6000, f"Token limit exceeded! Window starting at {window_start} had {tokens_in_window} tokens."
        
    assert len(history) == 10
