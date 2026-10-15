import pytest

async def _secure_cleanup(session, course_id: str):
    if course_id != "__test__":
        raise RuntimeError("SECURITY VULNERABILITY: Attempted to delete a non-test course!")
    await session.run("MATCH (n) WHERE n.course_id = $course_id OR n.id = $course_id DETACH DELETE n", course_id=course_id)

pytestmark = pytest.mark.integration



import asyncio
from app.db.neo4j_client import neo4j_client
from app.db.repo import save_ingestion_transaction
from scripts.run_pipeline import delete_document

@pytest.mark.asyncio
async def test_version_increments():
    await neo4j_client.connect()
    course_id = "__test__"
    
    try:
        async with neo4j_client.driver.session() as session:
            await session.run("MATCH (n {course_id: '__test__'}) DETACH DELETE n")

        # 1. ingest
        chunks = [{"id": "c1", "doc_id": "d1", "course_id": course_id, "index": 0, "page": 1, "heading_path": "H1", "char_start": 0, "char_end": 10, "text": "text", "text_unaccent": "text", "embedding": [0.1]*1024, "embedding_model": "test"}]
        concepts = []
        mentions = []
        
        res = await save_ingestion_transaction("d1", course_id, chunks, concepts, mentions)
        assert res["version"] == 1
        
        async with neo4j_client.driver.session() as session:
            rec = await session.run("MATCH (c:Course {id: $course_id}) RETURN c.version AS v", course_id=course_id)
            assert (await rec.single())["v"] == 1
            
        # 2. Re-ingest
        res = await save_ingestion_transaction("d1", course_id, chunks, concepts, mentions)
        assert res["version"] == 2
        
        async with neo4j_client.driver.session() as session:
            rec = await session.run("MATCH (c:Course {id: $course_id}) RETURN c.version AS v", course_id=course_id)
            assert (await rec.single())["v"] == 2
            
        # 3. DELETE
        await delete_document(course_id, "d1")
        
        async with neo4j_client.driver.session() as session:
            rec = await session.run("MATCH (c:Course {id: $course_id}) RETURN c.version AS v", course_id=course_id)
            assert (await rec.single())["v"] == 3
    finally:
        pass
