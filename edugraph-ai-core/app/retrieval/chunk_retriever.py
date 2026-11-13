import asyncio
import logging
import re
from typing import List, Dict, Any, Optional
from app.config import settings
from app.core.embedding import embed_model
from app.db.neo4j_client import neo4j_client
from app.core.text_utils import normalize_text_unaccent

logger = logging.getLogger(__name__)

def compute_rrf(
    vector_results: List[Dict[str, Any]],
    fulltext_results: List[Dict[str, Any]],
    k_rrf: int = 60
) -> List[Dict[str, Any]]:
    """
    Reciprocal Rank Fusion (RRF) gộp hai danh sách xếp hạng.
    score(d) = sum(1 / (k_rrf + rank))
    """
    scores: Dict[str, float] = {}
    doc_map: Dict[str, Dict[str, Any]] = {}

    for rank, item in enumerate(vector_results, start=1):
        cid = item["id"]
        doc_map[cid] = item
        scores[cid] = scores.get(cid, 0.0) + (1.0 / (k_rrf + rank))

    for rank, item in enumerate(fulltext_results, start=1):
        cid = item["id"]
        doc_map[cid] = item
        scores[cid] = scores.get(cid, 0.0) + (1.0 / (k_rrf + rank))

    # Sort descending by RRF score
    sorted_ids = sorted(scores.keys(), key=lambda x: scores[x], reverse=True)
    fused = []
    for cid in sorted_ids:
        chunk_obj = dict(doc_map[cid])
        chunk_obj["rrf_score"] = scores[cid]
        fused.append(chunk_obj)
    return fused

def sanitize_fulltext_query(query: str) -> str:
    """
    Chuẩn hóa câu query cho Lucene fulltext search (bỏ các ký tự đặc biệt của cú pháp Lucene).
    """
    # Thay thế các toán tử đặc biệt của Lucene: + - && || ! ( ) { } [ ] ^ " ~ * ? : \ /
    cleaned = re.sub(r'[\+\-\&\|\!\(\)\{\}\[\]\^\"~*?:\\/]', ' ', query)
    tokens = [t.strip() for t in cleaned.split() if t.strip()]
    if not tokens:
        return "*"
    return " ".join(tokens)

async def _authorized_courses_have_chunks(authorized_course_ids: List[str]) -> bool:
    """Only retry when at least one chunk exists within the search scope."""
    if not authorized_course_ids:
        return False
    q = """
    MATCH (c:Chunk)
    WHERE c.course_id IN $authorized_course_ids
    RETURN 1 AS n
    LIMIT 1
    """
    async with neo4j_client.driver.session() as s:
        rec = await (await s.run(q, authorized_course_ids=authorized_course_ids)).single()
        return bool(rec and rec["n"])

