import asyncio
from app.db.neo4j_client import neo4j_client

async def main():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as s:
            res = await s.run("MATCH (n:Concept {id: 'C1'}) RETURN n, labels(n) AS lbls")
            data = await res.data()
            print("Node C1:", data)
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
