import asyncio
from app.db.neo4j_client import neo4j_client

async def main():
    await neo4j_client.connect()
    async with neo4j_client.driver.session() as session:
        result = await session.run('SHOW TRANSACTIONS')
        txs = await result.data()
        for tx in txs:
            query = tx.get('currentQuery', '')
            if query is None: query = ''
            print(f"TX: {tx.get('transactionId')} | state: {tx.get('status')} | user: {tx.get('username')}")
            if 'app' in str(tx.get('metaData')) or 'UNWIND' in query or 'MATCH' in query:
                if 'SHOW TRANSACTIONS' not in query:
                    await session.run(f"TERMINATE TRANSACTION '{tx.get('transactionId')}'")
                    print(f"Terminated {tx.get('transactionId')}")
    await neo4j_client.close()
    
asyncio.run(main())
