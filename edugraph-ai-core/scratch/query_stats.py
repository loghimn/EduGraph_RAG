import asyncio
from app.db.neo4j_client import neo4j_client

async def main():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            # Chunk count grouped by doc_id
            print('\n--- Chunk Count by doc_id ---')
            res = await session.run("MATCH (c:Chunk {course_id: 'demo-sql'}) RETURN c.doc_id AS doc_id, count(c) AS cnt")
            for record in await res.data():
                print(f"{record['doc_id']}: {record['cnt']}")
            
            # Concept count
            print('\n--- Concept Count ---')
            res = await session.run("MATCH (c:Concept {course_id: 'demo-sql'}) RETURN count(c) AS cnt")
            print((await res.single())['cnt'])
            
            # MENTIONS count
            print('\n--- MENTIONS Count ---')
            res = await session.run("MATCH (c:Chunk {course_id: 'demo-sql'})-[r:MENTIONS]->() RETURN count(r) AS cnt")
            print((await res.single())['cnt'])
            
            # Chunks with NO MENTIONS
            print('\n--- Chunks with NO MENTIONS ---')
            res = await session.run("MATCH (c:Chunk {course_id: 'demo-sql'}) WHERE NOT (c)-[:MENTIONS]->() RETURN c.id AS id ORDER BY c.id")
            for record in await res.data():
                print(record['id'])
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
