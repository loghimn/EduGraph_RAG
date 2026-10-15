with open('scripts/test_ingest_api.py', 'r', encoding='utf-8') as f: text = f.read()
text = text.replace('"my-secret-key"', '"dummy_key"')
with open('scripts/test_ingest_api.py', 'w', encoding='utf-8') as f: f.write(text)
