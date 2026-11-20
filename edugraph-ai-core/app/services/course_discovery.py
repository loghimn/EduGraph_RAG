"""Discover public courses by concept metadata without borrowing their answer passages."""
import asyncio
import re

from app.core.text_utils import normalize_concept_key, normalize_text_unaccent
from app.db import graph_repository
from app.retrieval.concept_linker import link_concepts_from_data
from app.chat.path_answer import label


def keys(concept):
    return {normalize_text_unaccent(normalize_concept_key(value))
            for value in [concept['name'], *(concept.get('aliases') or [])] if value}


def _has_edge_words(query):
    return bool(re.search(r'->|→|quan he|relationship|\bedge\b|\bcanh\b', normalize_text_unaccent(query.lower())))


def _has_arrow(query):
    return '->' in query or '→' in query


def _overlaps(match, spans):
    return any(match.start() < end and start < match.end() for start, end in spans)


def _whole_names(concepts, normalized):
    # Prefer whole names beyond the general linker's four-gram window.
    spans, names = [], set()
    candidates = {name for row in concepts for name in keys(row)}
    for name in sorted(candidates, key=lambda name: (-len(name), name)):
        for match in re.finditer(r'(?<!\w)' + re.escape(name) + r'(?!\w)', normalized):
            if _overlaps(match, spans):
                continue
            names.add(name)
            spans.append(match.span())
    return names


def _matching_ids(concepts, lookup):
    normalized = normalize_text_unaccent(normalize_concept_key(lookup))
    names = _whole_names(concepts, normalized)
    if names:
        return {row['id'] for row in concepts if keys(row) & names}
    linked = link_concepts_from_data(lookup, concepts)
    return set(linked.get('concept_ids') or [row['id'] for row in linked.get('clarify') or []])


def _merge_name_groups(concepts, ids):
    groups = []
    # The same name in multiple courses represents one requested topic.
    for concept in concepts:
        if concept['id'] not in ids:
            continue
        names = keys(concept)
        overlap = [group for group in groups if group & names]
        if overlap:
            overlap[0].update(names)
        else:
            groups.append(names)
    return groups


def _group_position(group, normalized):
    return min((normalized.find(name) for name in group if name in normalized), default=100000)


def _question_groups(concepts, query, target):
    lookup = query if _has_edge_words(query) else target or query
    groups = _merge_name_groups(concepts, _matching_ids(concepts, lookup))
    if _has_arrow(query):
        normalized = normalize_text_unaccent(normalize_concept_key(query))
        groups.sort(key=lambda group: _group_position(group, normalized))
    return groups


def matching_groups(concepts, query, concept_ids=None, relationship=None, target=None, scope=(), relationship_names=None):
    if relationship_names is not None and relationship is None:
        return [{normalize_text_unaccent(normalize_concept_key(value))}
                for value in [relationship_names.source, relationship_names.target]]
    local = {row['id']: row for row in concepts if row['course_id'] in scope}
    selected = ([relationship.source_id, relationship.target_id] if relationship else concept_ids) or []
    if selected and all(cid in local for cid in selected):
        return [keys(local[cid]) for cid in selected][:20]
    return _question_groups(concepts, query, target)[:20]


def _matches_direction(edge, left, right, directed):
    forward = edge['source'] in left and edge['target'] in right
    reverse = edge['source'] in right and edge['target'] in left
    return forward or not directed and reverse


def _edge_endpoints(matches, edges, course, directed):
    left, right = {row['id'] for row in matches[0]}, {row['id'] for row in matches[1]}
    by_id = {row['id']: row for group in matches for row in group}
    for edge in edges:
        if edge['course_id'] != course or not _matches_direction(edge, left, right, directed):
            continue
        selected = [by_id[edge['source']], by_id[edge['target']]]
        # Evidence must belong to these endpoints, rather than a node sharing an alias.
        if any(row.get('has_evidence') for row in selected):
            return selected
    return None


def _select_concepts(course_nodes, edges, groups, course, edge_question, directed):
    matches = [[row for row in course_nodes if keys(row) & group] for group in groups]
    if not all(matches):
        return None
    if edge_question:
        return _edge_endpoints(matches, edges, course, directed)
    selected = [next((row for row in group if row.get('has_evidence')), group[0]) for group in matches]
    return selected if all(row.get('has_evidence') for row in selected) else None


