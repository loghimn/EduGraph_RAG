import networkx as nx
import logging
from typing import List, Dict, Any, Tuple
from app.db.neo4j_client import neo4j_client

logger = logging.getLogger(__name__)

async def clean_graph_cycles(course_id: str, proposed_edges: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], List[List[str]]]:
    """
    N\u1ea1p \u0111\u1ed3 th\u1ecb t\u1eeb Neo4j (bao g\u1ed3m CONFIRMED, MANUAL, AUTO) c\u1ed9ng v\u1edbi c\u00e1c c\u1ea1nh \u0111\u1ec1 xu\u1ea5t (proposed_edges).
    T\u00ecm v\u00e0 c\u1eaft chu tr\u00ecnh b\u1eb1ng networkx.
    Tr\u1ea3 v\u1ec1 danh s\u00e1ch proposed_edges sau khi \u0111\u00e3 l\u1ecdc b\u1ecf nh\u1eefng c\u1ea1nh g\u00e2y v\u00f2ng l\u1eb7p, v\u00e0 danh s\u00e1ch xung \u0111\u1ed9t t\u1eeb c\u00e1c c\u1ea1nh c\u1ee9ng.
    """
    # 1. Fetch current edges
    query = """
    MATCH (src:Concept {course_id: $course_id})-[r:PREREQUISITE_OF]->(tgt:Concept {course_id: $course_id})
    WHERE coalesce(src.status, 'ACTIVE') = 'ACTIVE' AND coalesce(tgt.status, 'ACTIVE') = 'ACTIVE' AND r.status IN ['CONFIRMED', 'MANUAL', 'AUTO']
    RETURN src.id AS src_id, tgt.id AS tgt_id, r.status AS status, coalesce(r.score, 1.0) AS score
    ORDER BY src.id, tgt.id
    """
    async with neo4j_client.driver.session() as session:
        result = await session.run(query, course_id=course_id)
        db_edges = await result.data()
        
    G = nx.DiGraph()
    
    # 2. Add existing edges to Graph
    # Nh\u1eefng c\u1ea1nh trong DB
    for e in db_edges:
        if e['src_id'] != e['tgt_id']: # B\u1ecf self-loop
            # Ch\u00fa \u00fd: score l\u00e0 float
            G.add_edge(e['src_id'], e['tgt_id'], status=e['status'], score=float(e['score']), is_proposed=False)
            
    # 3. Add proposed edges to Graph
    # Proposed edges lu\u00f4n l\u00e0 AUTO
    proposed_map = {}
    for pe in proposed_edges:
        src = pe['source_id']
        tgt = pe['target_id']
        score = pe['score']
        
        if src == tgt: continue
        
        # N\u1ebfu c\u1ea1nh \u0111\u00e3 c\u00f3 s\u1eb5n trong DB (CONFIRMED/MANUAL), n\u00f3 kh\u00f4ng th\u1ec3 b\u1ecb overwrite
        # n\u00ean m\u00ecnh kh\u00f4ng th\u00eam v\u00e0o G v\u1edbi t\u01b0 c\u00e1ch l\u00e0 proposed (v\u00ec n\u00f3 \u0111\u00e3 l\u00e0 c\u1ea1nh DB c\u1ee9ng).
        # N\u1ebfu l\u00e0 AUTO t\u1eeb DB, \u0111\u00e2y l\u00e0 update score
        if G.has_edge(src, tgt):
            st = G[src][tgt]['status']
            if st in ['CONFIRMED', 'MANUAL']:
                continue # Gi\u1eef nguy\u00ean DB edge
            else:
                # Update t\u1ea1m th\u1eddi
                G[src][tgt]['score'] = score
                G[src][tgt]['is_proposed'] = True
                proposed_map[(src, tgt)] = pe
        else:
            G.add_edge(src, tgt, status='AUTO', score=score, is_proposed=True)
            proposed_map[(src, tgt)] = pe
            
    # 4. T\u00ecm v\u00e0 c\u1eaft chu tr\u00ecnh
    conflicts = []
    cut_edges = []
    
    # N\u1ea1p b\u1eb1ng nx v\u1edbi list nodes sorted (Graph dict duy tr\u00ec th\u1ee9 t\u1ef1 ch\u00e8n t\u1eeb Python 3.7+)
    
    while True:
        try:
            # find_cycle tr\u1ea3 v\u1ec1 1 chu tr\u00ecnh, n\u1ebfu c\u00f3
            cycle = nx.find_cycle(G)
        except nx.NetworkXNoCycle:
            break
            
        # cycle t\u1eeb nx c\u00f3 format: [(u, v), (v, w), ...]
        edges_in_cycle = [(u, v, G[u][v]) for u, v in cycle]
        
        # T\u00ecm c\u1ea1nh AUTO y\u1ebfu nh\u1ea5t \u0111\u1ec3 c\u1eaft
        # \u01afu ti\u00ean c\u1eaft AUTO, n\u1ebfu kh\u00f4ng c\u00f3 th\u00ec b\u00e1o l\u1ed7i v\u00e0 d\u1eebng c\u1eaft cycle \u0111\u00f3 b\u1eb1ng c\u00e1ch remove \u0111\u1ea1i 1 c\u1ea1nh t\u1ea1m th\u1eddi ho\u1eb7c x\u00f3a cycle kh\u1ecfi ti\u1ebfn tr\u00ecnh
        auto_edges = [(u, v, data) for u, v, data in edges_in_cycle if data['status'] == 'AUTO']
        
        if not auto_edges:
            # Cycle to\u00e0n l\u00e0 CONFIRMED / MANUAL => Xung \u0111\u1ed9t c\u1ee9ng
            logger.warning(f"Hard cycle detected: {cycle}")
            conflicts.append([f"{u}->{v}" for u, v in cycle])
            # \u0110\u1ec3 kh\u00f4ng b\u1ecb l\u1eb7p v\u00f4 h\u1ea1n nx.find_cycle, m\u00ecnh ph\u1ea3i g\u1ee1 1 c\u1ea1nh ra kh\u1ecfi G (tr\u00ean RAM th\u00f4i)
            u_remove, v_remove = cycle[0]
            G.remove_edge(u_remove, v_remove)
            continue
            
        # T\u00ecm AUTO c\u00f3 score th\u1ea5p nh\u1ea5t. N\u1ebfu b\u1eb1ng nhau, so s\u00e1nh chu\u1ed7i id u v
        def edge_sort_key(item):
            u, v, data = item
            return (data['score'], u, v)
            
        auto_edges.sort(key=edge_sort_key)
        weakest_u, weakest_v, weakest_data = auto_edges[0]
        
        logger.info(f"Cutting edge to break cycle: {weakest_u} -> {weakest_v} (score: {weakest_data['score']})")
        cut_edges.append((weakest_u, weakest_v))
        G.remove_edge(weakest_u, weakest_v)
        
        # N\u1ebfu c\u1ea1nh b\u1ecb c\u1eaft l\u00e0 c\u1ea1nh proposed, ta ph\u1ea3i x\u00f3a n\u00f3 kh\u1ecfi proposed_map
        if weakest_data.get('is_proposed', False):
            if (weakest_u, weakest_v) in proposed_map:
                del proposed_map[(weakest_u, weakest_v)]
                
    # Tr\u1ea3 v\u1ec1 nh\u1eefng proposed edges c\u00f2n s\u1ed1t l\u1ea1i
    surviving_proposed = list(proposed_map.values())
    return surviving_proposed, conflicts, cut_edges
