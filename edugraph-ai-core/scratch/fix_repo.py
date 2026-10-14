import re
with open('app/db/repo.py', 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('c.name AS canonical,', 'c.name AS name,')
text = text.replace('"name": c["canonical"],', '"name": c["name"],')

with open('app/db/repo.py', 'w', encoding='utf-8') as f:
    f.write(text)
