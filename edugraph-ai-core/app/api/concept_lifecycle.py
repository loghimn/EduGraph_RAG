"""HTTP validation/authentication adapter; use cases live in app.services."""

from fastapi import APIRouter, Header, Query
from app.core.internal_auth import verify_internal_key
from app.services import concept_lifecycle as service

router = APIRouter(prefix='/courses/{course_id}/knowledge-graph')


@router.get('/archived-concepts')
async def archived_concepts(course_id: str, x_internal_key: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.archived_concepts(course_id=course_id)


@router.get('/concepts/{concept_id}/deletion-impact')
async def deletion_impact(course_id: str, concept_id: str, x_internal_key: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.deletion_impact(course_id=course_id, concept_id=concept_id)


@router.delete('/concepts/{concept_id}')
async def archive_concept(course_id: str, concept_id: str, expected_version: int=Query(..., ge=0), x_internal_key: str=Header(...), x_actor_id: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.archive_concept(course_id=course_id, concept_id=concept_id, expected_version=expected_version, x_actor_id=x_actor_id)


@router.post('/concepts/{concept_id}/restore')
async def restore_concept(course_id: str, concept_id: str, expected_version: int=Query(..., ge=0), x_internal_key: str=Header(...), x_actor_id: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.restore_concept(course_id=course_id, concept_id=concept_id, expected_version=expected_version, x_actor_id=x_actor_id)
