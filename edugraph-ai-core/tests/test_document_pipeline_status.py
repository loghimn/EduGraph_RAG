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
async def test_document_pipeline_status():
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as s:
        await _secure_cleanup(s, "__test__")
        await s.run("MERGE (c:Course {id: '__test__'}) SET c.owner_id = 'user1'")
        
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        # 1. Test failure in prereq -> FAILED with error
        async with neo4j_client.driver.session() as s:
            await s.run("MERGE (d:Document {id: 'doc1', course_id: '__test__'}) SET d.status = 'UPLOADED'")

        with patch("app.concept_extraction.pipeline.process_document_pipeline", new=AsyncMock()) as mock_extract:
            with patch("app.prerequisite.pipeline.run_prerequisite_pipeline", new=AsyncMock(side_effect=Exception("Prereq Error"))) as mock_prereq:
                resp = await ac.post("/ingest", headers={"X-Internal-Key": "test-internal-api-key"}, json={
                    "document_id": "doc1", "course_id": "__test__", "user_id": "user1", "filename": "test.pdf"
                })
                assert resp.status_code == 200
                assert resp.json()["status"] == "processing"
                
                # Chờ background task cập nhật
                await asyncio.sleep(0.5)
                
                # Kiểm tra trạng thái DB: FAILED
                async with neo4j_client.driver.session() as s:
                    doc = await (await s.run("MATCH (d:Document {id: 'doc1'}) RETURN d.status AS st, d.error AS err")).single()
                    assert doc["st"] == "FAILED"
                    assert "Prereq Error" in doc["err"]
                    
        # 2. Test success -> DONE
        async with neo4j_client.driver.session() as s:
            await s.run("MERGE (d:Document {id: 'doc2', course_id: '__test__'}) SET d.status = 'UPLOADED'")

        with patch("app.concept_extraction.pipeline.process_document_pipeline", new=AsyncMock()) as mock_extract:
            with patch("app.prerequisite.pipeline.run_prerequisite_pipeline", new=AsyncMock()) as mock_prereq:
                resp = await ac.post("/ingest", headers={"X-Internal-Key": "test-internal-api-key"}, json={
                    "document_id": "doc2", "course_id": "__test__", "user_id": "user1", "filename": "test.pdf"
                })
                assert resp.status_code == 200
                assert resp.json()["status"] == "processing"
                
                await asyncio.sleep(0.5)
                
                async with neo4j_client.driver.session() as s:
                    doc = await (await s.run("MATCH (d:Document {id: 'doc2'}) RETURN d.status AS st, d.error AS err")).single()
                    assert doc["st"] == "DONE"
                    
    async with neo4j_client.driver.session() as s:
        await _secure_cleanup(s, "__test__")
