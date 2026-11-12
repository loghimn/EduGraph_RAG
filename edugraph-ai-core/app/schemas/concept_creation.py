"""Validated request contracts shared by routes and application services."""

from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator
from app.core.text_utils import normalize_concept_key


class DraftRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=200)
    aliases: list[str] = Field(default_factory=list, max_length=50)
    language: Literal["vi", "en", "mixed"] = "mixed"

    @field_validator("name")
    @classmethod
    def clean_name(cls, value):
        value = " ".join(value.split())
        if not normalize_concept_key(value):
            raise ValueError("Name must contain letters or numbers")
        return value

    @field_validator("aliases")
    @classmethod
    def clean_aliases(cls, values):
        result, seen = [], set()
        for value in values:
            value = " ".join(value.split())
            key = normalize_concept_key(value)
            if not key or len(value) > 200:
                raise ValueError("Aliases must contain text and be at most 200 characters")
            if key not in seen:
                seen.add(key)
                result.append(value)
        return result
