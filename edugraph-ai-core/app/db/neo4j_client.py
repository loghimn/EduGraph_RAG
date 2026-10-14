from neo4j import AsyncGraphDatabase
from app.config import settings
import logging

logger = logging.getLogger(__name__)

class Neo4jClient:
    def __init__(self):
        self.driver = None

    async def connect(self):
        if not self.driver:
            self.driver = AsyncGraphDatabase.driver(
                settings.NEO4J_URI,
                auth=(settings.NEO4J_USERNAME, settings.NEO4J_PASSWORD)
            )
            # Verify connectivity
            await self.driver.verify_connectivity()
            logger.info("Connected to Neo4j successfully.")

    async def close(self):
        if self.driver:
            await self.driver.close()
            logger.info("Neo4j connection closed.")

neo4j_client = Neo4jClient()
