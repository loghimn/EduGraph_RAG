import glob
import re

secure_cleanup_func = """
async def _secure_cleanup(session, course_id: str):
    if course_id != "__test__":
        raise RuntimeError("SECURITY VULNERABILITY: Attempted to delete a non-test course!")
    await session.run("MATCH (n) WHERE n.course_id = $course_id OR n.id = $course_id DETACH DELETE n", course_id=course_id)
"""

for tf in ['tests/test_integration_reingest.py', 'tests/test_version_increment.py', 'tests/test_prerequisite_pipeline.py']:
    with open(tf, 'r', encoding='utf-8') as f:
        content = f.read()

    # 1. Remove old assert-based _secure_cleanup if exists
    content = re.sub(r'async def _secure_cleanup.*?DETACH DELETE n", course_id=course_id\)', '', content, flags=re.DOTALL)
    
    # 2. Inject new one
    content = content.replace('import pytest\n', 'import pytest\n' + secure_cleanup_func + '\n')
    
    with open(tf, 'w', encoding='utf-8') as f:
        f.write(content)
