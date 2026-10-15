with open('tests/test_prerequisite_pipeline.py', 'r', encoding='utf-8') as f: text = f.read()
import re
text = re.sub(r' *await neo4j_client\.close\(\)\n', '', text)
text = text.replace('@pytest.mark.asyncio', '@pytest.mark.asyncio(loop_scope="module")')
with open('tests/test_prerequisite_pipeline.py', 'w', encoding='utf-8') as f: f.write(text)
