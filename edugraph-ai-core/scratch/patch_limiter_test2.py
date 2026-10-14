with open('tests/test_token_limiter.py', 'r', encoding='utf-8') as f: text = f.read()
import re
new_test = """@pytest.mark.asyncio
async def test_token_limiter_concurrency():
    limiter = TokenLimiter(tpm_limit=6000)
    history = []
    current_time = 0.0
    def fake_time():
        return current_time
    async def fake_sleep(secs):
        nonlocal current_time
        current_time += secs
    async def worker(token_cost, worker_id):
        await limiter.wait_and_deduct(token_cost)
        history.append((current_time, token_cost))
    with patch('time.time', side_effect=fake_time), patch('asyncio.sleep', new=fake_sleep):
        limiter.reset_time = fake_time()
        workers = [asyncio.create_task(worker(1500, i)) for i in range(10)]
        await asyncio.gather(*workers)
    for i in range(len(history)):
        window_start = history[i][0]
        tokens_in_window = sum(t for ts, t in history if window_start <= ts < window_start + 60.0)
        assert tokens_in_window <= 6000, f"Exceeded limit: {tokens_in_window}"
    assert len(history) == 10
"""
text = re.sub(r"@pytest\.mark\.asyncio\nasync def test_token_limiter_concurrency\(\):.*?@pytest\.mark\.asyncio", new_test + "@pytest.mark.asyncio", text, flags=re.DOTALL)
with open('tests/test_token_limiter.py', 'w', encoding='utf-8') as f: f.write(text)
