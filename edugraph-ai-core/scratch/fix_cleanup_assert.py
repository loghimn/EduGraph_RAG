import glob
def fix_cleanup(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    old_code = 'assert course_id == "__test__", "SECURITY VULNERABILITY: Attempted to delete a non-test course!"'
    new_code = 'if course_id != "__test__":\n        raise RuntimeError("SECURITY VULNERABILITY: Attempted to delete a non-test course!")'
    
    if old_code in content:
        content = content.replace(old_code, new_code)
        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Patched {path}")

for tf in glob.glob('tests/*.py'):
    fix_cleanup(tf)
