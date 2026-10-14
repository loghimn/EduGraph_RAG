import asyncio
from app.db.neo4j_client import neo4j_client

async def main():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as s:
            res = await s.run("""
                MATCH (n)
                RETURN distinct n.course_id as cid, labels(n) as lbls, count(n) as cnt
            """)
            data = await res.data()
            for d in data:
                print(d)
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
