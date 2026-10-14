import pytest
import asyncio
import time
from app.core.rate_limiter import TokenLimiter

@pytest.mark.asyncio
async def test_token_limiter_priority_preference():
    # Giả lập limiter có ít token
    limiter = TokenLimiter(tpm_limit=100, heartbeat_interval=0.05)
    limiter.remaining_tokens = 0
    limiter.reset_time = time.time() + 60
    limiter.last_update_time = time.time()
    
    execution_order = []
    
    async def background_task():
        # Pipeline / background task: priority=False
        await limiter.wait_and_deduct(50, priority=False)
        execution_order.append("background")
        
    async def chat_task():
        # Chat task: priority=True
        await limiter.wait_and_deduct(50, priority=True)
        execution_order.append("chat")
        
    # Cho background task bắt đầu trước
    bg_t = asyncio.create_task(background_task())
    await asyncio.sleep(0.02) # Để background task vào hàng đợi
    
    # Cho chat task bắt đầu sau
    chat_t = asyncio.create_task(chat_task())
    await asyncio.sleep(0.02)
    
    # Bơm vừa đủ 50 token cho 1 task
    async with limiter.lock:
        limiter.last_update_time = time.time()
        limiter.remaining_tokens = 50
        
    # Chờ chat_t hoàn thành
    await asyncio.wait_for(chat_t, timeout=2.0)
    
    # Chat phải hoàn thành trước background task nhờ priority
    assert "chat" in execution_order
    assert execution_order[0] == "chat"
    
    # Bơm tiếp 50 token để background task hoàn thành
    async with limiter.lock:
        limiter.remaining_tokens = 50
    await asyncio.wait_for(bg_t, timeout=2.0)
    assert execution_order == ["chat", "background"]
