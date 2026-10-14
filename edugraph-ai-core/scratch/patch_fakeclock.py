with open('tests/test_token_limiter.py', 'r', encoding='utf-8') as f: text = f.read()
text = text.replace('await asyncio.sleep(0.001) # Real sleep to yield', 'await real_sleep(0.001)')
text = text.replace('advancer = asyncio.create_task(time_advancer())', 'real_sleep = asyncio.sleep\n    advancer = asyncio.create_task(time_advancer())')
with open('tests/test_token_limiter.py', 'w', encoding='utf-8') as f: f.write(text)
