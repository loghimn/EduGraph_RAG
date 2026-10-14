import asyncio
from app.db.neo4j_client import neo4j_client

async def main():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as s:
            res = await s.run("MATCH (c:Course {id: 'demo-sql'}) RETURN c.owner_id AS owner_id, c.version AS version")
            print("demo-sql course:", await res.data())
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
