import asyncio
import sys
sys.path.append('.')
from app.db.neo4j_client import neo4j_client

async def analyze():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            rec = await session.run("MATCH (c:Chunk {doc_id: 'test-real-123'}) RETURN c.index AS idx ORDER BY idx")
            records = [r['idx'] async for r in rec]
            print(f'Chunks found: {records}')
    finally:
        await neo4j_client.close()

asyncio.run(analyze())
