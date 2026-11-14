"""Neo4j session/query adapter used by application services.

Transactions are opened by the use case so multi-query mutations stay atomic.
"""
from app.db.neo4j_client import neo4j_client


def session():
    return neo4j_client.driver.session()


async def run(transaction, query: str, **parameters):
    return await transaction.run(query, **parameters)
