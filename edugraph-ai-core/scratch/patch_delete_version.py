with open('scripts/run_pipeline.py', 'r', encoding='utf-8') as f: text = f.read()
text = text.replace('DETACH DELETE orphan\n    """', 'DETACH DELETE orphan\n    WITH count(*) AS _dummy\n    MERGE (course:Course {id: $course_id})\n    SET course.version = coalesce(course.version, 0) + 1\n    """')
with open('scripts/run_pipeline.py', 'w', encoding='utf-8') as f: f.write(text)
