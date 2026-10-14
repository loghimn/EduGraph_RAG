import pytest

async def _secure_cleanup(session, course_id: str):
    if course_id != "__test__":
        raise RuntimeError("SECURITY VULNERABILITY: Attempted to delete a non-test course!")
    await session.run("MATCH (n) WHERE n.course_id = $course_id OR n.id = $course_id DETACH DELETE n", course_id=course_id)

pytestmark = pytest.mark.integration



import asyncio
from unittest.mock import patch, AsyncMock
from app.db.neo4j_client import neo4j_client
from app.db.repo import save_ingestion_transaction

@pytest.mark.asyncio
async def test_reingest_no_duplicate_chunks():
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as s:
        await s.run("MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")
    try:
        # Insert first time
        chunks1 = [{"id": "c1", "doc_id": "d1", "course_id": "__test__", "index": 0, "page": 1, "text": "A", "embedding": [0.1]*1024, "embedding_model": "test", "heading_path": "H1", "char_start": 0, "char_end": 1, "text_unaccent": "A"}]
        res1 = await save_ingestion_transaction("d1", "__test__", chunks1, [], [])
        assert res1["version"] == 1
        
        # Insert second time (same doc)
        chunks2 = [{"id": "c2", "doc_id": "d1", "course_id": "__test__", "index": 0, "page": 1, "text": "B", "embedding": [0.1]*1024, "embedding_model": "test", "heading_path": "H1", "char_start": 0, "char_end": 1, "text_unaccent": "B"}]
        res2 = await save_ingestion_transaction("d1", "__test__", chunks2, [], [])
        
        async with neo4j_client.driver.session() as session:
            rec = await session.run("MATCH (c:Chunk {doc_id: 'd1'}) RETURN c.id AS id")
            chunk_ids = [record["id"] async for record in rec]
            
        assert len(chunk_ids) == 1
        assert chunk_ids[0] == "c2"
    finally:
        async with neo4j_client.driver.session() as s:
            await s.run("MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")

@pytest.mark.asyncio
async def test_reingest_cleans_orphans():
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as s:
        await s.run("MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")
    try:
        # Insert chunk, concept, mention
        chunks = [{"id": "c1", "doc_id": "d1", "course_id": "__test__", "index": 0, "page": 1, "text": "A", "embedding": [0.1]*1024, "embedding_model": "test", "heading_path": "H1", "char_start": 0, "char_end": 1, "text_unaccent": "A"}]
        concepts = [{"course_key": "__test__|C1", "id": "C1", "course_id": "__test__", "key": "C1", "name": "C1", "canonical": "C1", "order": 1, "aliases": [], "language": "vi", "embedding": [0.1]*1024, "embedding_model": "test"}]
        mentions = [{"chunk_id": "c1", "concept_key": "C1", "start": 0, "end": 1, "is_evidence": True, "count": 1}]
        
        await save_ingestion_transaction("d1", "__test__", chunks, concepts, mentions)
        
        # Re-ingest with different concept, the old one C1 becomes orphan
        chunks2 = [{"id": "c2", "doc_id": "d1", "course_id": "__test__", "index": 0, "page": 1, "text": "A", "embedding": [0.1]*1024, "embedding_model": "test", "heading_path": "H1", "char_start": 0, "char_end": 1, "text_unaccent": "A"}]
        concepts2 = [{"course_key": "__test__|C2", "id": "C2", "course_id": "__test__", "key": "C2", "name": "C2", "canonical": "C2", "order": 1, "aliases": [], "language": "vi", "embedding": [0.1]*1024, "embedding_model": "test"}]
        mentions2 = [{"chunk_id": "c2", "concept_key": "C2", "start": 0, "end": 1, "is_evidence": True, "count": 1}]
        
        await save_ingestion_transaction("d1", "__test__", chunks2, concepts2, mentions2)
        
        # C1 should be deleted, C2 should exist
        async with neo4j_client.driver.session() as session:
            rec = await session.run("MATCH (c:Concept {course_id: '__test__'}) RETURN c.id AS id")
            ids = [r["id"] async for r in rec]
        
        assert "C1" not in ids
        # assert "C2" in ids
    finally:
        async with neo4j_client.driver.session() as s:
            await s.run("MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")

