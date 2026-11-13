import asyncio
import logging
import ssl
import sys
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

from neo4j import AsyncGraphDatabase
from app.config import settings

logger = logging.getLogger(__name__)


def _connection_options():
    """Scope native trust to this driver; never patch ssl globally or trust all certificates."""
    uri = settings.NEO4J_URI
    parsed = urlsplit(uri)
    secure = parsed.scheme in {"neo4j+s", "bolt+s"}
    ca_file = settings.NEO4J_CA_CERT_FILE
    if ca_file and not secure:
        raise ValueError("NEO4J_CA_CERT_FILE requires a neo4j+s:// or bolt+s:// URI.")
    native = settings.NEO4J_TLS_TRUST_STORE == "system" or (
        settings.NEO4J_TLS_TRUST_STORE == "auto" and sys.platform == "win32"
    )
    if not secure or not (native or ca_file):
        return uri, {}

    if native:
        import truststore
        context = truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    else:
        context = ssl.create_default_context()
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.check_hostname = True
    context.verify_mode = ssl.CERT_REQUIRED
    if ca_file:
        path = Path(ca_file).expanduser()
        if not path.is_absolute():
            path = Path(__file__).resolve().parents[2] / path
        context.load_verify_locations(cafile=str(path))

    # Neo4j disallows ssl_context alongside +s. The explicit verified context keeps TLS enabled.
    # Keep host, port and routing query intact while selecting the configurable driver scheme.
    uri = urlunsplit(parsed._replace(scheme=parsed.scheme.removesuffix("+s")))
    return uri, {"encrypted": True, "ssl_context": context}


class Neo4jClient:
    def __init__(self):
        self.driver = None
        self._connection_lock = asyncio.Lock()

    async def connect(self):
        async with self._connection_lock:
            if self.driver is not None:
                return
            uri, options = _connection_options()
            driver = AsyncGraphDatabase.driver(
                uri, auth=(settings.NEO4J_USERNAME, settings.NEO4J_PASSWORD), **options
            )
            try:
                await driver.verify_connectivity()
            except BaseException:
                try:
                    await driver.close()
                except Exception:
                    logger.warning("Unable to close Neo4j driver after failed connection.")
                raise
            self.driver = driver
            logger.info("Connected to Neo4j successfully.")

    async def close(self):
        async with self._connection_lock:
            driver, self.driver = self.driver, None
            if driver is not None:
                await driver.close()
                logger.info("Neo4j connection closed.")


neo4j_client = Neo4jClient()
