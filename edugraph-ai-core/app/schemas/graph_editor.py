"""Validated request contracts shared by routes and application services."""

from typing import Literal
from pydantic import BaseModel, ConfigDict, Field


class EdgeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source_id: str = Field(min_length=1, max_length=200)
    target_id: str = Field(min_length=1, max_length=200)
    note: str = Field(default="", max_length=2000)

class ReviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    status: Literal["CONFIRMED", "REJECTED"]
    note: str = Field(default="", max_length=2000)