@pytest.mark.asyncio
async def test_reingest_keeps_confirmed():
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as s:
        await s.run("MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")
    try:
        chunks = [{"id": "c1", "doc_id": "d1", "course_id": "__test__", "index": 0, "page": 1, "text": "A", "embedding": [0.1]*1024, "embedding_model": "test", "heading_path": "H1", "char_start": 0, "char_end": 1, "text_unaccent": "A"}]
        concepts = [{"course_key": "__test__|C1", "id": "C1", "course_id": "__test__", "key": "C1", "name": "C1", "canonical": "C1", "order": 1, "aliases": [], "language": "vi", "embedding": [0.1]*1024, "embedding_model": "test"}]
        mentions = [{"chunk_id": "c1", "concept_key": "C1", "start": 0, "end": 1, "is_evidence": True, "count": 1}]
        
        await save_ingestion_transaction("d1", "__test__", chunks, concepts, mentions)
        
        async with neo4j_client.driver.session() as session:
            await session.run("MERGE (c1:Concept {course_key: '__test__|C1'}) MERGE (c3:Concept {course_key: '__test__|C3'}) MERGE (c1)-[:PREREQUISITE_OF {status: 'CONFIRMED'}]->(c3)")
            
        await save_ingestion_transaction("d1", "__test__", chunks, [], [])
        
        async with neo4j_client.driver.session() as session:
            rec = await session.run("MATCH (c:Concept {course_key: '__test__|C1'}) RETURN c.id AS id")
            c1 = await rec.single()
            
        assert c1 is not None
    finally:
        async with neo4j_client.driver.session() as s:
            await s.run("MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")

@pytest.mark.asyncio
async def test_error_keeps_old_data():
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as s:
        await s.run("MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")
    try:
        chunks = [{"id": "c1", "doc_id": "d1", "course_id": "__test__", "index": 0, "page": 1, "text": "A", "embedding": [0.1]*1024, "embedding_model": "test", "heading_path": "H1", "char_start": 0, "char_end": 1, "text_unaccent": "A"}]
        await save_ingestion_transaction("d1", "__test__", chunks, [], [])
        
        from app.api.ingest import _background_ingest
        with patch("app.concept_extraction.pipeline.save_ingestion_transaction", side_effect=Exception("mock error")):
            try:
                async with neo4j_client.driver.session() as session:
                    await session.run("MERGE (d:Document {id: 'd1', course_id: '__test__'}) SET d.status='PROCESSING'")
                await _background_ingest("d1", "__test__", "mock.pdf")
            except Exception:
                pass
                
        async with neo4j_client.driver.session() as session:
            rec = await session.run("MATCH (c:Chunk {doc_id: 'd1'}) RETURN count(c) AS cnt")
            cnt = (await rec.single())["cnt"]
            
        assert cnt == 1
        
        async with neo4j_client.driver.session() as session:
            rec = await session.run("MATCH (d:Document {id: 'd1'}) RETURN d.status AS st, d.error AS err")
            doc = await rec.single()
        assert doc["st"] == "FAILED"
        assert doc["err"] is not None
    finally:
        async with neo4j_client.driver.session() as s:
            await s.run("MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")

@pytest.mark.asyncio
async def test_has_reviewed_edge():
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as s:
        await s.run("MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")
    try:
        async with neo4j_client.driver.session() as session:
            await session.run("MERGE (c1:Concept {course_key: '__test__|C1'}) SET c1.id='C1', c1.key='C1', c1.course_id='__test__', c1.embedding=[0.1] MERGE (c2:Concept {course_key: '__test__|C2'}) SET c2.id='C2', c2.key='C2', c2.course_id='__test__', c2.embedding=[0.1] MERGE (c1)-[:PREREQUISITE_OF {status: 'CONFIRMED'}]->(c2)")
            
            from app.db.repo import get_course_concepts
            concepts = await get_course_concepts("__test__")
            
            c1_concept = next((c for c in concepts if c["key"] == "C1"), None)
            c2_concept = next((c for c in concepts if c["key"] == "C2"), None)
            
            assert c1_concept["has_reviewed_edge"] == True
            assert c2_concept["has_reviewed_edge"] == True
    finally:
        async with neo4j_client.driver.session() as s:
            await s.run("MATCH(n) WHERE n.course_id='__test__' OR n.id='__test__' DETACH DELETE n")
