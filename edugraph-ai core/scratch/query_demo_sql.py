import asyncio
from app.db.neo4j_client import neo4j_client

async def get_stats():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as s:
            rec1 = await s.run("MATCH (c:Chunk {course_id: 'demo-sql'}) RETURN c.doc_id AS doc_id, count(c) AS chunk_count")
            chunks = {r['doc_id']: r['chunk_count'] async for r in rec1}
            print(f'Chunks by doc_id: {chunks}')

            rec2 = await s.run("MATCH (c:Concept {course_id: 'demo-sql'}) RETURN count(c) AS concept_count")
            concept_count = (await rec2.single())['concept_count']
            print(f'Total Concepts: {concept_count}')
            
            rec3 = await s.run("MATCH (:Chunk {course_id: 'demo-sql'})-[r:MENTIONS]->(:Concept) RETURN count(r) AS total, sum(CASE WHEN r.is_evidence THEN 1 ELSE 0 END) AS ev_count")
            mentions = await rec3.single()
            print(f'Mentions: Total={mentions["total"]}, is_evidence={mentions["ev_count"]}')
            
            rec4 = await s.run("MATCH (c:Course {id: 'demo-sql'}) RETURN c.version AS version")
            res4 = await rec4.single()
            version = res4['version'] if res4 else None
            print(f'Course.version: {version}')
    finally:
        await neo4j_client.close()

asyncio.run(get_stats())
