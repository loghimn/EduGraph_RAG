"""Validated request contracts shared by routes and application services."""

from typing import Optional
from pydantic import BaseModel, Field
from app.core.identifiers import DocumentId


class IngestRequest(BaseModel):
    document_id: DocumentId
    course_id: str
    user_id: str
    filename: Optional[str] = "unknown.pdf"
    restart: bool = False
    run_id: Optional[str] = None
    review_required: bool = True


class PublishRequest(BaseModel):
    review_id: str = Field(min_length=1, max_length=100)
    excluded_concepts: list[str] = Field(default_factory=list, max_length=10000)
    excluded_edges: list[str] = Field(default_factory=list, max_length=100000)
