import asyncio
from app.db.neo4j_client import neo4j_client

async def q():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as s:
            rec = await s.run('MATCH (c:Concept {course_id: "demo-sql"}) RETURN c.id AS id, c.name AS name LIMIT 5')
            for r in await rec.data():
                print(r)
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(q())
