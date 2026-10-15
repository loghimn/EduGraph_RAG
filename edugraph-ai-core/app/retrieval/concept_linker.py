import re
import unicodedata
from typing import List, Dict, Any, Optional, Tuple
import numpy as np
from rapidfuzz import fuzz

from app.config import settings
from app.core.text_utils import normalize_concept_key, normalize_text_unaccent
from app.db.neo4j_client import neo4j_client

def extract_ngrams(tokens: List[str], max_n: int = 4) -> List[Tuple[str, int, int]]:
    """
    Sinh n-grams từ 1 đến max_n từ danh sách tokens.
    Sắp xếp theo thứ tự longest-match-first (n giảm dần).
    Trả về list of (ngram_text, start_idx, end_idx).
    """
    ngrams = []
    n_tokens = len(tokens)
    for n in range(min(max_n, n_tokens), 0, -1):
        for i in range(n_tokens - n + 1):
            ngram = " ".join(tokens[i:i+n])
            ngrams.append((ngram, i, i+n))
    return ngrams

async def get_course_concepts_for_linking(course_id: str) -> List[Dict[str, Any]]:
    """
    Tải danh sách concepts và embeddings của course để phục vụ linker.
    """
    query = """
    MATCH (c:Concept {course_id: $course_id})
    RETURN c.id AS id, c.name AS name, coalesce(c.aliases, []) AS aliases, c.embedding AS embedding
    """
    async with neo4j_client.driver.session() as session:
        res = await session.run(query, course_id=course_id)
        return await res.data()

