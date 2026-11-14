"""Read-only connectivity probe: python scripts/check_neo4j_conn.py."""
import asyncio
import logging
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.db.neo4j_client import neo4j_client

logging.basicConfig(level=logging.INFO)


async def test_connection():
    try:
        await neo4j_client.connect()
        async with neo4j_client.driver.session() as session:
            result = await session.run("RETURN 1 AS ok")
            record = await result.single()
            if record is None or record["ok"] != 1:
                raise RuntimeError("Unexpected Neo4j probe result")
        print("Neo4j connection OK; RETURN 1 = 1")
        return 0
    except Exception as failure:
        print(f"Neo4j connection failed ({type(failure).__name__}).", file=sys.stderr)
        return 1
    finally:
        await neo4j_client.close()


if __name__ == "__main__":
    raise SystemExit(asyncio.run(test_connection()))
