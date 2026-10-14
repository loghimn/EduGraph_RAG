import asyncio, os, sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from app.db.neo4j_client import neo4j_client

CID = "__test__"
STATUS = sys.argv[1] if len(sys.argv) > 1 else "DONE"

async def main():
    assert CID == "__test__"
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as s:
            await s.run("MERGE (c:Course {id: $cid}) SET c.owner_id = 'user1', c.version = coalesce(c.version, 0)", cid=CID)
            await s.run("MERGE (d:Document {id: '__test__-doc1'}) SET d.course_id = $cid, d.status = $st", cid=CID, st=STATUS)
            for i in (1, 2):
                await s.run("MERGE (ch:Chunk {id: $id}) SET ch.course_id = $cid, ch.doc_id = '__test__-doc1', ch.index = $i",
                            id=f"__test__-ch{i}", cid=CID, i=i)
                await s.run("MERGE (co:Concept {course_key: $ck}) SET co.id = $id, co.course_id = $cid, co.name = $name",
                            ck=f"{CID}|k{i}", id=f"__test__-co{i}", cid=CID, name=f"concept {i}")
                await s.run("MATCH (ch:Chunk {id: $chid}), (co:Concept {id: $coid}) MERGE (ch)-[m:MENTIONS]->(co) SET m.is_evidence = true",
                            chid=f"__test__-ch{i}", coid=f"__test__-co{i}")
            await s.run("MATCH (a:Concept {id: '__test__-co1'}), (b:Concept {id: '__test__-co2'}) MERGE (a)-[p:PREREQUISITE_OF]->(b) SET p.status = 'AUTO'")
            print(f"seeded __test__ (doc status={STATUS})")
    finally:
        await neo4j_client.close()

asyncio.run(main())
