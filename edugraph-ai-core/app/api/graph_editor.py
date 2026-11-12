"""HTTP validation/authentication adapter; use cases live in app.services."""

from fastapi import APIRouter, Header, Query
from app.core.internal_auth import verify_internal_key
from app.schemas.graph_editor import EdgeRequest, ReviewRequest
from app.schemas.concept_creation import DraftRequest
from app.services import graph_editor as service

router = APIRouter(prefix='/courses/{course_id}/knowledge-graph')


@router.get('/review')
async def review_graph(course_id: str, x_internal_key: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.review_graph(course_id=course_id)


@router.post('/edges')
async def create_edge(course_id: str, req: EdgeRequest, expected_version: int=Query(..., ge=0), x_internal_key: str=Header(...), x_actor_id: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.create_edge(course_id=course_id, req=req, expected_version=expected_version, x_actor_id=x_actor_id)


@router.put('/edges/{source_id}/{target_id}')
async def update_edge(course_id: str, source_id: str, target_id: str, req: EdgeRequest, expected_version: int=Query(..., ge=0), x_internal_key: str=Header(...), x_actor_id: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.update_edge(course_id=course_id, source_id=source_id, target_id=target_id, req=req, expected_version=expected_version, x_actor_id=x_actor_id)


@router.post('/edges/{source_id}/{target_id}/review')
async def review_edge(course_id: str, source_id: str, target_id: str, req: ReviewRequest, expected_version: int=Query(..., ge=0), x_internal_key: str=Header(...), x_actor_id: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.review_edge(course_id=course_id, source_id=source_id, target_id=target_id, req=req, expected_version=expected_version, x_actor_id=x_actor_id)


@router.delete('/edges/{source_id}/{target_id}')
async def delete_edge(course_id: str, source_id: str, target_id: str, expected_version: int=Query(..., ge=0), x_internal_key: str=Header(...), x_actor_id: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.delete_edge(course_id=course_id, source_id=source_id, target_id=target_id, expected_version=expected_version, x_actor_id=x_actor_id)


@router.put('/concepts/{concept_id}')
async def update_concept(course_id: str, concept_id: str, req: DraftRequest, expected_version: int=Query(..., ge=0), x_internal_key: str=Header(...), x_actor_id: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.update_concept(course_id=course_id, concept_id=concept_id, req=req, expected_version=expected_version, x_actor_id=x_actor_id)


@router.post('/concepts/{concept_id}/review')
async def confirm_concept(course_id: str, concept_id: str, expected_version: int=Query(..., ge=0), x_internal_key: str=Header(...), x_actor_id: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.confirm_concept(course_id=course_id, concept_id=concept_id, expected_version=expected_version, x_actor_id=x_actor_id)
