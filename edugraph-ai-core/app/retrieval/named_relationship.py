"""Resolve classifier-supplied endpoint names without treating them as graph facts."""
from itertools import islice, product

from app.core.text_utils import normalize_concept_key, normalize_text_unaccent
from app.retrieval.concept_linker import get_course_concepts_for_linking
from app.schemas.chat import GraphRelationship


def _key(value):
    return normalize_text_unaccent(normalize_concept_key(value))


def _nodes_named(rows, name):
    return [row for row in rows if name in {_key(value) for value in [row['name'], *(row.get('aliases') or [])]}]


def _endpoint_pairs(sources, targets):
    pairs = ((left['id'], right['id']) for left, right in product(sources, targets)
             if left['course_id'] == right['course_id'] and left['id'] != right['id'])
    # Two pairs already establish ambiguity; do not enumerate the rest.
    return list(islice(pairs, 2))


def resolve_relationship_names(names, concepts, authorized_course_ids):
    rows = [row for row in concepts if row.get('course_id') in authorized_course_ids]
    source, target = _key(names.source), _key(names.target)
    sources = _nodes_named(rows, source)
    targets = _nodes_named(rows, target)
    matched_ids = list(dict.fromkeys(row['id'] for row in [*sources, *targets]))[:20]
    pairs = _endpoint_pairs(sources, targets)
    if len(pairs) == 1:
        source_id, target_id = pairs[0]
        return GraphRelationship(source_id=source_id, target_id=target_id), None, [source_id, target_id]
    return None, {'recorded': False, 'source': names.source, 'target': names.target,
                  'resolution': 'ambiguous' if pairs else 'unresolved'}, matched_ids


async def resolve_named_relationship(names, authorized_course_ids):
    if not authorized_course_ids:
        return resolve_relationship_names(names, [], [])
    concepts = await get_course_concepts_for_linking(authorized_course_ids)
    return resolve_relationship_names(names, concepts, authorized_course_ids)
