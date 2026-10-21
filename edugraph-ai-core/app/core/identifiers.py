import re
from typing import Annotated

from pydantic import AfterValidator, StringConstraints

JAVA_LONG_MAX = 9_223_372_036_854_775_807
MAX_CHUNK_ID_LENGTH = 100


class IdentifierValidationError(ValueError):
    """An ingestion identifier cannot be represented by the Java storage contract."""


def validate_document_id(value: str) -> str:
    # Keep the canonical string used as the Neo4j ID and raw-data directory name.
    # Reject leading zeros rather than silently mapping two IDs to the same Long.
    if not isinstance(value, str) or re.fullmatch(r"[1-9][0-9]{0,18}", value) is None:
        raise IdentifierValidationError("document_id must be a canonical positive Java Long string")
    if int(value) > JAVA_LONG_MAX:
        raise IdentifierValidationError("document_id exceeds Java Long range")
    return value


DocumentId = Annotated[
    str,
    StringConstraints(min_length=1, max_length=19, pattern=r"^[1-9][0-9]{0,18}$"),
    AfterValidator(validate_document_id),
]


def build_chunk_id(document_id: str, chunk_index: int) -> str:
    validate_document_id(document_id)
    if isinstance(chunk_index, bool) or not isinstance(chunk_index, int) or chunk_index < 0:
        raise IdentifierValidationError("chunk_index must be a non-negative integer")
    chunk_id = f"{document_id}:{chunk_index}"
    if len(chunk_id) > MAX_CHUNK_ID_LENGTH:
        raise IdentifierValidationError("chunk_id must not exceed 100 characters")
    return chunk_id
