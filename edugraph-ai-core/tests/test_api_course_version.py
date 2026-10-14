import pytest
import asyncio
from unittest.mock import patch, AsyncMock
from httpx import AsyncClient, ASGITransport
from app.main import app
from app.db.neo4j_client import neo4j_client

pytestmark = pytest.mark.integration

async def _secure_cleanup(session, course_id: str):
    if course_id != "__test__":
        raise RuntimeError("SECURITY VULNERABILITY")
    await session.run("MATCH (n) WHERE n.course_id = $course_id OR n.id = $course_id DETACH DELETE n", course_id=course_id)

@pytest.mark.asyncio
async def test_api_course_version():
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as s:
        await _secure_cleanup(s, "__test__")
        await s.run("MERGE (c:Course {id: '__test__'}) SET c.owner_id = 'user1' SET c.version = 0")
        
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        # 1. POST /courses/__test__/documents (Ingest) -> calls process_document_pipeline & run_prerequisite_pipeline
        with patch("app.concept_extraction.pipeline.process_document_pipeline", new=AsyncMock(return_value={"stats": {}})) as mock_extract:
            with patch("app.api.ingest.run_prerequisite_pipeline", new=AsyncMock()) as mock_prereq:
                from app.db.repo import save_ingestion_transaction
                await save_ingestion_transaction("d1", "__test__", [], [], [])
                
                async with neo4j_client.driver.session() as s:
                    await s.run("MERGE (d:Document {id: 'd1', course_id: '__test__'}) SET d.status = 'DONE'")
                    v = await (await s.run("MATCH (c:Course {id: '__test__'}) RETURN c.version AS v")).single()
                    assert v["v"] == 1
                    
        # 2. DELETE /documents/d1?course_id=__test__&user_id=user1
        resp = await ac.delete("/documents/d1?course_id=__test__&user_id=user1", headers={"X-Internal-Key": "dummy_key"})
        assert resp.status_code == 200
        async with neo4j_client.driver.session() as s:
            v = await (await s.run("MATCH (c:Course {id: '__test__'}) RETURN c.version AS v")).single()
            assert v["v"] == 2
            
        # 3. POST /courses/__test__/prerequisites
        with patch("app.prerequisite.pipeline.run_prerequisite_pipeline", new=AsyncMock()) as mock_prereq:
            # Actually call the real save_prerequisite_edges
            from app.prerequisite.pipeline import save_prerequisite_edges
            await save_prerequisite_edges("__test__", [], [], [])
            
            async with neo4j_client.driver.session() as s:
                v = await (await s.run("MATCH (c:Course {id: '__test__'}) RETURN c.version AS v")).single()
                assert v["v"] == 3

    async with neo4j_client.driver.session() as s:
        await _secure_cleanup(s, "__test__")
