import pytest
from pydantic import TypeAdapter, ValidationError

from app.core.identifiers import (
    DocumentId, JAVA_LONG_MAX, IdentifierValidationError, build_chunk_id, validate_document_id,
)


@pytest.mark.parametrize("document_id", ["1", "123", str(JAVA_LONG_MAX)])
def test_document_ids_stay_strings_and_fit_java_long(document_id):
    assert TypeAdapter(DocumentId).validate_python(document_id) == document_id
    assert validate_document_id(document_id) == document_id


@pytest.mark.parametrize("document_id", ["doc1", "", "0", "-1", "+1", "01", "1.5", " 1", "1\n",
                                         "١٢", str(JAVA_LONG_MAX + 1), "9" * 101])
def test_rejects_noncanonical_or_overflow_document_ids(document_id):
    with pytest.raises(ValidationError):
        TypeAdapter(DocumentId).validate_python(document_id)
    with pytest.raises(IdentifierValidationError):
        validate_document_id(document_id)


def test_chunk_id_limit_matches_java_column():
    assert build_chunk_id("123", 0) == "123:0"
    assert len(build_chunk_id("1", int("9" * 98))) == 100
    with pytest.raises(IdentifierValidationError, match="100 characters"):
        build_chunk_id("1", int("9" * 99))


@pytest.mark.parametrize("index", [-1, True, "1"])
def test_rejects_invalid_chunk_indexes(index):
    with pytest.raises(IdentifierValidationError):
        build_chunk_id("1", index)
