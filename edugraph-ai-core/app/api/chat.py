"""HTTP validation/authentication adapter; use cases live in app.services."""

from fastapi import APIRouter, Header
from typing import Annotated
from app.core.internal_auth import verify_internal_key
from app.schemas.chat import ChatRequest
from app.services import chat as service

router = APIRouter()


@router.post('/chat')
async def chat_endpoint(req: ChatRequest, x_internal_key: Annotated[str, Header()]):
    verify_internal_key(x_internal_key)
    return await service.chat_endpoint(req=req)
