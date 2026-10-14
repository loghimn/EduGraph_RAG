with open('tests/test_extraction.py', 'rb') as f: data = f.read()
text = data.decode('latin1')
text = text.replace('PROMPT_VERSION == "v2"', 'PROMPT_VERSION == "v3"')
with open('tests/test_extraction.py', 'wb') as f: f.write(text.encode('utf-8'))
