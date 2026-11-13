import asyncio
import logging
import time
from contextvars import ContextVar
from typing import Dict
from app.db.repo import get_course_concepts, save_ingestion_transaction
from app.core.embedding import embed_model
from app.core.text_utils import normalize_text_unaccent
from app.config import settings
from app.db.neo4j_client import neo4j_client
from app.core.document_tasks import gather_owned, to_thread_owned

logger = logging.getLogger(__name__)
draft_heartbeat = ContextVar("draft_heartbeat", default=None)

# Locks per course_id — đảm bảo an toàn DB khi nhiều document cùng course
# Không dùng global semaphore vì nó block toàn server (chat, API khác)
course_locks: Dict[str, asyncio.Lock] = {}

def get_course_lock(course_id: str) -> asyncio.Lock:
    if course_id not in course_locks:
        course_locks[course_id] = asyncio.Lock()
    return course_locks[course_id]

async def update_heartbeat(doc_id: str, progress: str = None, current_step: str = None):
    if draft_heartbeat.get() is not None:
        draft_heartbeat.get()(progress=progress, current_step=current_step)
        return
    set_clauses = ["d.updated_at = timestamp()"]
    parameters = {"doc_id": doc_id}
    if progress is not None:
        set_clauses.append("d.progress = $progress")
        parameters["progress"] = progress
    if current_step is not None:
        set_clauses.append("d.current_step = $current_step")
        set_clauses.append("d.progress = null")
        parameters["current_step"] = current_step
    query = f"""
    MATCH (d:Document {{id: $doc_id}})
    SET {", ".join(set_clauses)}
    """
    async with neo4j_client.driver.session() as session:
        await session.run(query, **parameters)

def _prepare_text(doc_id, course_id, filename):
    from app.ingestion.loaders import load_document
    from app.ingestion.cleaner import clean_text, remove_repeating_headers_footers
    from app.ingestion.chunker import chunk_document
    pages = load_document(doc_id, filename)
    cleaned = [(page, clean_text(text)) for page, text in pages]
    chunks = chunk_document(remove_repeating_headers_footers(cleaned), doc_id, course_id)
    for chunk in chunks:
        chunk["text_unaccent"] = normalize_text_unaccent(chunk["text"])
    return chunks, len(pages)


async def _filter_document_concepts(doc_id, chunks, embeddings):
    from app.concept_extraction.candidates import extract_candidates
    from app.concept_extraction.llm_filter import process_chunk_group
    await update_heartbeat(doc_id, current_step="EXTRACTING_KEYWORDS")

    # KeyBERT Candidates (to_thread)
    t_cand = time.perf_counter()
    candidates = await to_thread_owned(extract_candidates, chunks, embeddings)
    logger.info(
        f"[{doc_id}] ✔ KeyBERT candidates xong: {sum(len(c) for c in candidates)} ứng viên "
        f"({time.perf_counter() - t_cand:.2f}s)"
    )

    await update_heartbeat(doc_id, current_step="FILTERING_CONCEPTS")

    # LLM Filter (asyncio gather)
    # Ghép nhóm chunks (CHUNKS_PER_LLM_CALL)
    total_groups = max(1, len(range(0, len(chunks), settings.CHUNKS_PER_LLM_CALL)))
    completed_groups = 0
    logger.info(
        f"[{doc_id}] ▶ LLM filter: {len(chunks)} chunks → {total_groups} nhóm "
        f"(CHUNKS_PER_LLM_CALL={settings.CHUNKS_PER_LLM_CALL})"
    )

    async def process_and_report(idx, g_chunks, g_cands):
        nonlocal completed_groups
        res = await process_chunk_group(idx, g_chunks, g_cands)
        completed_groups += 1
        logger.info(f"[{doc_id}]   • Nhóm {completed_groups}/{total_groups} xong")
        await update_heartbeat(doc_id, progress=f"{completed_groups}/{total_groups}")
        return res

    tasks = []
    for i in range(0, len(chunks), settings.CHUNKS_PER_LLM_CALL):
        group_chunks = chunks[i:i+settings.CHUNKS_PER_LLM_CALL]
        group_cands = candidates[i:i+settings.CHUNKS_PER_LLM_CALL]
        tasks.append(process_and_report(i, group_chunks, group_cands))

    t_llm = time.perf_counter()
    group_results = await gather_owned(*tasks)
    logger.info(
        f"[{doc_id}] ✔ LLM filter xong ({time.perf_counter() - t_llm:.2f}s)"
    )

    # Gộp kết quả LLM
    new_concepts = []
    total_returned = 0
    total_dropped_no_ev = 0
    total_dropped_fmt = 0
    dropped_concepts = []

    for concepts, stats in group_results:
        new_concepts.extend(concepts)
        total_returned += stats["total_returned"]
        total_dropped_no_ev += stats["dropped_no_evidence"]
        total_dropped_fmt += stats["dropped_format"]
        dropped_concepts.extend(stats.get("dropped_list", []))

    logger.info(
        f"[{doc_id}] ✔ LLM trả về: {total_returned} concept | "
        f"loại evidence: {total_dropped_no_ev} | loại format: {total_dropped_fmt} | "
        f"còn lại: {len(new_concepts)}"
    )

    return new_concepts, dropped_concepts, total_dropped_no_ev, total_returned


