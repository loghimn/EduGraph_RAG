import pytest
import asyncio
from app.db.neo4j_client import neo4j_client
from app.prerequisite.pipeline import save_prerequisite_edges

pytestmark = pytest.mark.integration

async def _secure_cleanup(session, course_id: str):
    if course_id != "__test__":
        raise RuntimeError("SECURITY VULNERABILITY")
    await session.run("MATCH (n) WHERE n.course_id = $course_id OR n.id = $course_id DETACH DELETE n", course_id=course_id)

@pytest.fixture(scope="module")
def event_loop():
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()

@pytest.mark.asyncio
async def test_save_prerequisite_edges_rules():
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as s:
        await _secure_cleanup(s, "__test__")
        
        await s.run("MERGE (c:Course {id: '__test__', version: 0})")
        for x in ["A", "B", "C", "D", "E"]:
            await s.run("MERGE (c:Concept {id: $id, course_id: '__test__'})", id=x)
            
        # A->B is CONFIRMED
        await s.run("MATCH (a:Concept {id: 'A'}), (b:Concept {id: 'B'}) MERGE (a)-[r:PREREQUISITE_OF]->(b) SET r.status = 'CONFIRMED', r.score = 0.5")
        # B->C is AUTO
        await s.run("MATCH (a:Concept {id: 'B'}), (b:Concept {id: 'C'}) MERGE (a)-[r:PREREQUISITE_OF]->(b) SET r.status = 'AUTO', r.score = 0.5")
        # C->D is AUTO (to be deleted because target D is rescored but not in edges)
        await s.run("MATCH (a:Concept {id: 'C'}), (b:Concept {id: 'D'}) MERGE (a)-[r:PREREQUISITE_OF]->(b) SET r.status = 'AUTO', r.score = 0.5")
        # D->E is AUTO (to be cut)
        await s.run("MATCH (a:Concept {id: 'D'}), (b:Concept {id: 'E'}) MERGE (a)-[r:PREREQUISITE_OF]->(b) SET r.status = 'AUTO', r.score = 0.5")
        
    new_edges = [
        {"source_id": "A", "target_id": "B", "score": 0.9, "source": "LLM", "evidence": "new A->B", "llm_score": 0.9, "rule_score": 0, "evidence_chunk_id": "", "model": "", "prompt_version": ""}, 
        {"source_id": "B", "target_id": "C", "score": 0.9, "source": "LLM", "evidence": "new B->C", "llm_score": 0.9, "rule_score": 0, "evidence_chunk_id": "", "model": "", "prompt_version": ""} 
    ]
    
    rescored_ids = ["B", "C", "D"] # Target D was rescored, but no edges to it are in new_edges! Target E was NOT rescored.
    cut_edges = [{"source_id": "D", "target_id": "E"}]
    
    await save_prerequisite_edges("__test__", new_edges, rescored_ids, cut_edges)
    
    async with neo4j_client.driver.session() as s:
        # Check A->B
        ab = await (await s.run("MATCH (:Concept {id: 'A'})-[r:PREREQUISITE_OF]->(:Concept {id: 'B'}) RETURN r.status AS st, r.score AS sc")).single()
        assert ab["st"] == "CONFIRMED"
        assert ab["sc"] == 0.5 # Kept old score
        
        # Check B->C
        bc = await (await s.run("MATCH (:Concept {id: 'B'})-[r:PREREQUISITE_OF]->(:Concept {id: 'C'}) RETURN r.status AS st, r.score AS sc, r.source AS src")).single()
        assert bc["st"] == "AUTO"
        assert bc["sc"] == 0.9 # Updated score
        assert bc["src"] == "LLM"
        
        # Check C->D (Should be deleted!)
        cd = await (await s.run("MATCH (:Concept {id: 'C'})-[r:PREREQUISITE_OF]->(:Concept {id: 'D'}) RETURN r")).single()
        assert cd is None
        
        # Check D->E (Should be deleted!)
        de = await (await s.run("MATCH (:Concept {id: 'D'})-[r:PREREQUISITE_OF]->(:Concept {id: 'E'}) RETURN r")).single()
        assert de is None
        
        v = await (await s.run("MATCH (c:Course {id: '__test__'}) RETURN c.version")).single()
        assert v["c.version"] == 1
        
    # Empty edges test
    await save_prerequisite_edges("__test__", [], [], [])
    async with neo4j_client.driver.session() as s:
        v = await (await s.run("MATCH (c:Course {id: '__test__'}) RETURN c.version")).single()
        assert v["c.version"] == 2
        await _secure_cleanup(s, "__test__")
