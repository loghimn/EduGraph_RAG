import numpy as np
from typing import List, Dict, Any, Tuple, Set
from app.db.neo4j_client import neo4j_client
from app.config import settings

async def get_all_concepts_for_prereq(course_id: str) -> List[Dict[str, Any]]:
    query = """
    MATCH (c:Concept {course_id: $course_id})
    WHERE coalesce(c.status, 'ACTIVE') = 'ACTIVE'
    RETURN c.id AS id, c.name AS name, c.aliases AS aliases, c.embedding AS embedding
    """
    async with neo4j_client.driver.session() as session:
        result = await session.run(query, course_id=course_id)
        return await result.data()

async def get_chunk_cooccurrences(course_id: str) -> Dict[str, Set[str]]:
    query = """
    MATCH (c1:Concept {course_id: $course_id})<-[:MENTIONS]-(:Chunk)-[:MENTIONS]->(c2:Concept {course_id: $course_id})
    WHERE coalesce(c1.status, 'ACTIVE') = 'ACTIVE' AND coalesce(c2.status, 'ACTIVE') = 'ACTIVE' AND c1 <> c2
    RETURN c1.id AS id, collect(DISTINCT c2.id) AS neighbors
    """
    async with neo4j_client.driver.session() as session:
        result = await session.run(query, course_id=course_id)
        data = await result.data()
        return {r["id"]: set(r["neighbors"]) for r in data}

async def get_existing_directed_edges(course_id: str) -> Dict[str, Dict[str, str]]:
    """
    Tr\u1ea3 v\u1ec1 mapping: target_id -> {source_id: status}
    \u0110\u1ec3 bi\u1ebft C -> T \u0111\u00e3 c\u00f3 c\u1ea1nh ch\u01b0a v\u00e0 tr\u1ea1ng th\u00e1i l\u00e0 g\u00ec.
    """
    query = """
    MATCH (src:Concept {course_id: $course_id})-[r:PREREQUISITE_OF]->(tgt:Concept {course_id: $course_id})
    WHERE coalesce(src.status, 'ACTIVE') = 'ACTIVE' AND coalesce(tgt.status, 'ACTIVE') = 'ACTIVE'
    RETURN tgt.id AS tgt_id, src.id AS src_id, r.status AS status
    """
    async with neo4j_client.driver.session() as session:
        result = await session.run(query, course_id=course_id)
        data = await result.data()
        
        edges = {}
        for r in data:
            tgt = r["tgt_id"]
            src = r["src_id"]
            st = r["status"]
            if tgt not in edges:
                edges[tgt] = {}
            edges[tgt][src] = st
        return edges

def compute_cosine_similarity(emb1: List[float], emb2: List[float]) -> float:
    # Vector t\u1eeb DB \u0111\u00e3 l\u00e0 unit vector, n\u00ean ch\u1ec9 c\u1ea7n dot product
    return float(np.dot(emb1, emb2))

async def build_candidate_pairs(course_id: str) -> Dict[str, List[Dict[str, Any]]]:
    """
    X\u00e2y d\u1ef1ng danh s\u00e1ch \u1ee9ng vi\u00ean (t\u1ed1i \u0111a 15) cho m\u1ed7i target concept.
    Tr\u1ea3 v\u1ec1: target_id -> list of candidate concept dicts
    """
    concepts = await get_all_concepts_for_prereq(course_id)
    if not concepts:
        return {}
        
    cooccurrences = await get_chunk_cooccurrences(course_id)
    existing_edges = await get_existing_directed_edges(course_id)
    
    # Chu\u1ea9n b\u1ecb numpy array cho embeddings
    concept_ids = [c["id"] for c in concepts]
    concept_map = {c["id"]: c for c in concepts}
    
    # L\u1ecdc nh\u1eefng concept kh\u00f4ng c\u00f3 embedding h\u1ee3p l\u1ec7 (n\u1ebfu c\u00f3)
    valid_concepts = [c for c in concepts if c["embedding"] is not None]
    if not valid_concepts:
        return {c["id"]: [] for c in concepts}
        
    embeddings_matrix = np.array([c["embedding"] for c in valid_concepts])
    valid_ids = [c["id"] for c in valid_concepts]
    
    # T\u00ednh cosine similarity matrix
    sim_matrix = np.dot(embeddings_matrix, embeddings_matrix.T)
    
    candidates_dict = {}
    
    for i, tgt_id in enumerate(valid_ids):
        tgt_edges = existing_edges.get(tgt_id, {})
        tgt_cooc = cooccurrences.get(tgt_id, set())
        
        # Get similarities for tgt
        sims = sim_matrix[i]
        
        # L\u1ea5y top 30 indices (bao g\u1ed3m c\u1ea3 b\u1ea3n th\u00e2n n\u00f3, s\u1ebd b\u1ecb lo\u1ea1i sau)
        top_k = min(settings.PREREQ_TOPK_COSINE + 1, len(valid_ids))
        top_indices = np.argpartition(sims, -top_k)[-top_k:]
        # Sort top indices by similarity descending
        top_indices = top_indices[np.argsort(-sims[top_indices])]
        
        cosine_cands = [valid_ids[idx] for idx in top_indices if valid_ids[idx] != tgt_id]
        
        final_cands = []
        added_ids = set()
        
        # Helper \u0111\u1ec3 add candidate
        def add_cand(cid):
            if cid == tgt_id or cid in added_ids:
                return
            # Lo\u1ea1i c\u00e1c c\u1ea1nh \u0111\u00e3 c\u00f3 status CONFIRMED, MANUAL, REJECTED t\u1eeb cid -> tgt
            st = tgt_edges.get(cid)
            if st in ["CONFIRMED", "MANUAL", "REJECTED"]:
                return
            final_cands.append(concept_map[cid])
            added_ids.add(cid)
            
        # 1. \u01af\u01b0 ti\u00ean c\u00f9ng chunk
        for cid in tgt_cooc:
            if len(final_cands) >= settings.PREREQ_CANDIDATES:
                break
            add_cand(cid)
            
        # 2. B\u1ed5 sung t\u1eeb cosine
        for cid in cosine_cands:
            if len(final_cands) >= settings.PREREQ_CANDIDATES:
                break
            add_cand(cid)
            
        candidates_dict[tgt_id] = final_cands
        
    return candidates_dict
