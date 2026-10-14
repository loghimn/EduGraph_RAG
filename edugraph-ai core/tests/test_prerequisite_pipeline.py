import pytest

async def _secure_cleanup(session, course_id: str):
    if course_id not in ("__test__", "__test_prereq__"):
        raise RuntimeError("SECURITY VULNERABILITY: Attempted to delete a non-test course!")
    await session.run("MATCH (n) WHERE n.course_id = $course_id OR n.id = $course_id DETACH DELETE n", course_id=course_id)

pytestmark = pytest.mark.integration

import asyncio
from unittest.mock import patch, AsyncMock
from app.db.neo4j_client import neo4j_client
from app.prerequisite.pipeline import run_prerequisite_pipeline
from app.prerequisite.graph_cleaner import clean_graph_cycles

@pytest.fixture(scope="module")
def event_loop():
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()

@pytest.mark.asyncio
async def test_prerequisite_pipeline_full():
    await neo4j_client.connect()
    
    # 1. T\u1ea1o m\u1ed9t \u0111\u1ed3 th\u1ecb fake trong DB
    course_id = "__test_prereq__"
    async with neo4j_client.driver.session() as session:
        await _secure_cleanup(session, course_id)
        
        # T\u1ea1o Course v\u00e0 4 concepts: A, B, C, D
        await session.run("""
        MERGE (c:Course {id: $course_id}) SET c.version = 1
        MERGE (c1:Concept {id: 'C1', course_id: $course_id, name: 'Derivative', embedding: $emb1})
        MERGE (c2:Concept {id: 'C2', course_id: $course_id, name: 'Gradient', embedding: $emb2})
        MERGE (c3:Concept {id: 'C3', course_id: $course_id, name: 'Calculus', embedding: $emb3})
        MERGE (c4:Concept {id: 'C4', course_id: $course_id, name: 'Hallucinated', embedding: $emb4})
        
        // Th\u00eam 1 ch\u00fat evidence \u0111\u1ec3 rule_signals ch\u1ea1y \u0111\u01b0\u1ee3c
        MERGE (ch:Chunk {id: 'chunk1', course_id: $course_id, is_evidence: true, text: 'Before learning Gradient, you need Derivative.'})
        MERGE (ch)-[:MENTIONS]->(c1)
        MERGE (ch)-[:MENTIONS]->(c2)
        
        // C\u1ea1nh REJECTED: Calculus -> Derivative
        MERGE (c3)-[:PREREQUISITE_OF {status: 'REJECTED'}]->(c1)
        
        // C\u1ea1nh CONFIRMED v\u00f2ng l\u1eb7p c\u1ee9ng \u0111\u1ec3 test: C2 -> C3 -> C2 (Hai h\u01b0\u1edbng)
        MERGE (c2)-[:PREREQUISITE_OF {status: 'CONFIRMED'}]->(c3)
        MERGE (c3)-[:PREREQUISITE_OF {status: 'MANUAL'}]->(c2)
        
        // C\u1ea1nh AUTO c\u0169 s\u1ebd b\u1ecb x\u00f3a: C4 -> C1
        MERGE (c4)-[:PREREQUISITE_OF {status: 'AUTO', score: 0.8}]->(c1)
        
        """, course_id=course_id, emb1=[0.1]*1024, emb2=[0.1]*1024, emb3=[0.1]*1024, emb4=[0.1]*1024)

    # 2. Mock LLM
    async def mock_generate(prompt, version, data, json_mode):
        target = data["target"]
        if target == "Gradient":
            # \u0110\u1ec1 xu\u1ea5t \u0111\u00fang c\u1ea1nh h\u1ee3p l\u1ec7 (Derivative l\u00e0 ti\u00ean quy\u1ebft c\u1ee7a Gradient)
            # V\u00e0 1 c\u1ea1nh t\u00ean fake (b\u1ecba t\u00ean)
            return {"prerequisites": [
                {"name": "Derivative", "confidence": 0.9, "evidence": "Needs deriv"},
                {"name": "FakeName", "confidence": 0.9, "evidence": "Fake"}
            ]}
        elif target == "Derivative":
            # \u0110\u1ec1 xu\u1ea5t c\u1ea1nh m\u00e0 \u0111\u00e3 b\u1ecb REJECTED (Calculus -> Derivative).
            # M\u1eb7c d\u00f9 th\u1ef1c t\u1ebf n\u00f3 kh\u00f4ng c\u00f3 trong \u1ee9ng vi\u00ean do \u0111\u00e3 b\u1ecb l\u1ecdc, n\u1ebfu LLM v\u1eabn c\u1ed1 \u0111\u00e1nh th\u00ec s\u1ebd b\u1ecb lo\u1ea1i!
            return {"prerequisites": [{"name": "Calculus", "confidence": 0.9}]}
        return {"prerequisites": []}

    with patch("app.prerequisite.llm_judge.llm_client.generate", new=AsyncMock(side_effect=mock_generate)):
        # Run pipeline
        await run_prerequisite_pipeline(course_id)
        
    # 3. Ki\u1ec3m tra k\u1ebft qu\u1ea3 DB
    async with neo4j_client.driver.session() as session:
        # C\u1ea1nh REJECTED Calculus -> Derivative ph\u1ea3i C\u00d2N NGUY\u00caN
        res = await session.run("MATCH (c3 {id: 'C3'})-[r:PREREQUISITE_OF]->(c1 {id: 'C1'}) RETURN r.status AS s")
        assert (await res.single())["s"] == "REJECTED"
        
        # C\u1ea1nh AUTO c\u0169 (C4 -> C1) ph\u1ea3i B\u1eca X\u00d3A (v\u00ec LLM kh\u00f4ng ch\u1ecdn l\u1ea1i)
        res = await session.run("MATCH (c4 {id: 'C4'})-[r:PREREQUISITE_OF]->(c1 {id: 'C1'}) RETURN count(r) AS c")
        assert (await res.single())["c"] == 0
        
        # C\u1ea1nh m\u1edbi Derivative -> Gradient (AUTO) ph\u1ea3i \u0111\u01b0\u1ee3c t\u1ea1o
        res = await session.run("MATCH (c1 {id: 'C1'})-[r:PREREQUISITE_OF]->(c2 {id: 'C2'}) RETURN r.status AS s, r.source_type AS st, r.score AS sc")
        record = await res.single()
        assert record is not None
        assert record["s"] == "AUTO"
        assert record["st"] == "HYBRID" # v\u00ec c\u00f3 match regex "Before learning Gradient, you need Derivative"
        assert record["sc"] > 0.7 # W_LLM(0.8) * 0.9 + W_RULE(0.2) * 0.5 = 0.82
        
        # V\u00f2ng l\u1eb7p CONFIRMED / MANUAL kh\u00f4ng \u0111\u01b0\u1ee3c c\u1eaft
        res = await session.run("MATCH (c2 {id: 'C2'})-[r:PREREQUISITE_OF]->(c3 {id: 'C3'}) RETURN r.status AS s")
        assert (await res.single())["s"] == "CONFIRMED"
        res = await session.run("MATCH (c3 {id: 'C3'})-[r:PREREQUISITE_OF]->(c2 {id: 'C2'}) RETURN r.status AS s")
        assert (await res.single())["s"] == "MANUAL"
        
        # Version \u0111\u00e3 \u0111\u01b0\u1ee3c t\u0103ng
        res = await session.run("MATCH (c:Course {id: $course_id}) RETURN c.version AS v", course_id=course_id)
        assert (await res.single())["v"] == 2
        
        # Clean up
        await _secure_cleanup(session, course_id)

