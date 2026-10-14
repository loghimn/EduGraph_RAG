with open('tests/test_limiter_heartbeat.py', 'r', encoding='utf-8') as f:
    text = f.read()
text = text.replace('async def fake_hb():', 'def fake_hb():')
with open('tests/test_limiter_heartbeat.py', 'w', encoding='utf-8') as f:
    f.write(text)

with open('tests/test_prereq_constants.py', 'r', encoding='utf-8') as f:
    text = f.read()
text = text.replace('"score": float(i)', '"confidence": float(i)')
with open('tests/test_prereq_constants.py', 'w', encoding='utf-8') as f:
    f.write(text)
