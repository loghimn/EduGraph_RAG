with open('app/concept_extraction/pipeline.py', 'r', encoding='utf-8') as f: text = f.read()
text = text.replace('Deleted orphans: unknown', 'Deleted orphans: {record.get(\"deleted_orphans\", 0)}')
with open('app/concept_extraction/pipeline.py', 'w', encoding='utf-8') as f: f.write(text)
