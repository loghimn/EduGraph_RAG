from unittest.mock import MagicMock

import pytest

from app.core.embedding import embed_model
from app.ingestion.chunker import chunk_document
from app.core.identifiers import IdentifierValidationError


def test_character_fallback_rejects_nonpositive_step(monkeypatch):
    monkeypatch.setattr(embed_model, "_model", MagicMock(tokenizer=MagicMock(side_effect=RuntimeError("fallback"))))
    monkeypatch.setattr("app.ingestion.chunker.settings.CHUNK_TOKENS", 2)
    monkeypatch.setattr("app.ingestion.chunker.settings.CHUNK_OVERLAP", 2)

    with pytest.raises(ValueError, match="CHUNK_TOKENS must be greater than CHUNK_OVERLAP"):
        chunk_document([(1, "some text")], "1", "course-1")


def test_character_fallback_discards_partial_token_chunks(monkeypatch):
    class Offsets:
        def __getitem__(self, index):
            if index == 2:
                raise RuntimeError("token offsets failed mid-page")
            return [(0, 3), (3, 6)][index]

    tokenizer = MagicMock(return_value={
        "input_ids": [1, 2, 3, 4],
        "offset_mapping": Offsets(),
    })
    monkeypatch.setattr(embed_model, "_model", MagicMock(tokenizer=tokenizer))
    monkeypatch.setattr("app.ingestion.chunker.settings.CHUNK_TOKENS", 2)
    monkeypatch.setattr("app.ingestion.chunker.settings.CHUNK_OVERLAP", 0)

    chunks = chunk_document([(1, "abcdefghijkl")], "1", "course-1")

    assert [chunk["text"] for chunk in chunks] == ["abcdef", "ghijkl"]
    assert [chunk["index"] for chunk in chunks] == [0, 1]
    assert [chunk["id"] for chunk in chunks] == ["1:0", "1:1"]


def test_invalid_document_id_is_rejected_before_tokenization(monkeypatch):
    model = MagicMock()
    monkeypatch.setattr(embed_model, "_model", model)
    with pytest.raises(IdentifierValidationError):
        chunk_document([(1, "content")], "document-abc", "course-1")
    model.tokenizer.assert_not_called()


def test_invalid_chunk_id_does_not_trigger_tokenizer_fallback(monkeypatch):
    tokenizer = MagicMock(return_value={"input_ids": [1], "offset_mapping": [(0, 3)]})
    monkeypatch.setattr(embed_model, "_model", MagicMock(tokenizer=tokenizer))
    chunk_id_builder = MagicMock(side_effect=IdentifierValidationError("chunk_id too long"))
    monkeypatch.setattr("app.ingestion.chunker.build_chunk_id", chunk_id_builder)
    with pytest.raises(IdentifierValidationError, match="too long"):
        chunk_document([(1, "abc")], "1", "course-1")
    chunk_id_builder.assert_called_once_with("1", 0)