def link_concepts_from_data(
    message: str,
    concepts: List[Dict[str, Any]],
    message_embedding: Optional[List[float]] = None
) -> Dict[str, Any]:
    """
    Thuật toán link concepts từ văn bản và danh sách concepts đã nạp.
    Trả về dict:
      - "concept_ids": list of concept IDs nếu link thành công
      - "clarify": list of {"id": ..., "name": ...} nếu cần hỏi lại
    """
    if not concepts:
        return {"concept_ids": [], "clarify": None}

    # 1. Tiền xử lý các concept: lưu bản có dấu và không dấu
    # index: id -> {name, norm_name, unaccent_name, aliases, norm_aliases, unaccent_aliases, embedding}
    concept_map = {}
    for c in concepts:
        cid = c["id"]
        cname = c["name"]
        norm_name = normalize_concept_key(cname)
        unaccent_name = normalize_text_unaccent(norm_name)
        
        aliases = c.get("aliases") or []
        norm_aliases = [normalize_concept_key(a) for a in aliases if a]
        unaccent_aliases = [normalize_text_unaccent(na) for na in norm_aliases]

        concept_map[cid] = {
            "id": cid,
            "name": cname,
            "norm_name": norm_name,
            "unaccent_name": unaccent_name,
            "aliases": aliases,
            "norm_aliases": norm_aliases,
            "unaccent_aliases": unaccent_aliases,
            "embedding": c.get("embedding")
        }

    # 2. Tokenize message cho n-grams
    norm_msg = normalize_concept_key(message)
    tokens = norm_msg.split()
    if not tokens:
        return {"concept_ids": [], "clarify": None}

    ngrams = extract_ngrams(tokens, max_n=4)

    linked_ids = set()
    used_token_indices = set()

    # Lưu vết các lần khớp không dấu để phát hiện đa nghĩa
    unaccent_matches_record = []

    # 3. Quét longest-match-first
    for ngram, start, end in ngrams:
        # Nếu bất kỳ token nào trong ngram đã được match bởi ngram dài hơn, bỏ qua
        if any(idx in used_token_indices for idx in range(start, end)):
            continue

        unaccent_ngram = normalize_text_unaccent(ngram)

        # 3a. Exact match có dấu trước
        exact_hit_ids = []
        for cid, c_data in concept_map.items():
            if ngram == c_data["norm_name"]:
                exact_hit_ids.append(cid)
            elif ngram in c_data["norm_aliases"]:
                exact_hit_ids.append(cid)

        if exact_hit_ids:
            for hid in exact_hit_ids:
                linked_ids.add(hid)
            for idx in range(start, end):
                used_token_indices.add(idx)
            continue

        # 3b. Exact match không dấu
        unaccent_hit_ids = []
        for cid, c_data in concept_map.items():
            if unaccent_ngram == c_data["unaccent_name"]:
                unaccent_hit_ids.append(cid)
            elif unaccent_ngram in c_data["unaccent_aliases"]:
                unaccent_hit_ids.append(cid)

        if len(unaccent_hit_ids) > 1:
            # Khớp không dấu mà ra nhiều concept -> gửi clarify (SPEC §8)
            candidates = [{"id": cid, "name": concept_map[cid]["name"]} for cid in unaccent_hit_ids]
            return {"concept_ids": [], "clarify": candidates}
        elif len(unaccent_hit_ids) == 1:
            linked_ids.add(unaccent_hit_ids[0])
            for idx in range(start, end):
                used_token_indices.add(idx)
            continue

        # 3c. Rapidfuzz (chỉ áp dụng nếu chuỗi đủ độ dài >= ALIAS_FUZZY_MIN_LEN)
        fuzzy_hits = []
        for cid, c_data in concept_map.items():
            # Check name fuzzy
            if len(c_data["norm_name"]) >= settings.ALIAS_FUZZY_MIN_LEN:
                ratio = fuzz.ratio(ngram, c_data["norm_name"])
                if ratio >= getattr(settings, "FUZZY_THRESHOLD", 85):
                    fuzzy_hits.append((cid, ratio))
                    continue

            # Check aliases fuzzy (alias ngắn hơn ALIAS_FUZZY_MIN_LEN chỉ khớp exact)
            for na in c_data["norm_aliases"]:
                if len(na) >= settings.ALIAS_FUZZY_MIN_LEN:
                    ratio = fuzz.ratio(ngram, na)
                    if ratio >= getattr(settings, "FUZZY_THRESHOLD", 85):
                        fuzzy_hits.append((cid, ratio))
                        break

        if fuzzy_hits:
            fuzzy_hits.sort(key=lambda x: x[1], reverse=True)
            top_cid = fuzzy_hits[0][0]
            linked_ids.add(top_cid)
            for idx in range(start, end):
                used_token_indices.add(idx)

    # 4. Nếu có linked concepts -> trả về
    if linked_ids:
        return {"concept_ids": list(linked_ids), "clarify": None}

    # 5. Nếu KHÔNG khớp gì -> Cosine numpy top-3
    candidates = []
    if message_embedding is not None:
        valid_concepts = [c for c in concepts if c.get("embedding") is not None]
        if valid_concepts:
            matrix = np.array([c["embedding"] for c in valid_concepts])
            q_vec = np.array(message_embedding)
            # Normalize
            q_norm = np.linalg.norm(q_vec)
            if q_norm > 0:
                q_vec = q_vec / q_norm
                m_norms = np.linalg.norm(matrix, axis=1, keepdims=True)
                m_norms[m_norms == 0] = 1.0
                norm_matrix = matrix / m_norms
                sims = np.dot(norm_matrix, q_vec)
                top_k = min(3, len(valid_concepts))
                top_indices = np.argsort(-sims)[:top_k]
                for idx in top_indices:
                    c = valid_concepts[idx]
                    candidates.append({"id": c["id"], "name": c["name"]})

    if candidates:
        return {"concept_ids": [], "clarify": candidates}

    return {"concept_ids": [], "clarify": None}

async def link_concepts(course_id: str, message: str) -> Dict[str, Any]:
    """
    Hàm chính gọi từ API hoặc dịch vụ chat.
    """
    concepts = await get_course_concepts_for_linking(course_id)
    if not concepts:
        return {"concept_ids": [], "clarify": None}

    # Embed câu hỏi nếu cần fallback cosine
    message_embedding = None
    try:
        from app.core.embedding import embed_model
        emb_res = await embed_model.embed_batch([message])
        if emb_res:
            message_embedding = emb_res[0]
    except Exception as e:
        pass

    return link_concepts_from_data(message, concepts, message_embedding)
