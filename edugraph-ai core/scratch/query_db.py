import asyncio
from app.db.neo4j_client import neo4j_client
async def main():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            # Document
            res = await session.run("MATCH (d:Document {course_id: 'demo-sql'}) RETURN d.id as id, d.status as status, d.error as error, d.updated_at as updated_at")
            print('--- Document ---')
            for r in await res.data(): print(r)
            
            # Course version
            res = await session.run("MATCH (c:Course {id: 'demo-sql'}) RETURN c.version as version")
            print('--- Course ---')
            for r in await res.data(): print(r)
            
            # doc-2 concepts
            res = await session.run("MATCH (c:Chunk {doc_id: 'demo-sql-doc-2'})-[:MENTIONS]->(n:Concept) RETURN count(DISTINCT n) as cnt")
            print('--- doc-2 Concepts ---')
            print((await res.single())['cnt'])
    finally:
        await neo4j_client.close()
asyncio.run(main())
