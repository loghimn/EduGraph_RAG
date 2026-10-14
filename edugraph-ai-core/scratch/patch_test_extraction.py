with open('tests/test_extraction.py', 'r', encoding='utf-8') as f: text = f.read()
text = text.replace('PROMPT_VERSION == "v2"', 'PROMPT_VERSION == "v3"')
with open('tests/test_extraction.py', 'w', encoding='utf-8') as f: f.write(text)
