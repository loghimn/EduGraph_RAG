with open('app/db/repo.py', 'r', encoding='utf-8') as f: text = f.read()
import re
text = re.sub(
r"        return \{\"version\": 1\}",
"""        res = await tx.run(\"\"\"
        MERGE (course:Course {id: $course_id})
        SET course.version = coalesce(course.version, 0) + 1
        RETURN course.version AS version
        \"\"\", course_id=course_id)
        rec = await res.single()
        return {\"version\": rec[\"version\"] if rec else 1}""",
text
)
with open('app/db/repo.py', 'w', encoding='utf-8') as f: f.write(text)
