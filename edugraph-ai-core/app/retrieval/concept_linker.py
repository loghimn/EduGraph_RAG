import asyncio
import time
from collections import OrderedDict
from typing import List, Dict, Any, Tuple, Optional
from rapidfuzz import fuzz, process

from app.config import settings
from app.core.text_utils import normalize_concept_key, normalize_text_unaccent
from app.db.neo4j_client import neo4j_client

_CONCEPT_INDEX_CACHE: OrderedDict[
    Tuple[str, ...],
    Tuple[float, Tuple[Tuple[str, Optional[int]], ...], Dict[str, Any]]
] = OrderedDict()
_CONCEPT_INDEX_CACHE_LIMIT = 128

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

async def get_course_concepts_for_linking(course_id: Optional[str] = None,
                                          course_ids: Optional[List[str]] = None) -> List[Dict[str, Any]]:
    """
    Tải tên và aliases của concepts trong course để phục vụ linker.
    """
    query = """
    MATCH (c:Concept)
    WHERE coalesce(c.status, 'ACTIVE') = 'ACTIVE'
    """
    params = {}

    if course_ids is not None:
        if not course_ids:
            return []
        query += """
    AND c.course_id IN $course_ids
    """
        params["course_ids"] = course_ids
    elif course_id is not None:
        query += """
    AND ($course_id IS NULL OR c.course_id = $course_id)
    """
        params["course_id"] = course_id

    query += """
    OPTIONAL MATCH (course:Course {id: c.course_id})
    RETURN c.id AS id, c.name AS name, coalesce(c.aliases, []) AS aliases,
           c.course_id AS course_id, course.name AS course_name
    ORDER BY c.name
    """

    async with neo4j_client.driver.session() as session:
        res = await session.run(query, **params)
        concepts = await res.data()
    return concepts

def link_concepts_from_data(
    message: str,
    concepts: List[Dict[str, Any]]
) -> Dict[str, Any]:
    """
    Thuật toán link concepts từ văn bản và danh sách concepts đã nạp.
    Trả về dict:
      - "concept_ids": list of concept IDs nếu link thành công
      - "clarify": list of {"id": ..., "name": ...} nếu cần hỏi lại
    """
    return _link_concepts_with_index(message, _build_concept_index(concepts))


def _build_concept_index(concepts: List[Dict[str, Any]]) -> Dict[str, Any]:
    concept_map = {}
    exact_terms: Dict[str, List[str]] = {}
    unaccented_terms: Dict[str, List[str]] = {}
    fuzzy_terms = {}
    fuzzy_ids = {}

    def add_term(index: Dict[str, List[str]], term: str, concept_id: str) -> None:
        if term:
            ids = index.setdefault(term, [])
            if concept_id not in ids:
                ids.append(concept_id)

    for concept in concepts:
        concept_id = concept["id"]
        normalized_name = normalize_concept_key(concept["name"])
        normalized_aliases = [
            normalize_concept_key(alias) for alias in (concept.get("aliases") or []) if alias
        ]
        concept_map[concept_id] = {
            "id": concept_id,
            "name": concept["name"],
            "course_id": concept.get("course_id"),
            "course_name": concept.get("course_name"),
        }
        for term in [normalized_name, *normalized_aliases]:
            add_term(exact_terms, term, concept_id)
            add_term(unaccented_terms, normalize_text_unaccent(term), concept_id)
            if len(term) >= settings.ALIAS_FUZZY_MIN_LEN:
                key = len(fuzzy_terms)
                fuzzy_terms[key] = term
                fuzzy_ids[key] = concept_id

    return {
        "concepts": concept_map,
        "exact_terms": exact_terms,
        "unaccented_terms": unaccented_terms,
        "fuzzy_terms": fuzzy_terms,
        "fuzzy_ids": fuzzy_ids,
    }


