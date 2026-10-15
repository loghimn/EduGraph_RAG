import re

with open('app/prerequisite/pipeline.py', 'r', encoding='utf-8') as f:
    text = f.read()

old_func_pattern = r'async def save_prerequisite_edges\(course_id: str, edges: List\[Dict\[str, Any\]\]\):.*?(?=\n\w|\Z)'
# Wait, it might be safer to just replace from 'async def save_prerequisite_edges' to the end of the file since it's the last function.
# Let's just find the index.

idx = text.find('async def save_prerequisite_edges')
if idx != -1:
    text = text[:idx]

new_func = '''async def save_prerequisite_edges(course_id: str, edges: List[Dict[str, Any]], rescored_ids: List[str], cut_edges: List[Dict[str, str]]):
    query = """
    // 1. Delete AUTO edges that are no longer selected or were cut
    MATCH (s:Concept {course_id: $course_id})-[r:PREREQUISITE_OF {status: 'AUTO'}]->(t:Concept {course_id: $course_id})
    WHERE (t.id IN $rescored_ids AND NOT any(e IN $edges WHERE e.source_id = s.id AND e.target_id = t.id))
       OR any(ce IN $cut_edges WHERE ce.source_id = s.id AND ce.target_id = t.id)
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
        r.llm_score = coalesce(edge.llm_score, 0),
        r.rule_score = coalesce(edge.rule_score, 0),
        r.source = edge.source,
        r.evidence = edge.evidence,
        r.evidence_chunk_id = edge.evidence_chunk_id,
        r.model = edge.model,
        r.prompt_version = edge.prompt_version
    ON MATCH SET
        r.score = CASE WHEN r.status = 'AUTO' THEN edge.score ELSE r.score END,
        r.llm_score = CASE WHEN r.status = 'AUTO' THEN coalesce(edge.llm_score, 0) ELSE r.llm_score END,
        r.rule_score = CASE WHEN r.status = 'AUTO' THEN coalesce(edge.rule_score, 0) ELSE r.rule_score END,
        r.source = CASE WHEN r.status = 'AUTO' THEN edge.source ELSE r.source END,
        r.evidence = CASE WHEN r.status = 'AUTO' THEN edge.evidence ELSE r.evidence END,
        r.evidence_chunk_id = CASE WHEN r.status = 'AUTO' THEN edge.evidence_chunk_id ELSE r.evidence_chunk_id END,
        r.model = CASE WHEN r.status = 'AUTO' THEN edge.model ELSE r.model END,
        r.prompt_version = CASE WHEN r.status = 'AUTO' THEN edge.prompt_version ELSE r.prompt_version END
        
    WITH count(src) AS updated
    
    // 3. Increment Course version in same transaction
    MATCH (c:Course {id: $course_id})
    SET c.version = coalesce(c.version, 0) + 1
    """
    async with neo4j_client.driver.session() as session:
        await session.run(query, course_id=course_id, edges=edges, rescored_ids=rescored_ids, cut_edges=cut_edges)
'''

with open('app/prerequisite/pipeline.py', 'w', encoding='utf-8') as f:
    f.write(text + new_func)
