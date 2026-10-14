import re
with open('tests/test_integration_reingest.py', 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('"course_key": "__test__|C1", "start"', '"concept_key": "C1", "start"')
text = text.replace('"course_key": "__test__|C2", "start"', '"concept_key": "C2", "start"')
text = text.replace('_background_ingest("d1", "__test__", "mock.pdf", "u1")', '_background_ingest("d1", "__test__", "mock.pdf")')

with open('tests/test_integration_reingest.py', 'w', encoding='utf-8') as f:
    f.write(text)
