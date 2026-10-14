import asyncio
from app.db.neo4j_client import neo4j_client

async def main():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
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
            result = await session.run(query, course_id='__test__', edges=[], rescored_ids=[], cut_edges=[])
            summary = await result.consume()
            
            def search_plan(node):
                res = [node.get("operatorType")]
                for child in node.get("children", []):
                    res.extend(search_plan(child))
                return res
                
            operators = search_plan(summary.plan)
            cart = [op for op in operators if 'CartesianProduct' in str(op)]
            print("Cartesian found:", cart)
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
