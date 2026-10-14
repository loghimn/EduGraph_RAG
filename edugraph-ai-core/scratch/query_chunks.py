import asyncio
import sys
sys.path.append('.')
from app.db.neo4j_client import neo4j_client

async def analyze():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            for idx in [5, 7, 9, 11]:
                rec = await session.run("MATCH (c:Chunk {doc_id: 'test-real-123', index: $idx}) RETURN c.text AS text", idx=idx)
                record = await rec.single()
                if record:
                    text = record['text']
                    print(f'\n--- CHUNK {idx} (Length {len(text)} chars) ---')
                    print(text[:300])
                    print('...')
                else:
                    print(f'Chunk {idx} not found.')
    finally:
        await neo4j_client.close()

asyncio.run(analyze())
