import asyncio
import json
import networkx as nx
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

            # 2. Chunks by doc_id
            res_chunks = await session.run("""
                MATCH (ch:Chunk)
                RETURN ch.doc_id AS doc_id, count(ch) AS count, 
                       min(ch.index) AS min_index, max(ch.index) AS max_index
            """)
            chunks = await res_chunks.data()

            # 3. Concepts
            res_concepts = await session.run("""
                MATCH (c:Concept)
                RETURN c.course_id AS course_id, count(c) AS count
            """)
            concepts_count = await res_concepts.data()

            res_concept_names = await session.run("""
                MATCH (c:Concept {course_id: 'demo-sql'})
                RETURN c.id AS id, c.name AS name, c.order AS order
                ORDER BY c.order ASC, c.name ASC
            """)
            concept_names = await res_concept_names.data()

            # 4. PREREQUISITE_OF by status, source, model, prompt_version
            res_dist = await session.run("""
                MATCH ()-[r:PREREQUISITE_OF]->()
                RETURN r.status AS status, r.source AS source, 
                       r.model AS model, r.prompt_version AS prompt_version, count(r) AS count
            """)
            dist = await res_dist.data()

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

            # 6. Topological generations / Stage tree for demo-sql
            # Dựng đồ thị
            G = nx.DiGraph()
            for c in concept_names:
                G.add_node(c["id"], name=c["name"], order=c["order"])
            
            # Đọc cạnh
            res_sql_edges = await session.run("""
                MATCH (src:Concept {course_id: 'demo-sql'})-[r:PREREQUISITE_OF]->(tgt:Concept {course_id: 'demo-sql'})
                WHERE r.status <> 'REJECTED'
                RETURN src.id AS src_id, tgt.id AS tgt_id, src.name AS src_name, tgt.name AS tgt_name
            """)
            sql_edges = await res_sql_edges.data()
            for e in sql_edges:
                G.add_edge(e["src_id"], e["tgt_id"])

            is_dag = nx.is_directed_acyclic_graph(G)
            stages = []
            if is_dag:
                for lvl, gen in enumerate(nx.topological_generations(G)):
                    sorted_gen = sorted(gen, key=lambda cid: (G.nodes[cid]["order"], G.nodes[cid]["name"]))
                    stages.append({
                        "level": lvl,
                        "concepts": [G.nodes[cid]["name"] for cid in sorted_gen]
                    })

            data = {
                "docs": docs,
                "chunks": chunks,
                "concepts_count": concepts_count,
                "concept_names": concept_names,
                "dist": dist,
                "edges_count": len(edges),
                "edges": edges,
                "is_dag": is_dag,
                "stages": stages
            }

            with open("scratch/db_inspection_report.json", "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, ensure_ascii=False, default=str)
            print("Saved report to scratch/db_inspection_report.json")
    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
