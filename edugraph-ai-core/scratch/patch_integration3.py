import re
with open('tests/test_integration_reingest.py', 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace("MATCH(n) WHERE n.course_id='__test__' DETACH DELETE n", "MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")

text = text.replace("SET c1.id='C1', c1.key='C1', c1.embedding=[0.1] MERGE", "SET c1.id='C1', c1.key='C1', c1.course_id='__test__', c1.embedding=[0.1] MERGE")
text = text.replace("SET c2.id='C2', c2.key='C2', c2.embedding=[0.1] MERGE", "SET c2.id='C2', c2.key='C2', c2.course_id='__test__', c2.embedding=[0.1] MERGE")

with open('tests/test_integration_reingest.py', 'w', encoding='utf-8') as f:
    f.write(text)
