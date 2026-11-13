import asyncio
import ssl
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
import app.db.neo4j_client as module


@pytest.fixture
def config(monkeypatch):
    value = SimpleNamespace(NEO4J_URI="neo4j+s://graph.example:7687?region=asia", NEO4J_USERNAME="test-user",
                            NEO4J_PASSWORD="test-password", NEO4J_TLS_TRUST_STORE="auto", NEO4J_CA_CERT_FILE=None)
    monkeypatch.setattr(module, "settings", value)
    return value


def test_windows_auto_uses_native_verified_tls_without_changing_routing(config, monkeypatch):
    monkeypatch.setattr(module, "sys", SimpleNamespace(platform="win32"))
    uri, options = module._connection_options()
    assert uri == "neo4j://graph.example:7687?region=asia"
    assert options["encrypted"] is True
    context = options["ssl_context"]
    assert context.check_hostname is True
    assert context.verify_mode == ssl.CERT_REQUIRED
    assert context.minimum_version >= ssl.TLSVersion.TLSv1_2


@pytest.mark.parametrize("scheme", ["neo4j", "bolt"])
def test_plain_local_connections_keep_existing_configuration(config, scheme):
    config.NEO4J_URI = f"{scheme}://localhost:7687"
    assert module._connection_options() == (config.NEO4J_URI, {})


def test_non_windows_auto_and_python_override_keep_driver_defaults(config, monkeypatch):
    monkeypatch.setattr(module, "sys", SimpleNamespace(platform="linux"))
    assert module._connection_options() == (config.NEO4J_URI, {})
    monkeypatch.setattr(module, "sys", SimpleNamespace(platform="win32"))
    config.NEO4J_TLS_TRUST_STORE = "python"
    assert module._connection_options() == (config.NEO4J_URI, {})


def test_custom_ca_requires_secure_uri_and_is_loaded_into_verified_context(config, monkeypatch, tmp_path):
    config.NEO4J_TLS_TRUST_STORE = "python"
    config.NEO4J_CA_CERT_FILE = tmp_path / "approved-ca.pem"
    context = Mock()
    monkeypatch.setattr(module.ssl, "create_default_context", lambda: context)
    uri, options = module._connection_options()
    context.load_verify_locations.assert_called_once_with(cafile=str(config.NEO4J_CA_CERT_FILE))
    assert context.verify_mode == ssl.CERT_REQUIRED and context.check_hostname is True
    assert options["encrypted"] is True
    config.NEO4J_URI = "neo4j+ssc://graph.example:7687"
    with pytest.raises(ValueError, match="requires a neo4j"):
        module._connection_options()


@pytest.mark.asyncio
async def test_failed_connection_is_closed_and_retry_creates_a_fresh_driver(config, monkeypatch):
    monkeypatch.setattr(module, "_connection_options", lambda: (config.NEO4J_URI, {}))
    failed = Mock(verify_connectivity=AsyncMock(side_effect=RuntimeError("TLS verification failed")), close=AsyncMock())
    ready = Mock(verify_connectivity=AsyncMock(), close=AsyncMock())
    create = Mock(side_effect=[failed, ready])
    monkeypatch.setattr(module.AsyncGraphDatabase, "driver", create)
    client = module.Neo4jClient()
    with pytest.raises(RuntimeError, match="TLS verification failed"):
        await client.connect()
    assert client.driver is None
    failed.close.assert_awaited_once()
    await client.connect()
    assert client.driver is ready
    await client.close()
    assert client.driver is None
    ready.close.assert_awaited_once()
    await client.close()
    ready.close.assert_awaited_once()


@pytest.mark.asyncio
async def test_cleanup_failure_does_not_hide_original_tls_failure(config, monkeypatch):
    original = RuntimeError("original TLS failure")
    driver = Mock(verify_connectivity=AsyncMock(side_effect=original), close=AsyncMock(side_effect=RuntimeError("cleanup failed")))
    monkeypatch.setattr(module, "_connection_options", lambda: (config.NEO4J_URI, {}))
    monkeypatch.setattr(module.AsyncGraphDatabase, "driver", Mock(return_value=driver))
    client = module.Neo4jClient()
    with pytest.raises(RuntimeError) as raised:
        await client.connect()
    assert raised.value is original
    assert client.driver is None


@pytest.mark.asyncio
async def test_concurrent_connects_only_open_one_verified_driver(config, monkeypatch):
    started, proceed = asyncio.Event(), asyncio.Event()
    async def verify():
        started.set()
        await proceed.wait()
    driver = Mock(verify_connectivity=AsyncMock(side_effect=verify), close=AsyncMock())
    create = Mock(return_value=driver)
    monkeypatch.setattr(module, "_connection_options", lambda: (config.NEO4J_URI, {}))
    monkeypatch.setattr(module.AsyncGraphDatabase, "driver", create)
    client = module.Neo4jClient()
    first = asyncio.create_task(client.connect())
    await started.wait()
    second = asyncio.create_task(client.connect())
    proceed.set()
    await asyncio.gather(first, second)
    create.assert_called_once()
    await client.close()


@pytest.mark.asyncio
async def test_embedding_startup_failure_still_closes_neo4j(monkeypatch):
    from app import main
    from app.core.document_tasks import document_tasks
    connect, close, shutdown = AsyncMock(), AsyncMock(), AsyncMock()
    monkeypatch.setattr(main.neo4j_client, "connect", connect)
    monkeypatch.setattr(main.neo4j_client, "close", close)
    monkeypatch.setattr(document_tasks, "shutdown", shutdown)
    monkeypatch.setattr(main, "EmbeddingModel", Mock(return_value=Mock(load=Mock(side_effect=RuntimeError("model failed")))))
    with pytest.raises(RuntimeError, match="model failed"):
        async with main.lifespan(main.app):
            pytest.fail("must not serve requests after failed warmup")
    close.assert_awaited_once()
    shutdown.assert_awaited_once()
