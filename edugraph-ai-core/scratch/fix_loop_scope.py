with open('tests/test_prerequisite_pipeline.py', 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('(loop_scope="module")', '')

with open('tests/test_prerequisite_pipeline.py', 'w', encoding='utf-8') as f:
    f.write(text)
