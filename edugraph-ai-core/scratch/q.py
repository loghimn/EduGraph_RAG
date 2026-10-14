import asyncio
from app.db.neo4j_client import neo4j_client
async def q():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as s:
            rec1 = await s.run('MATCH (c:Chunk) RETURN c.course_id AS course, c.doc_id AS doc, count(*) AS chunks ORDER BY course, doc')
            print('--- Chunks ---')
            async for r in rec1:
                print(f"{r['course']} | {r['doc']} | {r['chunks']}")
            rec2 = await s.run('MATCH (d:Document) RETURN d.id, d.course_id, d.status, d.error')
            print('\n--- Documents ---')
            async for r in rec2:
                print(f"{r['d.id']} | {r['d.course_id']} | {r['d.status']} | {r['d.error']}")
    finally:
        await neo4j_client.close()
asyncio.run(q())
