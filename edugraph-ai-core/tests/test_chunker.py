from unittest.mock import MagicMock

import pytest

from app.core.embedding import embed_model
from app.ingestion.chunker import chunk_document


def test_character_fallback_rejects_nonpositive_step(monkeypatch):
    monkeypatch.setattr(embed_model, "_model", MagicMock(tokenizer=MagicMock(side_effect=RuntimeError("fallback"))))
    monkeypatch.setattr("app.ingestion.chunker.settings.CHUNK_TOKENS", 2)
    monkeypatch.setattr("app.ingestion.chunker.settings.CHUNK_OVERLAP", 2)

    with pytest.raises(ValueError, match="CHUNK_TOKENS must be greater than CHUNK_OVERLAP"):
        chunk_document([(1, "some text")], "doc-1", "course-1")


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

    chunks = chunk_document([(1, "abcdefghijkl")], "doc-1", "course-1")

    assert [chunk["text"] for chunk in chunks] == ["abcdef", "ghijkl"]
    assert [chunk["index"] for chunk in chunks] == [0, 1]
