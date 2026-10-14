import asyncio
from app.db.neo4j_client import neo4j_client
from typing import List, Dict, Any

async def save_prerequisite_edges(course_id: str, edges: List[Dict[str, Any]]):
    query = """
    // 1. Delete AUTO edges that are no longer selected
    MATCH (s:Concept {course_id: $course_id})-[r:PREREQUISITE_OF {status: 'AUTO'}]->(t:Concept {course_id: $course_id})
    WHERE NOT any(e IN $edges WHERE e.source_id = s.id AND e.target_id = t.id)
    DELETE r
    WITH count(r) AS deleted_auto

    // 2. Upsert new edges
    UNWIND $edges AS edge
    MATCH (src:Concept {id: edge.source_id, course_id: $course_id})
    MATCH (tgt:Concept {id: edge.target_id, course_id: $course_id})
    MERGE (src)-[r:PREREQUISITE_OF]->(tgt)
    ON CREATE SET 
        r.status = 'AUTO',
        r.score = edge.score,
        r.source_type = edge.source_type,
        r.evidence = edge.evidence
    ON MATCH SET
        r.score = CASE WHEN r.status = 'AUTO' THEN edge.score ELSE r.score END,
        r.source_type = CASE WHEN r.status = 'AUTO' THEN edge.source_type ELSE r.source_type END,
        r.evidence = CASE WHEN r.status = 'AUTO' THEN edge.evidence ELSE r.evidence END
        
    WITH count(src) AS updated
    
    // 3. Increment Course version in same transaction
    MATCH (c:Course {id: $course_id})
    SET c.version = coalesce(c.version, 0) + 1
    """
    async with neo4j_client.driver.session() as session:
        await session.run(query, course_id=course_id, edges=edges)
