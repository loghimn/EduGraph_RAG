"""Validated request contracts shared by routes and application services."""

from typing import Optional, List, Dict, Literal
from pydantic import BaseModel, Field


class GraphRelationship(BaseModel):
    source_id: str = Field(min_length=1, max_length=200)
    target_id: str = Field(min_length=1, max_length=200)


class HistoryMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(max_length=4_000)

class ChatRequest(BaseModel):
    course_id: Optional[str] = None
    user_id: str
    message: str = Field(min_length=1, max_length=12000)
    concept_ids: Optional[List[str]] = Field(default=None, max_length=20)
    graph_relationship: Optional[GraphRelationship] = None
    history: List[HistoryMessage] = Field(default_factory=list, max_length=12)
    reply_context: List[HistoryMessage] = Field(default_factory=list, max_length=2)
    authorized_course_ids: Optional[List[str]] = None  # Danh sách course_ids user được phép truy cập (từ Spring Boot JWT)
    course_names: Dict[str, str] = Field(default_factory=dict)
    known_concepts_by_course: Dict[str, List[str]] = Field(default_factory=dict)