def _available_courses(concepts, edges, groups, edge_question, directed):
    available = {}
    for course in sorted({row['course_id'] for row in concepts}):
        nodes = [row for row in concepts if row['course_id'] == course]
        selected = _select_concepts(nodes, edges, groups, course, edge_question, directed)
        if selected is not None:
            available[course] = selected
    return available


def _course_suggestion(course, selected, metadata, query, edge_question):
    subject = (' → ' if edge_question else ', ').join(row['name'] for row in selected)
    return {
        'courseId': course, 'courseName': metadata.name, 'enrolled': metadata.enrolled,
        'subject': subject[:500], 'conceptIds': [row['id'] for row in selected], 'question': query[:4000],
        'graphRelationship': ({'sourceId': selected[0]['id'], 'targetId': selected[1]['id']} if edge_question else None),
    }


def select_courses(concepts, edges, groups, scope, public_courses, query, relationship=None):
    if not groups:
        return [], relationship is not None
    edge_question = relationship is not None or len(groups) == 2 and _has_edge_words(query)
    directed = relationship is not None or _has_arrow(query)
    if edge_question and len(groups) != 2:
        return [], True
    available = _available_courses(concepts, edges, groups, edge_question, directed)
    if any(course in available for course in scope):
        return [], False
    suggestions = [_course_suggestion(course, selected, public_courses[course], query, edge_question)
                   for course, selected in available.items() if course not in scope and course in public_courses]
    suggestions.sort(key=lambda row: (not row['enrolled'], row['courseName'].casefold(), row['courseId']))
    return suggestions[:5], True


async def discover_courses(scope, public_courses, query, concept_ids=None, relationship=None, target=None, relationship_names=None):
    if not scope or not public_courses:
        return [], False
    courses = list(dict.fromkeys([*scope, *public_courses]))
    async with graph_repository.session() as session:
        result = await graph_repository.run(session, """
            MATCH (c:Concept) WHERE c.course_id IN $courses AND coalesce(c.status, 'ACTIVE') = 'ACTIVE'
            RETURN c.id AS id, c.name AS name, coalesce(c.aliases, []) AS aliases, c.course_id AS course_id,
                EXISTS { MATCH (c)<-[:MENTIONS {is_evidence: true}]-(ch:Chunk)
                         WHERE ch.course_id = c.course_id AND trim(coalesce(ch.text, '')) <> '' } AS has_evidence
            ORDER BY c.course_id, c.id
        """, courses=courses)
        concepts = await result.data()
        groups = await asyncio.to_thread(matching_groups, concepts, query, concept_ids, relationship, target, scope, relationship_names)
        if not groups:
            return [], False
        relevant = [row['id'] for row in concepts if any(keys(row) & group for group in groups)]
        result = await graph_repository.run(session, """
            MATCH (s:Concept)-[r:PREREQUISITE_OF]->(t:Concept)
            WHERE s.course_id IN $courses AND t.course_id = s.course_id
              AND s.id IN $ids AND t.id IN $ids AND coalesce(r.status, 'AUTO') <> 'REJECTED'
            RETURN s.id AS source, t.id AS target, s.course_id AS course_id
        """, courses=courses, ids=relevant)
        edges = await result.data()
    return await asyncio.to_thread(select_courses, concepts, edges, groups, scope, public_courses, query, relationship or relationship_names)


def invitation(suggestions):
    lines = ['Môn hiện tại chưa có dữ liệu phù hợp cho khái niệm hoặc quan hệ bạn đang hỏi.']
    for row in suggestions:
        subject, name = label(row['subject']), label(row['courseName'])
        if row['enrolled']:
            lines.append(f"{subject} thuộc môn {name} mà bạn đang tham gia. Bạn có muốn chuyển qua môn {name} để hỏi tiếp không?")
        else:
            lines.append(f"Môn public {name} có dữ liệu về {subject}. Bạn có muốn học môn này không?")
    return '\n\n'.join(lines)
