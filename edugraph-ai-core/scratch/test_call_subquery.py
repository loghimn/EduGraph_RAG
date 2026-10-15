import asyncio
from app.db.neo4j_client import neo4j_client

async def main():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            q = """EXPLAIN
            UNWIND $edges AS edge
            MATCH (src:Concept {id: edge.source_id, course_id: $course_id})
            CALL {
                WITH edge
                MATCH (tgt:Concept {id: edge.target_id, course_id: $course_id})
                RETURN tgt
            }
            MERGE (src)-[r:PREREQUISITE_OF]->(tgt)
            """
            res = await session.run(q, course_id="__test__", edges=[])
            plan = (await res.consume()).plan
            
            def search_plan(node):
                res = [node.get("operatorType")]
                for child in node.get("children", []):
                    res.extend(search_plan(child))
                return res
            ops = search_plan(plan)
            print("Cartesian in CALL:", [op for op in ops if "Cartesian" in str(op)])
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
