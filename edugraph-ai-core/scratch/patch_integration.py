import re
with open('tests/test_integration_reingest.py', 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('await neo4j_client.connect()\n    try:', 'await neo4j_client.connect()\n    async with neo4j_client.driver.session() as s:\n        await s.run("MATCH(n) WHERE n.course_id=\'__test__\' DETACH DELETE n")\n    try:')

text = text.replace('"name": "C1"', '"name": "C1", "canonical": "C1"')
text = text.replace('"name": "C2"', '"name": "C2", "canonical": "C2"')

text = text.replace('from app.concept_extraction.pipeline import process_document_pipeline', 'from app.api.ingest import _background_ingest')
text = text.replace('await process_document_pipeline("d1", "__test__", "mock.pdf")', 'await _background_ingest("d1", "__test__", "mock.pdf", "u1")')

text = text.replace("MERGE (c1:Concept {course_key: '__test__|C1'}) MERGE (c2:Concept {course_key: '__test__|C2'})", "MERGE (c1:Concept {course_key: '__test__|C1'}) SET c1.id='C1', c1.key='C1', c1.embedding=[0.1] MERGE (c2:Concept {course_key: '__test__|C2'}) SET c2.id='C2', c2.key='C2', c2.embedding=[0.1]")

with open('tests/test_integration_reingest.py', 'w', encoding='utf-8') as f:
    f.write(text)
