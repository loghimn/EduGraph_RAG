import asyncio
from app.db.neo4j_client import neo4j_client

async def main():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            q1 = """EXPLAIN
            UNWIND $edges AS edge
            MATCH (src:Concept {id: edge.source_id, course_id: $course_id})
            MATCH (tgt:Concept {id: edge.target_id, course_id: $course_id})
            MERGE (src)-[r:PREREQUISITE_OF]->(tgt)
            """
            res1 = await session.run(q1, course_id="__test__", edges=[])
            p1 = (await res1.consume()).plan
            
            def search_plan(node):
                res = [node.get("operatorType")]
                for child in node.get("children", []):
                    res.extend(search_plan(child))
                return res
            ops1 = search_plan(p1)
            print("Ops 1:", [op for op in ops1 if "Cartesian" in str(op)])

            q2 = """EXPLAIN
            UNWIND $edges AS edge
            MATCH (src:Concept {id: edge.source_id, course_id: $course_id})
            WITH edge, src
            MATCH (tgt:Concept {id: edge.target_id, course_id: $course_id})
            MERGE (src)-[r:PREREQUISITE_OF]->(tgt)
            """
            res2 = await session.run(q2, course_id="__test__", edges=[])
            p2 = (await res2.consume()).plan
            ops2 = search_plan(p2)
            print("Ops 2:", [op for op in ops2 if "Cartesian" in str(op)])
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
