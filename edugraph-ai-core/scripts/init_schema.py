import asyncio
import os
import sys

# Thêm đường dẫn project vào sys.path để có thể import module app
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.db.neo4j_client import neo4j_client
from app.db.schema import DDL_STATEMENTS

async def main():
    await neo4j_client.connect()
    print("Applying basic constraints and indexes...")
    async with neo4j_client.driver.session() as session:
        for stmt in DDL_STATEMENTS:
            try:
                await session.run(stmt)
                print(f"[OK] {stmt[:50]}...")
            except Exception as e:
                print(f"[ERROR] {stmt[:50]}... => {e}")

    await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
