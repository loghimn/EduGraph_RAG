import re
with open('tests/test_token_limiter.py', 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('await asyncio.sleep(0.001)', 'await real_sleep(0.001)')

with open('tests/test_token_limiter.py', 'w', encoding='utf-8') as f:
    f.write(text)
