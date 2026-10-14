import asyncio
import sys
sys.path.append('.')
from app.db.neo4j_client import neo4j_client

async def seed():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            await session.run("MERGE (c:Course {id: 'demo-sql'}) SET c.owner_id = 'dev-user'")
            await session.run("MERGE (d:Document {id: 'demo-sql-doc-2', course_id: 'demo-sql'}) SET d.status = 'UPLOADED', d.seq = 1")
            print("Seeded Course 'demo-sql' and Document 'demo-sql-doc-2'")
    finally:
        await neo4j_client.close()

asyncio.run(seed())
