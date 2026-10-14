import asyncio
import logging
import sys
import os

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.append(ROOT_DIR)
from app.db.neo4j_client import neo4j_client

logging.basicConfig(level=logging.INFO)

async def test_connection():
    try:
        print("Đang thử kết nối tới Neo4j...")
        await neo4j_client.connect()
    except Exception as e:
        print(f"Kết nối thất bại. Lỗi: {e}")
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(test_connection())