async def _previous_affected_ids(doc_id, course_id):
    # Capture concepts previously mentioned by this document before its old
    # chunks/mentions are replaced, so prerequisite inference can re-judge
    # concepts that lose evidence during re-ingestion.
    async with neo4j_client.driver.session() as session:
        _res = await session.run(
            "MATCH (ch:Chunk {doc_id: $doc_id})-[:MENTIONS]->(c:Concept {course_id: $course_id}) "
            "RETURN DISTINCT c.id AS id",
            doc_id=doc_id, course_id=course_id)
        old_mentioned_concept_ids = {r["id"] for r in await _res.data() if r.get("id")}
        _pres = await session.run(
            "MATCH (d:Document {id: $doc_id, course_id: $course_id}) "
            "RETURN d.pending_affected_concept_ids AS ids",
            doc_id=doc_id, course_id=course_id)
        _prow = await _pres.single()
        if _prow and _prow.get("ids"):
            old_mentioned_concept_ids |= set(_prow["ids"])

    return old_mentioned_concept_ids


async def _embed_concepts(doc_id, new_concepts):
    # Embed concepts before merging so cross-language cosine matching can run.
    concept_texts_to_embed = []
    for nc in new_concepts:
        aliases = nc.get("aliases", [])[:settings.CONCEPT_EMBED_MAX_ALIASES]
        emb_str = nc["canonical"]
        if aliases:
            emb_str += "; " + "; ".join(aliases)
        concept_texts_to_embed.append(emb_str)

    if concept_texts_to_embed:
        c_embs = await embed_model.embed_batch(concept_texts_to_embed)
        for i, nc in enumerate(new_concepts):
            nc["embedding"] = c_embs[i]
            nc["embedding_model"] = "BAAI/bge-m3"
        logger.info(
            f"[{doc_id}] ✔ Embed {len(concept_texts_to_embed)} concept mới xong"
        )



async def _collect_mentions(doc_id, chunks, new_concepts, db_concepts, merged_new):
    from app.concept_extraction.mention_scanner import scan_mentions
    # Sinh Mentions: từ LLM và từ Scanner
    mentions = []

    # MENTIONS (a): từ LLM
    # Duyệt danh sách new_concepts ban đầu (kể cả những cái bị gộp)
    for nc in new_concepts:
        # Nếu bị gộp, nó sẽ có merge_target_id, nhưng ta map ngược bằng key
        mentions.append({
            "chunk_id": nc["evidence_chunk_id"],
            "concept_key": nc.get("key"),
            "is_evidence": True,
            "start": nc["evidence_start"],
            "end": nc["evidence_end"]
        })

    # MENTIONS (b): từ Scanner
    all_concepts = db_concepts + merged_new
    final_mentions = {}
    scanned_mentions = await to_thread_owned(scan_mentions, chunks, all_concepts)
    logger.info(
        f"[{doc_id}] ✔ Scan mentions: {len(scanned_mentions)} từ scanner + {len(mentions)} từ LLM"
    )
    for m in scanned_mentions:
        pair = (m['chunk_id'], m['concept_key'])
        final_mentions[pair] = m
    for m in mentions:
        pair = (m['chunk_id'], m['concept_key'])
        if pair in final_mentions:
            final_mentions[pair]['is_evidence'] = True
            final_mentions[pair]['start'] = m['start']
            final_mentions[pair]['end'] = m['end']
        else:
            m['count'] = 1
            final_mentions[pair] = m
    mentions = list(final_mentions.values())

    return mentions


def _changed_concept_ids(old_mentioned_concept_ids, mentions, db_concepts, merged_new):
    # Concepts whose mentions/evidence changed in this ingestion. Existing
    # targets that gained new evidence must be re-judged even when the new
    # concept is absent from their capped candidate list.
    mentioned_keys = {m.get("concept_key") for m in mentions if m.get("concept_key")}
    changed_concept_ids = set(old_mentioned_concept_ids)
    for c in db_concepts:
        if c.get("key") in mentioned_keys and c.get("id"):
            changed_concept_ids.add(c["id"])
    for nc in merged_new:
        if nc.get("merge_target_id"):
            changed_concept_ids.add(nc["merge_target_id"])

    return changed_concept_ids


def _set_canonical_names(concepts):
    for concept in concepts:
        if "canonical" in concept:
            concept["name"] = concept["canonical"]


