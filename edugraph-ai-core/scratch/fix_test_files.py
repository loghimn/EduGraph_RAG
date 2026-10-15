import re

for filename in ['tests/test_document_pipeline_status.py', 'tests/test_api_course_version.py']:
    with open(filename, 'r', encoding='utf-8') as f:
        text = f.read()
    
    text = text.replace("MERGE (c:Course {id: '__test__', owner_id: 'user1'})", "MERGE (c:Course {id: '__test__'}) SET c.owner_id = 'user1'")
    text = text.replace('"secret"', '"dummy_key"')
    
    with open(filename, 'w', encoding='utf-8') as f:
        f.write(text)
