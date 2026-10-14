import asyncio
from app.db.neo4j_client import neo4j_client

async def query_db():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as s:
            rec = await s.run('MATCH (c:Chunk) RETURN c.course_id AS c_id, c.doc_id AS d_id, count(*) AS cnt')
            print('--- Chunks ---')
            async for r in rec:
                print(r['c_id'], r['d_id'], r['cnt'])
            
            rec2 = await s.run('MATCH (d:Document) RETURN d.id AS id, d.course_id AS c_id, d.status AS st, d.error AS err')
            print('\n--- Documents ---')
            async for r in rec2:
                print(r['id'], r['c_id'], r['st'], r['err'])
    finally:
        await neo4j_client.close()

asyncio.run(query_db())