async def process_document_pipeline(doc_id: str, course_id: str, filename: str, *, draft: bool = False):
    """
    Thực thi pipeline trích xuất. Chạy hoàn toàn trong RAM, cuối cùng ghi DB 1 lượt.
    Mỗi document chạy độc lập; course_lock bảo vệ DB khi 2 document cùng course.
    Trả về dict gồm valid_concepts và new_concept_ids (dùng cho incremental prereq).
    """
    t_start = time.perf_counter()
    course_lock = get_course_lock(course_id)
    logger.info(f"[{doc_id}] ▶ BẮT ĐẦU trích xuất (course={course_id}, file={filename})")

    from app.concept_extraction.merger import merge_concepts

    await update_heartbeat(doc_id, current_step="EXTRACTING_TEXT_AND_CHUNKING")

    t_io = time.perf_counter()
    chunks, num_pages = await to_thread_owned(_prepare_text, doc_id, course_id, filename)
    logger.info(
        f"[{doc_id}] ✔ Load/Clean/Chunk xong: {num_pages} trang → {len(chunks)} chunks "
        f"({time.perf_counter() - t_io:.2f}s)"
    )
    if not chunks:
        if draft:
            raise ValueError("Document contains no extractable text")
        logger.warning(f"[{doc_id}] ✖ Tài liệu trống hoặc không hợp lệ — dừng pipeline.")
        return

    await update_heartbeat(doc_id, current_step="GENERATING_CHUNK_EMBEDDINGS")

    # Tính Embedding cho các chunk (chạy 1 lần, dùng cho cả KeyBERT và DB)
    chunk_texts = [c["text"] for c in chunks]
    t_embed = time.perf_counter()
    embeddings = await embed_model.embed_batch(chunk_texts)
    logger.info(
        f"[{doc_id}] ✔ Embed {len(chunk_texts)} chunks xong ({time.perf_counter() - t_embed:.2f}s)"
    )
    for i, c in enumerate(chunks):
        c["embedding"] = embeddings[i]
        c["embedding_model"] = "BAAI/bge-m3"

    new_concepts, dropped_concepts, total_dropped_no_ev, total_returned = await _filter_document_concepts(doc_id, chunks, embeddings)

    await update_heartbeat(doc_id, current_step="MERGING_CONCEPTS")

    # Locking khi đụng đến DB (chỉ lock phần đọc-ghi DB, không lock toàn pipeline)
    async with course_lock:
        # Lấy concept hiện tại của course
        db_concepts = await get_course_concepts(course_id)
        logger.info(
            f"[{doc_id}] ✔ Đã nạp {len(db_concepts)} concept hiện có của course để merge"
        )

        old_mentioned_concept_ids = await _previous_affected_ids(doc_id, course_id)

        await _embed_concepts(doc_id, new_concepts)

        # Gộp và xử lý
        t_merge = time.perf_counter()
        merged_new, merge_cands = await to_thread_owned(merge_concepts, new_concepts, db_concepts)
        logger.info(
            f"[{doc_id}] ✔ Merge xong: {len(new_concepts)} → {len(merged_new)} concept mới "
            f"({time.perf_counter() - t_merge:.2f}s)"
        )

        # Tính order cho các concept mới
        for nc in merged_new:
            fc_index = nc.get("first_chunk_index", 0)
            # Dùng seq mặc định 1 vì Python không trực tiếp quản lý seq, Java gửi nếu cần.
            seq = 1
            nc["order"] = seq * 100000 + fc_index

        mentions = await _collect_mentions(doc_id, chunks, new_concepts, db_concepts, merged_new)

        changed_concept_ids = _changed_concept_ids(old_mentioned_concept_ids, mentions, db_concepts, merged_new)

        await update_heartbeat(doc_id)

        # Cuối cùng, Ghi 1 transaction
        logger.info(
            f"[{doc_id}] ▶ Ghi DB: {len(chunks)} chunks, {len(merged_new)} concepts, "
            f"{len(mentions)} mentions"
        )
        await update_heartbeat(doc_id, current_step="SAVING_GRAPH")
        t_save = time.perf_counter()
        _set_canonical_names(merged_new)
        if draft:
            return {"chunks": chunks, "concepts": merged_new, "mentions": mentions,
                    "all_concepts": db_concepts + merged_new}
        record = await save_ingestion_transaction(
            doc_id=doc_id,
            course_id=course_id,
            chunks=chunks,
            concepts=merged_new,
            mentions=mentions,
            affected_concept_ids=list(changed_concept_ids)
        )

        logger.info(
            f"[{doc_id}] ✔ Ghi DB xong ({time.perf_counter() - t_save:.2f}s)"
        )
        if record:
            logger.info(
                f"[{doc_id}] ✔ Đã lưu: {len(chunks)} chunks, {len(merged_new)} concepts, "
                f"{len(mentions)} mentions | Course version: {record['version']} | "
                f"Orphans xóa: {record.get('deleted_orphans', 0)}"
            )
        logger.info(f"[{doc_id}] ✔ HOÀN TẤT trích xuất ({time.perf_counter() - t_start:.2f}s)")

        # Trả về new_concept_ids để incremental prerequisite pipeline chỉ re-judge
        # những concept mới + concept cũ có liên hệ với concept mới.
        new_concept_ids = {nc["id"] for nc in merged_new if "id" in nc}

        return {
            "valid_concepts": merged_new,
            "dropped_concepts": dropped_concepts,
            "merge_candidates": merge_cands,
            "new_concept_ids": new_concept_ids,
            "changed_concept_ids": changed_concept_ids,
            "stats": {
                "dropped_evidence": total_dropped_no_ev,
                "total_returned": total_returned
            }
        }