@pytest.mark.asyncio
async def test_clean_graph_cycles_deterministic():
    # \u0110\u1ea3m b\u1ea3o ch\u1ea1y nhi\u1ec1u l\u1ea7n lu\u00f4n c\u1eaft c\u00f9ng 1 c\u1ea1nh
    proposed = [
        {"source_id": "A", "target_id": "B", "score": 0.8, "source_type": "LLM", "evidence": ""},
        {"source_id": "B", "target_id": "C", "score": 0.9, "source_type": "LLM", "evidence": ""},
        {"source_id": "C", "target_id": "A", "score": 0.8, "source_type": "LLM", "evidence": ""}
    ]
    
    # A->B v\u00e0 C->A \u0111\u1ec1u c\u00f3 score 0.8 (th\u1ea5p nh\u1ea5t).
    # Lu\u1eadt weakest: sort(score, u, v).
    # A->B c\u00f3 u='A', v='B'. C->A c\u00f3 u='C', v='A'.
    # 'A' < 'C', n\u00ean A->B s\u1ebd l\u00e0 weakest v\u00e0 b\u1ecb c\u1eaft \u0111\u1ea7u ti\u00ean!
    
    await neo4j_client.connect()
    # Kh\u00f4ng c\u1ea7n d\u1eef li\u1ec7u th\u1eadt, ch\u1ec9 pass course r\u1ed7ng \u0111\u1ec3 db edge = empty
    course_id = "__empty__"
    
    surviving1, conflicts1, cut1 = await clean_graph_cycles(course_id, proposed)
    surviving2, conflicts2, cut2 = await clean_graph_cycles(course_id, proposed)
    
    assert len(surviving1) == 2
    assert len(surviving2) == 2
    # C\u1ea1nh b\u1ecb lo\u1ea1i l\u00e0 A->B
    assert all(e["source_id"] != "A" for e in surviving1)
    
    assert surviving1 == surviving2
