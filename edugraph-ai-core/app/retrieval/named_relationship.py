"""Resolve classifier-supplied endpoint names without treating them as graph facts."""
from app.core.text_utils import normalize_concept_key, normalize_text_unaccent
from app.retrieval.concept_linker import get_course_concepts_for_linking
from app.schemas.chat import GraphRelationship


def _key(value):
    return normalize_text_unaccent(normalize_concept_key(value))


def resolve_relationship_names(names, concepts, authorized_course_ids):
    rows = [row for row in concepts if row.get('course_id') in authorized_course_ids]
    source, target = _key(names.source), _key(names.target)
    sources = [row for row in rows if source in {_key(value) for value in [row['name'], *(row.get('aliases') or [])]}]
    targets = [row for row in rows if target in {_key(value) for value in [row['name'], *(row.get('aliases') or [])]}]
    matched_ids = list(dict.fromkeys(row['id'] for row in [*sources, *targets]))[:20]
    pairs = []
    for left in sources:
        for right in targets:
            if left['course_id'] == right['course_id'] and left['id'] != right['id']:
                pairs.append((left['id'], right['id']))
                if len(pairs) > 1:
                    break
        if len(pairs) > 1:
            break
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
