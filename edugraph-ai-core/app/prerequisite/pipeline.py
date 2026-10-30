import logging
import asyncio
import time
from typing import Dict, Any, List
from app.db.neo4j_client import neo4j_client
from app.config import settings

from app.prerequisite.candidate_pairs import build_candidate_pairs
from app.prerequisite.llm_judge import judge_prerequisites
from app.prerequisite.rule_signals import evaluate_rule_signals
from app.prerequisite.fusion import fuse_scores
from app.prerequisite.graph_cleaner import clean_graph_cycles

logger = logging.getLogger(__name__)

async def get_concept_evidence(course_id: str) -> Dict[str, List[str]]:
    query = """

    MATCH (c:Concept {course_id: $course_id})<-[m:MENTIONS]-(ch:Chunk)
    WHERE coalesce(c.status, 'ACTIVE') = 'ACTIVE'
      AND (m.is_evidence = true OR ch.is_evidence = true)
    WITH c, ch.text AS text
    ORDER BY ch.id
    RETURN c.id AS id, collect(text) AS evidence_texts
    """
    async with neo4j_client.driver.session() as session:
        result = await session.run(query, course_id=course_id)
        data = await result.data()
        return {r["id"]: r["evidence_texts"] for r in data}

async def run_prerequisite_pipeline(course_id: str, new_concept_ids: set = None):
    logger.info(f"Bắt đầu pipeline tiên quyết cho {course_id}...")
    if new_concept_ids:
        logger.info(
            f"[{course_id}] Incremental mode: {len(new_concept_ids)} concept mới "
            f"→ chỉ re-judge concept bị ảnh hưởng"
        )

    t_start = time.perf_counter()

    # 1. candidate_pairs
    t0 = time.perf_counter()
    candidates_dict = await build_candidate_pairs(course_id)
    num_with_cands = sum(1 for c in candidates_dict.values() if c)
    total_cands = sum(len(c) for c in candidates_dict.values())
    logger.info(
        f"[{course_id}] Ung vien: {len(candidates_dict)} target "
        f"({num_with_cands} co ung vien, tong {total_cands} cap) ({time.perf_counter() - t0:.2f}s)"
    )

    # ── INCREMENTAL FILTER ───────────────────────────────────────────────────────
    # Chỉ re-judge target concept nếu:
    #   (a) Bản thân là concept mới, HOẶC
    #   (b) Có ít nhất 1 candidate mới trong danh sách ứng viên của nó.
    # Concept cũ hoàn toàn không liên quan → bỏ qua, giữ nguyên cạnh cũ trên DB.
    if new_concept_ids:
        filtered_dict = {}
        skipped = 0
        for tgt_id, cands in candidates_dict.items():
            cand_ids = {c["id"] for c in cands}
            if tgt_id in new_concept_ids or cand_ids & new_concept_ids:
                filtered_dict[tgt_id] = cands
            else:
                skipped += 1
        logger.info(
            f"[{course_id}] Incremental filter: {len(filtered_dict)} cần judge, "
            f"{skipped} concept cũ bỏ qua (giữ cạnh cũ)"
        )
        candidates_dict = filtered_dict
    # ────────────────────────────────────────────────────────────────────────────

    t0 = time.perf_counter()
    evidence_dict = await get_concept_evidence(course_id)
    logger.info(
        f"[{course_id}] Evidence: {len(evidence_dict)} concept co evidence "
        f"({time.perf_counter() - t0:.2f}s)"
    )

    # Map để tra cứu target info nhanh
    query = "MATCH (c:Concept {course_id: $course_id}) WHERE coalesce(c.status, 'ACTIVE') = 'ACTIVE' RETURN c.id AS id, c.name AS name, c.aliases AS aliases"
    async with neo4j_client.driver.session() as session:
        res = await session.run(query, course_id=course_id)
        concept_data = await res.data()
    concept_map = {c["id"]: c for c in concept_data}

    all_proposed_edges = []

    async def process_target(tgt_id: str, cands: List[Dict[str, Any]]):
        if not cands:
            return []
        if tgt_id not in concept_map:
            return []

        tgt_obj = concept_map[tgt_id]
        tgt_evidences = evidence_dict.get(tgt_id, [])

        # 2. llm_judge
        llm_results = await judge_prerequisites(tgt_obj, cands, tgt_evidences)

        # 3. rule_signals
        rule_scores = {}
        for cand in cands:
            score = evaluate_rule_signals(tgt_obj["name"], cand["name"], tgt_evidences)
            rule_scores[cand["id"]] = score

        # 4. fusion
        edges = fuse_scores(tgt_id, llm_results, rule_scores)
        return edges

    tasks = []
    for tgt_id, cands in candidates_dict.items():
        tasks.append(process_target(tgt_id, cands))

    results = await asyncio.gather(*tasks)

    for edges in results:
        all_proposed_edges.extend(edges)

    logger.info(
        f"[{course_id}] Judge + Fusion xong: {len(all_proposed_edges)} canh de xuat"
    )

        
    # 5. graph_cleaner
    t_clean = time.perf_counter()
    surviving_edges, conflicts, cut_edges = await clean_graph_cycles(course_id, all_proposed_edges)
    logger.info(
        f"[{course_id}] Don chu trinh: {len(all_proposed_edges)} de xuat → "
        f"{len(surviving_edges)} song sot | cat {len(cut_edges)} canh | "
        f"{len(conflicts)} xung dong cung ({time.perf_counter() - t_clean:.2f}s)"
    )
    
    # 6. Ghi c\u1ea1nh
    rescored_ids = list(candidates_dict.keys())
    cut_edge_dicts = [{"source_id": u, "target_id": v} for u, v in cut_edges]
    t_save = time.perf_counter()
    await save_prerequisite_edges(course_id, surviving_edges, rescored_ids, cut_edge_dicts)
    logger.info(
        f"[{course_id}] Ghi {len(surviving_edges)} canh PREREQUISITE_OF xong "
        f"({time.perf_counter() - t_save:.2f}s) | tong thoi gian tien quyet "
        f"{time.perf_counter() - t_start:.2f}s"
    )
    
    logger.info(f"Ho\u00e0n t\u1ea5t ti\u00ean quy\u1ebft cho {course_id}. \u0110\u1ec1 xu\u1ea5t: {len(all_proposed_edges)}, Ghi l\u1ea1i: {len(surviving_edges)}, Xung \u0111\u1ed9t c\u1ee9ng: {len(conflicts)}")

