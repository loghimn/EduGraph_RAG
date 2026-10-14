with open('tests/test_prerequisite_pipeline.py', 'r', encoding='utf-8') as f: text = f.read()
text = text.replace('await session.run("MATCH (n {course_id: $course_id}) DETACH DELETE n", course_id=course_id)',
'await session.run("MATCH (n {course_id: $course_id}) DETACH DELETE n", course_id=course_id)\n        await neo4j_client.close()')
text = text.replace('surviving2, conflicts2 = await clean_graph_cycles(course_id, proposed)', 'surviving2, conflicts2 = await clean_graph_cycles(course_id, proposed)\n    await neo4j_client.close()')
with open('tests/test_prerequisite_pipeline.py', 'w', encoding='utf-8') as f: f.write(text)