def _clarification(candidates: List[str], concept_map: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    return {"concept_ids": [], "clarify": [
        {"id": concept_id, **concept_map[concept_id]} for concept_id in candidates
    ]}


def _link_concepts_with_index(message: str, index: Dict[str, Any]) -> Dict[str, Any]:
    concept_map = index["concepts"]
    if not concept_map:
        return {"concept_ids": [], "clarify": None}

    tokens = normalize_concept_key(message).split()
    if not tokens:
        return {"concept_ids": [], "clarify": None}

    linked_ids = []
    linked_set = set()
    used_token_indices = set()
    fuzzy_threshold = getattr(settings, "FUZZY_THRESHOLD", 85)

    for ngram, start, end in extract_ngrams(tokens, max_n=4):
        if any(idx in used_token_indices for idx in range(start, end)):
            continue

        exact_ids = index["exact_terms"].get(ngram, [])
        if len(exact_ids) > 1:
            return _clarification(exact_ids, concept_map)
        if exact_ids:
            if exact_ids[0] not in linked_set:
                linked_ids.append(exact_ids[0])
                linked_set.add(exact_ids[0])
            used_token_indices.update(range(start, end))
            continue

        unaccented_ids = index["unaccented_terms"].get(normalize_text_unaccent(ngram), [])
        if len(unaccented_ids) > 1:
            return _clarification(unaccented_ids, concept_map)
        if unaccented_ids:
            if unaccented_ids[0] not in linked_set:
                linked_ids.append(unaccented_ids[0])
                linked_set.add(unaccented_ids[0])
            used_token_indices.update(range(start, end))
            continue

        scores: Dict[str, float] = {}
        for _, score, key in process.extract_iter(
                ngram, index["fuzzy_terms"], scorer=fuzz.ratio,
                score_cutoff=fuzzy_threshold):
            concept_id = index["fuzzy_ids"][key]
            scores[concept_id] = max(score, scores.get(concept_id, 0))
        if scores:
            top_score = max(scores.values())
            top_ids = [concept_id for concept_id, score in scores.items() if score == top_score]
            if len(top_ids) > 1:
                return _clarification(top_ids, concept_map)
            if top_ids[0] not in linked_set:
                linked_ids.append(top_ids[0])
                linked_set.add(top_ids[0])
            used_token_indices.update(range(start, end))

    return {"concept_ids": linked_ids, "clarify": None}


def _scope_key(course_id: Optional[str], course_ids: Optional[List[str]]) -> Tuple[str, ...]:
    if course_ids is not None:
        return ("courses", *sorted(set(course_ids)))
    if course_id is not None:
        return ("course", course_id)
    return ("all",)


async def _get_scope_versions(
    course_id: Optional[str], course_ids: Optional[List[str]]
) -> Tuple[Tuple[str, Optional[int]], ...]:
    if course_ids is not None:
        scoped_course_ids = sorted(set(course_ids))
        query = """
        MATCH (course:Course)
        WHERE course.id IN $course_ids
        RETURN course.id AS course_id, coalesce(course.version, 0) AS version
        ORDER BY course.id
        """
        params = {"course_ids": scoped_course_ids}
    elif course_id is not None:
        scoped_course_ids = [course_id]
        query = """
        MATCH (course:Course {id: $course_id})
        RETURN course.id AS course_id, coalesce(course.version, 0) AS version
        """
        params = {"course_id": course_id}
    else:
        scoped_course_ids = []
        query = """
        MATCH (course:Course)
        RETURN course.id AS course_id, coalesce(course.version, 0) AS version
        ORDER BY course.id
        """
        params = {}

    async with neo4j_client.driver.session() as session:
        result = await session.run(query, **params)
        rows = await result.data()

    versions = {row["course_id"]: row["version"] for row in rows}
    if course_ids is None and course_id is None:
        return tuple(sorted(versions.items()))
    return tuple((item, versions.get(item)) for item in scoped_course_ids)


async def link_concepts(course_id: Optional[str] = None,
                        message: Optional[str] = None,
                        course_ids: Optional[List[str]] = None) -> Dict[str, Any]:
    """
    Hàm chính gọi từ API hoặc dịch vụ chat.
    """
    if course_ids is not None and not course_ids:
        return {"concept_ids": [], "clarify": None}

    key = _scope_key(course_id, course_ids)
    ttl = max(0, getattr(settings, "CONCEPT_LINKING_CACHE_TTL_SECONDS", 60))
    versions = await _get_scope_versions(course_id, course_ids) if ttl else ()
    cached = _CONCEPT_INDEX_CACHE.get(key)
    now = time.monotonic()
    if ttl and cached and cached[1] == versions and now - cached[0] < ttl:
        _CONCEPT_INDEX_CACHE.move_to_end(key)
        index = cached[2]
    else:
        _CONCEPT_INDEX_CACHE.pop(key, None)
        concepts = await get_course_concepts_for_linking(course_id=course_id, course_ids=course_ids)
        index = await asyncio.get_running_loop().run_in_executor(
            None, _build_concept_index, concepts)
        if ttl:
            _CONCEPT_INDEX_CACHE[key] = (now, versions, index)
            _CONCEPT_INDEX_CACHE.move_to_end(key)
            while len(_CONCEPT_INDEX_CACHE) > _CONCEPT_INDEX_CACHE_LIMIT:
                _CONCEPT_INDEX_CACHE.popitem(last=False)

    result = await asyncio.get_running_loop().run_in_executor(
        None, _link_concepts_with_index, message, index)
    return result