async def save_prerequisite_edges(course_id: str, edges: List[Dict[str, Any]], rescored_ids: List[str], cut_edges: List[Dict[str, str]]):
    query = """
    MATCH (course:Course {id: $course_id})
    SET course.concept_write_lock = coalesce(course.concept_write_lock, 0) + 1
    WITH count(*) AS locked
    // 1. Delete AUTO edges that are no longer selected or were cut
    MATCH (s:Concept {course_id: $course_id})-[r:PREREQUISITE_OF {status: 'AUTO'}]->(t:Concept {course_id: $course_id})
    WHERE coalesce(s.status, 'ACTIVE') = 'ACTIVE' AND coalesce(t.status, 'ACTIVE') = 'ACTIVE' AND ((t.id IN $rescored_ids AND NOT any(e IN $edges WHERE e.source_id = s.id AND e.target_id = t.id))
       OR any(ce IN $cut_edges WHERE ce.source_id = s.id AND ce.target_id = t.id))
    DELETE r
    WITH count(r) AS deleted_auto

    // 2. Upsert new edges
    UNWIND $edges AS edge
    MATCH (src:Concept {id: edge.source_id, course_id: $course_id})
    WHERE coalesce(src.status, 'ACTIVE') = 'ACTIVE'
    CALL (edge) {
        MATCH (tgt:Concept {id: edge.target_id, course_id: $course_id})
        WHERE coalesce(tgt.status, 'ACTIVE') = 'ACTIVE'
        RETURN tgt
    }
    MERGE (src)-[r:PREREQUISITE_OF]->(tgt)
    ON CREATE SET 
        r.status = 'AUTO',
        r.score = edge.score,
        r.llm_score = coalesce(edge.llm_score, 0),
        r.rule_score = coalesce(edge.rule_score, 0),
        r.source = edge.source,
        r.source_type = edge.source,
        r.evidence = edge.evidence,
        r.evidence_chunk_id = edge.evidence_chunk_id,
        r.model = edge.model,
        r.prompt_version = edge.prompt_version
    ON MATCH SET
        r.score = CASE WHEN r.status = 'AUTO' THEN edge.score ELSE r.score END,
        r.llm_score = CASE WHEN r.status = 'AUTO' THEN coalesce(edge.llm_score, 0) ELSE r.llm_score END,
        r.rule_score = CASE WHEN r.status = 'AUTO' THEN coalesce(edge.rule_score, 0) ELSE r.rule_score END,
        r.source = CASE WHEN r.status = 'AUTO' THEN edge.source ELSE r.source END,
        r.source_type = CASE WHEN r.status = 'AUTO' THEN edge.source ELSE r.source_type END,
        r.evidence = CASE WHEN r.status = 'AUTO' THEN edge.evidence ELSE r.evidence END,
        r.evidence_chunk_id = CASE WHEN r.status = 'AUTO' THEN edge.evidence_chunk_id ELSE r.evidence_chunk_id END,
        r.model = CASE WHEN r.status = 'AUTO' THEN edge.model ELSE r.model END,
        r.prompt_version = CASE WHEN r.status = 'AUTO' THEN edge.prompt_version ELSE r.prompt_version END
        
    WITH count(src) AS updated
    
    // 3. Increment Course version in same transaction
    MATCH (c:Course {id: $course_id})
    SET c.version = coalesce(c.version, 0) + 1
    """
    async with neo4j_client.driver.session() as session:
        await session.run(query, course_id=course_id, edges=edges, rescored_ids=rescored_ids, cut_edges=cut_edges)
