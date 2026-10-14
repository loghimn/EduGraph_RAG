import pytest
import asyncio
import time
from app.core.rate_limiter import TokenLimiter
from app.core.context import heartbeat_cb_var

@pytest.mark.asyncio
async def test_token_limiter_heartbeat():
    limiter = TokenLimiter(tpm_limit=10, heartbeat_interval=0.05)
    
    hb_calls = 0
    def fake_hb():
        nonlocal hb_calls
        hb_calls += 1
        
    heartbeat_cb_var.set(fake_hb)
    
    limiter.remaining_tokens = 0
    limiter.reset_time = time.time() + 60
    limiter.last_update_time = time.time()
    
    async def advancer():
        while True:
            await asyncio.sleep(0.02)
            if hb_calls > 0:
                async with limiter.lock:
                    limiter.last_update_time = time.time()
                    limiter.remaining_tokens = 10
                break
                
    adv = asyncio.create_task(advancer())
    
    await limiter.wait_and_deduct(1, heartbeat_callback=fake_hb)
    
    adv.cancel()
    assert hb_calls > 0
