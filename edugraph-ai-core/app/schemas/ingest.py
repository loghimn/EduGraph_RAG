"""Validated request contracts shared by routes and application services."""

from typing import Optional
from pydantic import BaseModel
from app.core.identifiers import DocumentId


class IngestRequest(BaseModel):
    document_id: DocumentId
    course_id: str
    user_id: str
    filename: Optional[str] = "unknown.pdf"
    restart: bool = False
    run_id: Optional[str] = None
