import asyncio
import sys
sys.path.append('.')
from app.db.neo4j_client import neo4j_client

async def clean():
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as session:
        # Delete doc-test-123
        await session.run("MATCH (d:Document {id: 'doc-test-123'}) DETACH DELETE d")
        await session.run("MATCH (c:Chunk {doc_id: 'doc-test-123'}) DETACH DELETE c")
        # Clean specific course_ids
        for c_id in ['__test__', '__smoke__', 'course-test-123']:
            await session.run('MATCH (n) WHERE n.course_id = $c_id DETACH DELETE n', c_id=c_id)
        
        # Count remaining
        cnt = 0
        for c_id in ['__test__', '__smoke__', 'course-test-123']:
            rec = await session.run('MATCH (n) WHERE n.course_id = $c_id RETURN count(n) AS c', c_id=c_id)
            cnt += (await rec.single())['c']
        print(f'Remaining nodes for test courses: {cnt}')
    await neo4j_client.close()

asyncio.run(clean())
