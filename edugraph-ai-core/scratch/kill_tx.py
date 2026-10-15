"""Dev-only helper: list (and optionally terminate) stuck Neo4j transactions.

SAFETY: dry-run by default. Termination requires ALL of:
  1. --confirm flag (explicit opt-in, no accidental kills),
  2. username match (defaults to this app's NEO4J_USERNAME),
  3. narrow query filter: metadata tag OR UNWIND write (never bare MATCH).

Usage:
  python scratch/kill_tx.py                       # list only
  python scratch/kill_tx.py --confirm             # terminate app-owned stuck txs
  python scratch/kill_tx.py --confirm --tag mytag # custom metadata tag
"""
import argparse
import asyncio
import re

from app.db.neo4j_client import neo4j_client
from app.config import settings

_TX_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9:._-]*$")


def _is_candidate(tx: dict, username: str, tag: str) -> bool:
    query = tx.get("currentQuery") or ""
    if "SHOW TRANSACTIONS" in query:
        return False
    # Scope to our own DB user first (shared Aura instance safety).
    if tx.get("username") != username:
        return False
    meta = str(tx.get("metaData") or "")
    # Narrow filter: app metadata tag OR bulk-write UNWIND.
    # NOTE: bare 'MATCH' alone is intentionally NOT a match — it would
    # kill almost every live transaction (ingest reads, Java writes, chat).
    if tag and tag in meta:
        return True
    if "UNWIND" in query:
        return True
    return False


async def main() -> None:
    parser = argparse.ArgumentParser(description="List/kill stuck Neo4j transactions (safe defaults).")
    parser.add_argument("--confirm", action="store_true",
                        help="Actually terminate matching transactions. Without it: dry-run list only.")
    parser.add_argument("--username", default=settings.NEO4J_USERNAME,
                        help="Only consider transactions of this user (default: app's NEO4J_USERNAME).")
    parser.add_argument("--tag", default="app",
                        help="Metadata tag identifying app transactions (default: 'app').")
    args = parser.parse_args()

    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            result = await session.run("SHOW TRANSACTIONS")
            txs = await result.data()
            candidates = [tx for tx in txs if _is_candidate(tx, args.username, args.tag)]
            for tx in txs:
                mark = "CANDIDATE" if tx in candidates else "skip"
                print(f"TX: {tx.get('transactionId')} | state: {tx.get('status')} "
                      f"| user: {tx.get('username')} | {mark}")
            if not candidates:
                print("No matching transactions.")
                return
            if not args.confirm:
                print(f"Dry-run: {len(candidates)} candidate(s) would be terminated. "
                      f"Re-run with --confirm to proceed.")
                return
            for tx in candidates:
                tx_id = str(tx.get("transactionId"))
                if not _TX_ID_RE.match(tx_id):
                    print(f"Skip suspicious transactionId: {tx_id!r}")
                    continue
                await session.run(f"TERMINATE TRANSACTION '{tx_id}'")
                print(f"Terminated {tx_id}")
    finally:
        await neo4j_client.close()


asyncio.run(main())

