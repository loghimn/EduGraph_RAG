import asyncio
import time
import logging
from app.core.context import heartbeat_cb_var

logger = logging.getLogger(__name__)

class TokenLimiter:
    def __init__(self, tpm_limit=6000, heartbeat_callback=None, heartbeat_interval=30.0):
        self.tpm_limit = tpm_limit
        self.remaining_tokens = tpm_limit
        self.reset_time = time.time()
        self.last_update_time = time.time()
        self.lock = asyncio.Lock()
        self.heartbeat_callback = heartbeat_callback
        self.heartbeat_interval = heartbeat_interval
        self.priority_waiters = 0
        
    async def wait_and_deduct(self, estimated_tokens: int, heartbeat_callback=None, priority: bool = False):
        is_waiting_priority = False
        try:
            while True:
                wait_time = 0
                async with self.lock:
                    now = time.time()
                    
                    # Bơm token dựa trên thời gian đã trôi qua
                    elapsed = now - self.last_update_time
                    if elapsed > 0:
                        rate = self.tpm_limit / 60.0
                        replenished = int(elapsed * rate)
                        self.remaining_tokens = min(self.tpm_limit, self.remaining_tokens + replenished)
                        self.last_update_time = now
                        
                    # Reset time nếu đã qua 60s
                    if now >= self.reset_time:
                        self.remaining_tokens = self.tpm_limit
                        self.reset_time = now + 60
                        self.last_update_time = now
                        
                    # Nếu non-priority và có priority request đang chờ, nhường token
                    if not priority and self.priority_waiters > 0:
                        wait_time = 0.5
                    elif estimated_tokens > self.remaining_tokens:
                        rate = self.tpm_limit / 60.0
                        needed = estimated_tokens - self.remaining_tokens
                        wait_time = max(0.0, needed / rate) + 0.5
                        if priority and not is_waiting_priority:
                            self.priority_waiters += 1
                            is_waiting_priority = True
                        print(f"[RateLimiter] Sắp vượt token (priority={priority}, cần {estimated_tokens}, còn {self.remaining_tokens}). Đợi {wait_time:.1f}s...")
                    else:
                        self.remaining_tokens -= estimated_tokens
                        return
                
                # Sleep outside lock
                if wait_time > 0:
                    to_sleep = min(wait_time, self.heartbeat_interval)
                    await asyncio.sleep(to_sleep)
                    cb = heartbeat_callback or self.heartbeat_callback or heartbeat_cb_var.get()
                    if cb:
                        if asyncio.iscoroutinefunction(cb):
                            await cb()
                        else:
                            cb()
        finally:
            if is_waiting_priority:
                async with self.lock:
                    self.priority_waiters = max(0, self.priority_waiters - 1)

    async def refund(self, diff: int):
        async with self.lock:
            self.remaining_tokens += diff
            if self.remaining_tokens > self.tpm_limit:
                self.remaining_tokens = self.tpm_limit

    async def update_from_headers(self, headers: dict):
        rem_tok = headers.get("x-ratelimit-remaining-tokens")
        reset_tok = headers.get("x-ratelimit-reset-tokens")
        rem_req = headers.get("x-ratelimit-remaining-requests")
        limit_req = headers.get("x-ratelimit-limit-requests")
        limit_tok = headers.get("x-ratelimit-limit-tokens")
        reset_req = headers.get("x-ratelimit-reset-requests")
        
        async with self.lock:
            if rem_tok and reset_tok:
                try:
                    self.remaining_tokens = int(rem_tok)
                    rt = float(reset_tok.replace("s", ""))
                    self.reset_time = time.time() + rt
                    print(f"[{time.strftime('%H:%M:%S')}] [RateLimit Headers] Limit: {limit_req} req, {limit_tok} tok | Remaining: {rem_req} req, {rem_tok} tok | Reset: {reset_req} req, {reset_tok} tok")
                except Exception:
                    pass

token_limiter = TokenLimiter()
