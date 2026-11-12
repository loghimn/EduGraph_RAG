"""Validated request contracts shared by routes and application services."""

from typing import Optional, List
from pydantic import BaseModel


class LearningPathPlanRequest(BaseModel):
    course_id: str
    user_id: str
    document_id: Optional[str] = None
    goal_concept_ids: Optional[List[str]] = None
    known_concept_ids: Optional[List[str]] = None
    include_external_prereqs: bool = True
    assume_known_prerequisites: bool = True
