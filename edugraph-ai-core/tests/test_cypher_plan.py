import pytest
pytestmark = pytest.mark.integration
from neo4j import AsyncGraphDatabase
from app.db.neo4j_client import neo4j_client

@pytest.mark.asyncio
async def test_cypher_plan_no_cartesian():
    # Câu query mới đã được tách thành 5 câu riêng rẽ, mình sẽ test 1 câu phức tạp nhất là MERGE mentions
    query_mentions = """
    EXPLAIN
    UNWIND $mentions AS m
    MATCH (chunk:Chunk {id: m.chunk_id})
    MATCH (concept:Concept {course_key: m.course_key})
    MERGE (chunk)-[r:MENTIONS {start: m.start, end: m.end}]->(concept)
    SET r.is_evidence = m.is_evidence, r.count = m.count
    """
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as session:
        result = await session.run(query_mentions, mentions=[])
        summary = await result.consume()
        
        def search_plan(node):
            res = [node.get("operatorType")]
            for child in node.get("children", []):
                res.extend(search_plan(child))
            return res
            
        ops = search_plan(summary.plan)
        # Bắt buộc không được có CartesianProduct
        assert "CartesianProduct" not in ops, f"Found CartesianProduct in execution plan! Ops: {ops}"
        
    # await neo4j_client.close()


@pytest.mark.asyncio
async def test_save_prereq_edges_no_cartesian():
    from app.prerequisite.pipeline import save_prerequisite_edges
    
    query = """EXPLAIN
    MATCH (s:Concept {course_id: $course_id})-[r:PREREQUISITE_OF {status: 'AUTO'}]->(t:Concept {course_id: $course_id})
    WHERE (t.id IN $rescored_ids AND NOT any(e IN $edges WHERE e.source_id = s.id AND e.target_id = t.id))
       OR any(ce IN $cut_edges WHERE ce.source_id = s.id AND ce.target_id = t.id)
    DELETE r
    WITH count(r) AS deleted_auto

    UNWIND $edges AS edge
    MATCH (src:Concept {id: edge.source_id, course_id: $course_id})
    CALL (edge) {
        MATCH (tgt:Concept {id: edge.target_id, course_id: $course_id})
        RETURN tgt
    }
    MERGE (src)-[r:PREREQUISITE_OF]->(tgt)
    ON CREATE SET r.status = 'AUTO'
    WITH count(src) AS updated
    
    MATCH (c:Course {id: $course_id})
    SET c.version = coalesce(c.version, 0) + 1
    """
    
    from app.db.neo4j_client import neo4j_client
    async with neo4j_client.driver.session() as session:
        result = await session.run(query, course_id='__test__', edges=[], rescored_ids=[], cut_edges=[])
        summary = await result.consume()
        
        def search_plan(node):
            res = [node.get("operatorType")]
            for child in node.get("children", []):
                res.extend(search_plan(child))
            return res
            
        operators = search_plan(summary.plan)
        
        for op in operators:
            assert 'CartesianProduct' not in str(op), f"Query contains CartesianProduct: {op}"
