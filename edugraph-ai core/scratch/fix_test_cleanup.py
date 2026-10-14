import os
import glob
import re

secure_cleanup_func = """
async def _secure_cleanup(session, course_id: str):
    assert course_id == "__test__", "SECURITY VULNERABILITY: Attempted to delete a non-test course!"
    await session.run("MATCH (n) WHERE n.course_id = $course_id OR n.id = $course_id DETACH DELETE n", course_id=course_id)
"""

test_files = glob.glob('tests/*.py')
for tf in test_files:
    with open(tf, 'r', encoding='utf-8') as f:
        content = f.read()

    changed = False

    if 'DETACH DELETE' in content:
        # replace course_ids to __test__
        content = content.replace('__test_ver__', '__test__')
        
        # We need to replace all await s.run("MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")
        # and similar variations.
        
        # regex to match: await session.run("MATCH... DETACH DELETE n"...)
        content = re.sub(r'await\s+[a-zA-Z0-9_]+\.run\(\s*["\']MATCH[^"\']*DETACH DELETE n["\'][^)]*\)', 'await _secure_cleanup(s if \'s\' in locals() else session, \"__test__\")', content)
        
        # inject the helper if not there
        if '_secure_cleanup' not in content:
            content = content.replace('import pytest', 'import pytest' + secure_cleanup_func)
        changed = True
        
    if changed:
        with open(tf, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Patched {tf}")
