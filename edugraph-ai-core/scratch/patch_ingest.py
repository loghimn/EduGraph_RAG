with open('app/api/ingest.py', 'r', encoding='utf-8') as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    if "await process_document_pipeline(doc_id, course_id, filename)" in line:
        new_lines.append(line)
        new_lines.append("        from app.concept_extraction.prerequisite_pipeline import run_prerequisite_pipeline\n")
        new_lines.append("        await run_prerequisite_pipeline(course_id)\n")
        new_lines.append("        query = \"MATCH (d:Document {id: $doc_id, course_id: $course_id}) SET d.status = 'DONE', d.updated_at = timestamp()\"\n")
        new_lines.append("        async with neo4j_client.driver.session() as session:\n")
        new_lines.append("            await session.run(query, doc_id=doc_id, course_id=course_id)\n")
    else:
        new_lines.append(line)

with open('app/api/ingest.py', 'w', encoding='utf-8') as f:
    f.writelines(new_lines)
