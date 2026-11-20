"""Bounded prerequisite traversal, stopping at the learner's mastery boundary."""
from collections import deque

from app.db import graph_repository
from app.retrieval.graph_cache import get_course_graph


def _unvisited_parents(graph, node, visited):
    return (parent for parent in sorted(graph.predecessors(node)) if parent not in visited)


def missing_prerequisites(graph, targets, known, max_depth=3, limit=40):
    targets, known = set(targets), set(known)
    queue = deque((target, 0) for target in sorted(targets) if target in graph and target not in known)
    visited = set(targets)
    missing, mastered = [], []
    while queue and len(missing) < limit:
        node, depth = queue.popleft()
        if depth >= max_depth:
            continue
        for parent in _unvisited_parents(graph, node, visited):
            visited.add(parent)
            item = {"id": parent, "name": graph.nodes[parent].get("name", parent), "depth": depth + 1}
            if parent in known:
                mastered.append(item)
                continue
            missing.append(item)
            if len(missing) >= limit:
                break
            queue.append((parent, depth + 1))
    return missing, mastered


async def retrieve_prerequisite_context(concept_ids, courses, known_by_course=None, configs=None):
    if not concept_ids or not courses:
        return [], [], []
    async with graph_repository.session() as session:
        result = await graph_repository.run(session, """
            MATCH (c:Concept) WHERE c.id IN $ids AND c.course_id IN $courses
              AND coalesce(c.status, 'ACTIVE') = 'ACTIVE'
            RETURN c.id AS id, c.name AS name, c.course_id AS course_id
            ORDER BY c.course_id, c.id LIMIT 20
        """, ids=concept_ids, courses=courses)
        targets = await result.data()
    context, chunks = [], []
    for course in sorted({row["course_id"] for row in targets}):
        graph, _, _, _ = await get_course_graph(course)
        ids = [row["id"] for row in targets if row["course_id"] == course]
        config = (configs or {}).get(course)
        depth = config.traversal_depth if config else 3
        missing, mastered = missing_prerequisites(graph, ids, (known_by_course or {}).get(course, []), depth)
        context.append({"course_id": course, "missing": missing, "mastered": mastered, "max_depth": depth})
        if not missing:
            continue
        async with graph_repository.session() as session:
            result = await graph_repository.run(session, """
                UNWIND $ids AS concept_id
                MATCH (c:Concept {id: concept_id, course_id: $course})
                      <-[:MENTIONS {is_evidence: true}]-(ch:Chunk {course_id: $course})
                WITH c, ch ORDER BY ch.index, ch.id
                WITH c, collect(ch)[0] AS ch
                RETURN ch.id AS id, ch.text AS text, ch.doc_id AS doc_id,
                       ch.page AS page, ch.index AS index, c.id AS concept_id, ch.course_id AS course_id
            """, ids=[item["id"] for item in missing], course=course)
            by_concept = {row["concept_id"]: row for row in await result.data()}
        chunks.extend({**by_concept[item["id"]], "prerequisite_evidence": True}
                      for item in missing if item["id"] in by_concept)
    return context, chunks, targets


def merge_prerequisite_chunks(chunks, prerequisites, limit):
    """Reserve answer evidence and prerequisite evidence under the same citation budget."""
    if not prerequisites:
        return chunks[:limit]
    prerequisite_ids = {row["id"] for row in prerequisites}
    base = [{**row, **({"prerequisite_evidence": True} if row["id"] in prerequisite_ids else {})}
            for row in chunks[:max(1, limit - min(len(prerequisites), limit // 2))]]
    seen = {row["id"] for row in base}
    result = list(base)
    for row in prerequisites + chunks[len(base):]:
        if row["id"] not in seen and len(result) < limit:
            result.append(row)
            seen.add(row["id"])
    return result
