import asyncio
import json
from app.db.neo4j_client import neo4j_client

query_old = """
EXPLAIN
// 1. Xóa chunk cũ
OPTIONAL MATCH (old_c:Chunk {doc_id: $doc_id})
DETACH DELETE old_c
WITH count(*) AS _dummy

// 2. Ghi Chunk mới
UNWIND $chunks AS ch
MERGE (c:Chunk {id: ch.id})
SET c.doc_id = ch.doc_id,
    c.course_id = ch.course_id,
    c.embedding = ch.embedding
WITH count(*) AS _dummy

// 3. Ghi Concept mới
UNWIND $concepts AS co
MERGE (n:Concept {course_key: co.course_key})
ON CREATE SET n.id = co.id, n.course_id = co.course_id
SET n.name = co.name, n.embedding = co.embedding
WITH count(*) AS _dummy

// 4. Ghi Mentions
UNWIND $mentions AS m
MATCH (chunk:Chunk {id: m.chunk_id})
MATCH (concept:Concept {course_key: m.course_key})
MERGE (chunk)-[r:MENTIONS {start: m.start, end: m.end}]->(concept)
SET r.is_evidence = m.is_evidence
WITH count(*) AS _dummy

// 5. Dọn dẹp
OPTIONAL MATCH (orphan:Concept {course_id: $course_id})
WHERE NOT (orphan)<-[:MENTIONS]-()
DETACH DELETE orphan
"""

async def main():
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as session:
        result = await session.run(query_old)
        summary = await result.consume()
        # Parse the plan tree to see if CartesianProduct exists
        def search_plan(node):
            res = [node.get("operatorType")]
            for child in node.get("children", []):
                res.extend(search_plan(child))
            return res
        
        plan = summary.plan
        ops = search_plan(plan)
        print("OLD QUERY OPERATORS:", [op for op in ops if "Cartesian" in op or "Apply" in op or op.startswith("NodeHash")])
        if "CartesianProduct" in ops:
            print("FOUND CartesianProduct IN OLD QUERY!")
            
    await neo4j_client.close()

asyncio.run(main())
