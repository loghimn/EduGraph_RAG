"""Opt-in Neo4j checks. Every fixture is isolated and explicitly rolled back."""
import os
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import patch
from uuid import uuid4

import pytest
from fastapi import HTTPException

from app.db.neo4j_client import neo4j_client
from app.db import repo
from app.db.queries import publication as pq


pytestmark = [pytest.mark.asyncio, pytest.mark.skipif(
    os.getenv("EDUGRAPH_TEST_NEO4J") != "1", reason="Set EDUGRAPH_TEST_NEO4J=1 for live rollback-only checks")]


@pytest.mark.parametrize("edges,archived,cyclic,replace_auto_cycle", [
    ([("a", "b", "CONFIRMED"), ("b", "c", "MANUAL")], [], False, False),
    ([("a", "b", "CONFIRMED"), ("b", "a", "CONFIRMED")], [], True, False),
    ([("a", "b", "CONFIRMED"), ("b", "c", "AUTO"), ("c", "a", "CONFIRMED")], [], True, False),
    ([("a", "a", "CONFIRMED")], [], True, False),
    ([("a", "b", "CONFIRMED"), ("b", "a", "REJECTED")], [], False, False),
    ([("a", "b", "CONFIRMED"), ("b", "a", "CONFIRMED")], ["b"], False, False),
    ([("x", "y", "AUTO"), ("y", "x", "AUTO")], [], False, True),
])
async def test_real_neo4j_publication_guard(edges, archived, cyclic, replace_auto_cycle):
    course_id = "dag-test-" + uuid4().hex
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            tx = await session.begin_transaction()
            try:
                await tx.run("CREATE (:Course {id: $course_id, owner_id: 'test-owner', version: 0})", course_id=course_id)
                await tx.run("""
                    UNWIND $edges AS edge
                    MERGE (s:Concept {id: edge.source, course_id: $course_id})
                    MERGE (t:Concept {id: edge.target, course_id: $course_id})
                    SET s.origin = 'MANUAL', t.origin = 'MANUAL'
                    MERGE (s)-[r:PREREQUISITE_OF]->(t) SET r.status = edge.status
                """, course_id=course_id, edges=[dict(source=s, target=t, status=st) for s, t, st in edges])
                if replace_auto_cycle:
                    await tx.run("""
                        CREATE (ch:Chunk {id: $course_id, doc_id: $course_id, course_id: $course_id})
                        WITH ch
                        MATCH (c:Concept {course_id: $course_id})
                        SET c.origin = 'AUTO', c.curated = false
                        CREATE (ch)-[:MENTIONS]->(c)
                    """, course_id=course_id)
                await tx.run("""MATCH (c:Concept {course_id: $course_id})
                    WHERE c.id IN $archived SET c.status = 'ARCHIVED'""", course_id=course_id, archived=archived)
                # Run the real publication queries in this transaction, while
                # retaining control of its boundary so the fixture never commits.
                async def execute(write):
                    return await write(tx)
                @asynccontextmanager
                async def publication_session():
                    yield SimpleNamespace(execute_write=execute)
                with patch.object(repo.neo4j_client, 'driver', SimpleNamespace(session=publication_session)):
                    operation = repo.save_ingestion_transaction(course_id, course_id, [], [], [], publication={
                        'user_id': 'test-owner', 'version': 0, 'review_id': 'test-review', 'filename': 'test.txt', 'edges': []})
                    if cyclic:
                        with pytest.raises(HTTPException) as error:
                            await operation
                        assert error.value.detail["code"] == "PREREQUISITE_CYCLE"
                    else:
                        saved = await operation
                        assert saved['version'] == 1
                        if replace_auto_cycle:
                            assert saved['deleted_chunks'] == 1
                            assert saved['deleted_orphans'] == 2
                            remaining = await (await tx.run("""
                                MATCH (c:Concept {course_id: $course_id})
                                RETURN count(c) AS count
                            """, course_id=course_id)).single()
                            assert remaining['count'] == 0
                            assert await (await tx.run(pq.ACTIVE_EDGES, course_id=course_id)).data() == []
            finally:
                await tx.rollback()
            row = await (await session.run("MATCH (n {course_id: $course_id}) RETURN count(n) AS count",
                                          course_id=course_id)).single()
            assert row["count"] == 0
            row = await (await session.run("MATCH (c:Course {id: $course_id}) RETURN count(c) AS count",
                                          course_id=course_id)).single()
            assert row["count"] == 0
    finally:
        await neo4j_client.close()
