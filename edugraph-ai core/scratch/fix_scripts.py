import re
import glob

def fix_script(path):
    with open(path, 'r', encoding='utf-8') as f:
        text = f.read()
    
    text = re.sub(r'course_id:\s*str\s*=\s*"demo-sql"', 'course_id: str = "__test__"', text)
    text = re.sub(r'doc_id:\s*str\s*=\s*"demo-sql-doc.*?"', 'doc_id: str = "__test__"', text)
    text = re.sub(r'course_id="demo-sql"', 'course_id="__test__"', text)
    text = re.sub(r'doc_id="demo-sql-doc.*?"', 'doc_id="__test__"', text)
    text = re.sub(r'doc_id="doc-test-123"', 'doc_id="__test__"', text)
    
    with open(path, 'w', encoding='utf-8') as f:
        f.write(text)

fix_script('scripts/run_pipeline.py')
for f in glob.glob('scripts/*.py'):
    fix_script(f)
