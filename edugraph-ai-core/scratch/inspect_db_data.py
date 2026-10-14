import asyncio
import json
from app.db.neo4j_client import neo4j_client

async def main():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            # 1. Documents
            res_docs = await session.run("""
                MATCH (d:Document)
                RETURN d.id AS id, d.name AS name, d.course_id AS course_id, 
                       d.status AS status, d.error AS error, d.progress AS progress,
                       d.updated_at AS updated_at
            """)
            docs = await res_docs.data()
            print("=== DOCUMENTS ===")
            print(json.dumps(docs, indent=2, ensure_ascii=False))

            # 2. Chunks by doc_id
            res_chunks = await session.run("""
                MATCH (ch:Chunk)
                RETURN ch.doc_id AS doc_id, count(ch) AS count, 
                       min(ch.index) AS min_index, max(ch.index) AS max_index
            """)
            chunks = await res_chunks.data()
            print("=== CHUNKS BY DOC_ID ===")
            print(json.dumps(chunks, indent=2, ensure_ascii=False))

            # 3. Concepts
            res_concepts = await session.run("""
                MATCH (c:Concept)
                RETURN c.course_id AS course_id, count(c) AS count
            """)
            concepts_count = await res_concepts.data()
            print("=== CONCEPTS COUNT ===")
            print(json.dumps(concepts_count, indent=2, ensure_ascii=False))

            res_concept_names = await session.run("""
                MATCH (c:Concept {course_id: 'demo-sql'})
                RETURN c.id AS id, c.name AS name, c.order AS order
                ORDER BY c.order ASC, c.name ASC
            """)
            concept_names = await res_concept_names.data()
            print("=== DEMO-SQL CONCEPTS (count) ===")
            print(f"Total: {len(concept_names)}")
            for c in concept_names:
                print(f"  - {c['name']} (id={c['id']}, order={c['order']})")

            # 4. PREREQUISITE_OF by status, source, model, prompt_version
            res_dist = await session.run("""
                MATCH ()-[r:PREREQUISITE_OF]->()
                RETURN r.status AS status, r.source AS source, 
                       r.model AS model, r.prompt_version AS prompt_version, count(r) AS count
            """)
            dist = await res_dist.data()
            print("=== PREREQUISITE_OF DISTRIBUTION ===")
            print(json.dumps(dist, indent=2, ensure_ascii=False))

            # 5. All 31 PREREQUISITE_OF edges
            res_edges = await session.run("""
                MATCH (src:Concept)-[r:PREREQUISITE_OF]->(tgt:Concept)
                RETURN src.name AS src_name, tgt.name AS tgt_name,
                       r.status AS status, r.score AS score, r.evidence AS evidence,
                       r.model AS model, r.prompt_version AS prompt_version,
                       r.source AS source, r.created_at AS created_at
                ORDER BY src.name, tgt.name
            """)
            edges = await res_edges.data()
            print(f"=== ALL PREREQUISITE_OF EDGES (total: {len(edges)}) ===")
            for e in edges:
                print(f"{e['src_name']} -> {e['tgt_name']} | status: {e['status']} | score: {e['score']} | evidence: {e['evidence']}")

            # 6. Specifically check GROUP BY and view relationship
            res_gv = await session.run("""
                MATCH (a:Concept)-[r:PREREQUISITE_OF]-(b:Concept)
                WHERE toLower(a.name) CONTAINS 'group' OR toLower(b.name) CONTAINS 'group'
                   OR toLower(a.name) CONTAINS 'view' OR toLower(b.name) CONTAINS 'view'
                RETURN a.name AS a, type(r) AS rel, b.name AS b, startNode(r) = a AS a_is_src,
                       r.status AS status, r.score AS score, r.evidence AS evidence,
                       r.model AS model
            """)
            gv = await res_gv.data()
            print("=== GROUP BY / VIEW EDGES ===")
            print(json.dumps(gv, indent=2, ensure_ascii=False))
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