async def retrieve_chunks(
    course_id: Optional[str],
    query: str,
    concept_ids: Optional[List[str]] = None,
    authorized_course_ids: Optional[List[str]] = None,
    max_chunks: int = 6,
    overfetch_k: Optional[int] = None
) -> List[Dict[str, Any]]:
    """
    Truy xuất chunks cho câu hỏi giải thích (explain):
    1. Lấy chunks gắn với concepts qua MENTIONS (is_evidence=true).
    2. Vector search (lọc course_id, adaptive overfetch khi top-k toàn cục
       không chứa chunk của course) + Fulltext search (lọc course_id).
    3. Gộp qua RRF (khoảng 10 dòng) và giới hạn tối đa max_chunks.
    """
    graph_chunks = []
    
    # All three queries use this list. A missing list falls back to the
    # authorized course supplied by the internal caller; an explicit empty
    # list grants no access and must never become an unfiltered search.
    if authorized_course_ids is None:
        authorized_course_ids = [course_id] if course_id else []
    if not authorized_course_ids:
        return []
    
    # 1. Graph chunks nếu có concept_ids
    if concept_ids:
        query_graph = """
        MATCH (c:Concept)<-[m:MENTIONS {is_evidence: true}]-(ch:Chunk)
        WHERE coalesce(c.status, 'ACTIVE') = 'ACTIVE'
          AND c.id IN $concept_ids
          AND c.course_id IN $authorized_course_ids
          AND ch.course_id IN $authorized_course_ids
        RETURN ch.id AS id, ch.text AS text, ch.doc_id AS doc_id, ch.page AS page, ch.index AS index
        ORDER BY ch.index ASC LIMIT 200
        """
        async with neo4j_client.driver.session() as session:
            res = await session.run(query_graph, course_id=course_id, concept_ids=concept_ids, authorized_course_ids=authorized_course_ids)
            graph_chunks = await res.data()

    # 2. Vector search
    query_vector = await embed_model.embed_batch([query])
    vec = query_vector[0] if query_vector else [0.0] * 1024

    vec_query = """
    CALL db.index.vector.queryNodes('chunk_embedding', $k, $vector)
    YIELD node, score
    WHERE node.course_id IN $authorized_course_ids
    RETURN node.id AS id, node.text AS text, node.doc_id AS doc_id, node.page AS page, node.index AS index, score
    ORDER BY score DESC LIMIT 200
    """

    # 3. Fulltext search
    ft_query_str = sanitize_fulltext_query(query)
    ft_cypher = """
    CALL db.index.fulltext.queryNodes('chunk_text', $ft_query)
    YIELD node, score
    WHERE node.course_id IN $authorized_course_ids
    RETURN node.id AS id, node.text AS text, node.doc_id AS doc_id, node.page AS page, node.index AS index, score
    ORDER BY score DESC LIMIT 200
    """

    vector_results = []
    fulltext_results = []

    fetch_k = overfetch_k if overfetch_k is not None else settings.VECTOR_OVERFETCH_K
    max_k = settings.VECTOR_OVERFETCH_MAX
    step = settings.VECTOR_OVERFETCH_STEP

    async with neo4j_client.driver.session() as session:
        # Adaptive overfetch: db.index.vector.queryNodes trả top-k toàn cục
        # rồi mới lọc course_id, nên retry với k lớn dần khi kết quả rỗng.
        while True:
            try:
                res_vec = await session.run(vec_query, k=fetch_k, vector=vec, course_id=course_id, authorized_course_ids=authorized_course_ids)
                vector_results = await res_vec.data()
            except Exception:
                # Nếu vector index chưa sẵn sàng trong mock/test
                vector_results = []
                break
            if vector_results or fetch_k >= max_k:
                break
            try:
                if not await _authorized_courses_have_chunks(authorized_course_ids):
                    break
            except Exception:
                break
            fetch_k = min(fetch_k * step, max_k)
            logger.warning(
                "vector overfetch retry course_id=%s fetch_k=%s", course_id, fetch_k
            )

        try:
            res_ft = await session.run(ft_cypher, ft_query=ft_query_str, course_id=course_id, authorized_course_ids=authorized_course_ids)
            fulltext_results = await res_ft.data()
        except Exception:
            # Nếu fulltext index chưa sẵn sàng
            pass

    return rank_chunks(query, graph_chunks, vector_results, fulltext_results, max_chunks)


def rank_chunks(query, graph_results, vector_results, fulltext_results, limit):
    """Fuse all channels before truncation, with lexical relevance and document diversity."""
    terms = set(re.findall(r"\w+", normalize_text_unaccent(query.casefold())))
    terms -= {"va", "la", "cua", "cho", "toi", "hay", "cac", "nhung", "mot", "the", "a", "an", "is", "of", "to", "in"}
    candidates = {}
    for channel, rows in [("graph", graph_results), ("vector", vector_results), ("fulltext", fulltext_results)]:
        for rank, row in enumerate(rows, 1):
            if not row.get("id") or not row.get("text", "").strip():
                continue
            if channel == "vector" and row.get("score", 1) < getattr(settings, "CHAT_MIN_VECTOR_SCORE", 0.35):
                continue
            item = candidates.setdefault(row["id"], {"chunk": dict(row), "score": 0.0})
            item["score"] += 60 / (60 + rank)
            if channel == "vector":
                item["score"] += max(0, min(1, row.get("score", 0)))
    for item in candidates.values():
        words = set(re.findall(r"\w+", normalize_text_unaccent(item["chunk"]["text"].casefold())))
        item["score"] += 1.5 * len(words & terms) / max(1, len(terms))
    ordered = sorted(candidates.values(), key=lambda item: (-item["score"], str(item["chunk"]["id"])))
    selected, deferred, counts, seen_text = [], [], {}, set()
    for item in ordered:
        chunk = item["chunk"]
        text = " ".join(chunk["text"].split()).casefold()
        if text in seen_text:
            continue
        seen_text.add(text)
        doc = chunk.get("doc_id", chunk["id"])
        if counts.get(doc, 0) >= 2:
            deferred.append(chunk)
        else:
            selected.append(chunk)
            counts[doc] = counts.get(doc, 0) + 1
    return (selected + deferred)[:limit]
