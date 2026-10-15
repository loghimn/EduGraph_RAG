import asyncio
import logging
import re
from typing import List, Dict, Any, Optional
from app.config import settings
from app.core.embedding import embed_model
from app.db.neo4j_client import neo4j_client

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

async def _course_has_chunks(course_id: str) -> bool:
    """Trả về True nếu course còn ít nhất một Chunk (chống retry vô tận khi course trống)."""
    q = "MATCH (c:Chunk {course_id: $course_id}) RETURN count(c) AS n"
    async with neo4j_client.driver.session() as s:
        rec = await (await s.run(q, course_id=course_id)).single()
        return bool(rec and rec["n"])

async def retrieve_chunks(
    course_id: str,
    query: str,
    concept_ids: Optional[List[str]] = None,
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
    
    # 1. Graph chunks nếu có concept_ids
    if concept_ids:
        query_graph = """
        MATCH (c:Concept {course_id: $course_id})<-[m:MENTIONS {is_evidence: true}]-(ch:Chunk {course_id: $course_id})
        WHERE c.id IN $concept_ids
        RETURN ch.id AS id, ch.text AS text, ch.doc_id AS doc_id, ch.page AS page, ch.index AS index
        ORDER BY ch.index ASC
        """
        async with neo4j_client.driver.session() as session:
            res = await session.run(query_graph, course_id=course_id, concept_ids=concept_ids)
            graph_chunks = await res.data()

    # 2. Vector search
    query_vector = await embed_model.embed_batch([query])
    vec = query_vector[0] if query_vector else [0.0] * 1024

    vec_query = """
    CALL db.index.vector.queryNodes('chunk_embedding', $k, $vector)
    YIELD node, score
    WHERE node.course_id = $course_id
    RETURN node.id AS id, node.text AS text, node.doc_id AS doc_id, node.page AS page, node.index AS index, score
    """

    # 3. Fulltext search
    ft_query_str = sanitize_fulltext_query(query)
    ft_cypher = """
    CALL db.index.fulltext.queryNodes('chunk_text', $ft_query)
    YIELD node, score
    WHERE node.course_id = $course_id
    RETURN node.id AS id, node.text AS text, node.doc_id AS doc_id, node.page AS page, node.index AS index, score
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
                res_vec = await session.run(vec_query, k=fetch_k, vector=vec, course_id=course_id)
                vector_results = await res_vec.data()
            except Exception:
                # Nếu vector index chưa sẵn sàng trong mock/test
                vector_results = []
                break
            if vector_results or fetch_k >= max_k:
                break
            try:
                if not await _course_has_chunks(course_id):
                    break
            except Exception:
                break
            fetch_k = min(fetch_k * step, max_k)
            logger.warning(
                "vector overfetch retry course_id=%s fetch_k=%s", course_id, fetch_k
            )

        try:
            res_ft = await session.run(ft_cypher, ft_query=ft_query_str, course_id=course_id)
            fulltext_results = await res_ft.data()
        except Exception:
            # Nếu fulltext index chưa sẵn sàng
            pass

    # Gộp vector + fulltext bằng RRF
    rrf_chunks = compute_rrf(vector_results, fulltext_results, k_rrf=60)

    # Ghép: Ưu tiên graph_chunks (is_evidence của concepts), sau đó bổ sung từ RRF
    selected = []
    seen_ids = set()

    for ch in graph_chunks:
        if ch["id"] not in seen_ids:
            seen_ids.add(ch["id"])
            selected.append(ch)
            if len(selected) >= max_chunks:
                break

    for ch in rrf_chunks:
        if len(selected) >= max_chunks:
            break
        if ch["id"] not in seen_ids:
            seen_ids.add(ch["id"])
            selected.append(ch)

    return selected
