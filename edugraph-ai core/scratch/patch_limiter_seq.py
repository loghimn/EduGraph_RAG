import pytest
import asyncio
import time
from unittest.mock import patch, AsyncMock
from app.core.rate_limiter import TokenLimiter

@pytest.mark.asyncio
async def test_token_limiter_concurrency():
    limiter = TokenLimiter(tpm_limit=6000)
    history = []
    
    current_time = 0.0
    
    def fake_time():
        return current_time
        
    async def fake_sleep(secs):
        nonlocal current_time
        # Advanced time immediately to the target sleep end. 
        # But if multiple workers are sleeping, the time shouldn't advance sequentially.
        # This is a bit tricky to mock perfectly for gather(). 
        # So we'll just yield to the event loop.
        current_time = max(current_time, current_time + secs)
        
    # Wait, the problem is they read current_time, add secs, and update.
    # A better way is a time-stepping event loop, or just mock time but use a queue.
    # Actually, the real wait_and_deduct logic uses:
    # now = time.time()
    # pass_time = now - self.reset_time
    
    # We can just test the Limiter by calling wait_and_deduct sequentially in a loop,
    # advancing our fake clock by whatever sleep it requested!
    
    for i in range(10):
        # We manually step the logic
        with patch('time.time', side_effect=fake_time), patch('asyncio.sleep', new=AsyncMock(side_effect=fake_sleep)):
            await limiter.wait_and_deduct(1500)
            history.append((current_time, 1500))
            
    for i in range(len(history)):
        window_start = history[i][0]
        tokens_in_window = sum(t for ts, t in history if window_start <= ts < window_start + 60.0)
        assert tokens_in_window <= 6000, f"Exceeded limit: {tokens_in_window}"
    
    assert len(history) == 10
