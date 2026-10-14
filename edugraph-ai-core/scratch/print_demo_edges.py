import asyncio
from app.db.neo4j_client import neo4j_client
import networkx as nx

async def main():
    await neo4j_client.connect()
    try:
        async with neo4j_client.driver.session() as session:
            res = await session.run("""
                MATCH (src:Concept {course_id: 'demo-sql'})-[r:PREREQUISITE_OF]->(tgt:Concept {course_id: 'demo-sql'})
                RETURN src.name AS src, tgt.name AS tgt, r.status AS status, r.score AS score, r.evidence AS evidence
                ORDER BY src.name, tgt.name
            """)
            edges = await res.data()
            print(f"Total demo-sql edges: {len(edges)}")
            for idx, e in enumerate(edges, 1):
                print(f"{idx}. {e['src']} -> {e['tgt']} | {e['status']} | {e['score']:.2f} | {e['evidence']}")

            # Check paths between JOIN, GROUP BY, view
            print("\n=== PATHS / ANCESTORS FOR view ===")
            G = nx.DiGraph()
            for e in edges:
                G.add_edge(e['src'], e['tgt'])

            # Find concept id for view
            res_c = await session.run("MATCH (c:Concept {course_id: 'demo-sql'}) RETURN c.id AS id, c.name AS name")
            c_data = await res_c.data()
            id_to_name = {c['id']: c['name'] for c in c_data}
            name_to_id = {c['name']: c['id'] for c in c_data}

            view_name = "view"
            print(f"Ancestors of '{view_name}': {nx.ancestors(G, view_name)}")
            print(f"Predecessors of '{view_name}': {list(G.predecessors(view_name))}")
            if "JOIN" in G:
                print(f"Ancestors of 'JOIN': {nx.ancestors(G, 'JOIN')}")
                print(f"Successors of 'JOIN': {list(G.successors('JOIN'))}")
            if "GROUP BY" in G:
                print(f"Ancestors of 'GROUP BY': {nx.ancestors(G, 'GROUP BY')}")
                print(f"Predecessors of 'GROUP BY': {list(G.predecessors('GROUP BY'))}")
                print(f"Successors of 'GROUP BY': {list(G.successors('GROUP BY'))}")

            print("\nPaths from JOIN to view:")
            try:
                for p in nx.all_simple_paths(G, "JOIN", "view"):
                    print("  Path:", " -> ".join(p))
            except Exception as ex:
                print("  No path:", ex)

            print("\nPaths from GROUP BY to view:")
            try:
                for p in nx.all_simple_paths(G, "GROUP BY", "view"):
                    print("  Path:", " -> ".join(p))
            except Exception as ex:
                print("  No path:", ex)

            print("\nPaths from view to GROUP BY:")
            try:
                for p in nx.all_simple_paths(G, "view", "GROUP BY"):
                    print("  Path:", " -> ".join(p))
            except Exception as ex:
                print("  No path:", ex)

    finally:
        await neo4j_client.close()

if __name__ == "__main__":
    asyncio.run(main())
