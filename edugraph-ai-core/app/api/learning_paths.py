"""HTTP validation/authentication adapter; use cases live in app.services."""

from fastapi import APIRouter, Header
from typing import Annotated
from app.core.internal_auth import verify_internal_key
from app.schemas.learning_paths import LearningPathPlanRequest
from app.services import learning_paths as service

router = APIRouter()


@router.post('/learning-paths/plan')
async def create_learning_path_plan(req: LearningPathPlanRequest, x_internal_key: Annotated[str, Header()]):
    verify_internal_key(x_internal_key)
    return await service.create_learning_path_plan(req=req)
