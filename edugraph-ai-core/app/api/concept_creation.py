"""HTTP validation/authentication adapter; use cases live in app.services."""

from fastapi import APIRouter, Header
from app.core.internal_auth import verify_internal_key
from app.schemas.concept_creation import DraftRequest
from app.services import concept_creation as service

router = APIRouter(prefix='/courses/{course_id}/knowledge-graph')


@router.post('/concept-drafts', status_code=201)
async def compute_draft(course_id: str, req: DraftRequest, x_internal_key: str=Header(...), x_actor_id: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.compute_draft(course_id=course_id, req=req, x_actor_id=x_actor_id)


@router.post('/concept-drafts/{draft_id}/publish')
async def publish_draft(course_id: str, draft_id: str, x_internal_key: str=Header(...), x_actor_id: str=Header(...)):
    verify_internal_key(x_internal_key)
    return await service.publish_draft(course_id=course_id, draft_id=draft_id, x_actor_id=x_actor_id)
