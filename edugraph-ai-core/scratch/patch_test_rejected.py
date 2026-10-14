with open('tests/test_extraction.py', 'r', encoding='utf-8') as f: text = f.read()
text = text.replace("assert merge_cands[0] == ('Concept A', 'Concept A')", "assert merge_cands[0] == ('Concept A', 'Concept A', 'has_reviewed_edge (Alias Match)')")
with open('tests/test_extraction.py', 'w', encoding='utf-8') as f: f.write(text)
