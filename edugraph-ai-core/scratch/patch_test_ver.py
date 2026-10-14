with open('tests/test_version_increment.py', 'r', encoding='utf-8') as f: text = f.read()
text = text.replace('@pytest.fixture(scope="module")', 'import pytest_asyncio\n@pytest_asyncio.fixture(scope="module")')
text = text.replace('@pytest.fixture(autouse=True)', '@pytest_asyncio.fixture(autouse=True)')
with open('tests/test_version_increment.py', 'w', encoding='utf-8') as f: f.write(text)
