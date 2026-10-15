from unittest.mock import AsyncMock, patch

import pytest

from app.concept_extraction.pipeline import process_document_pipeline


@pytest.mark.asyncio
async def test_pipeline_embeds_new_concepts_before_cosine_merge():
    new_concept = {
        "canonical": "đạo hàm",
        "language": "vi",
        "aliases": [],
        "evidence_chunk_id": "chunk-1",
        "evidence_start": 0,
        "evidence_end": 7,
    }
    db_concept = {
        "id": "derivative-id",
        "key": "derivative",
        "canonical": "derivative",
        "language": "en",
        "aliases": [],
        "embedding": [1.0, 0.0],
    }
    embed_batch = AsyncMock(side_effect=[[[0.0, 1.0]], [[1.0, 0.0]]])

    with (
        patch("app.concept_extraction.pipeline.update_heartbeat", new=AsyncMock()),
        patch("app.ingestion.loaders.load_document", return_value=[(1, "text")]),
        patch("app.ingestion.cleaner.clean_text", side_effect=lambda text: text),
        patch("app.ingestion.cleaner.remove_repeating_headers_footers", side_effect=lambda pages: pages),
        patch("app.ingestion.chunker.chunk_document", return_value=[
            {"id": "chunk-1", "text": "chunk text", "course_id": "course-1"}
        ]),
        patch("app.concept_extraction.candidates.extract_candidates", return_value=[[]]),
        patch(
            "app.concept_extraction.llm_filter.process_chunk_group",
            new=AsyncMock(return_value=([new_concept], {
                "total_returned": 1,
                "dropped_no_evidence": 0,
                "dropped_format": 0,
                "dropped_list": [],
            })),
        ),
        patch("app.concept_extraction.pipeline.get_course_concepts", new=AsyncMock(return_value=[db_concept])),
        patch("app.concept_extraction.pipeline.embed_model.embed_batch", new=embed_batch),
        patch("app.concept_extraction.mention_scanner.scan_mentions", return_value=[]),
        patch("app.concept_extraction.pipeline.save_ingestion_transaction", new=AsyncMock(return_value=None)) as save,
    ):
        result = await process_document_pipeline("doc-1", "course-1", "file.pdf")

    assert embed_batch.await_args_list[1].args[0] == ["đạo hàm"]
    assert result["merge_candidates"] == [("đạo hàm", "derivative", "Merged (Cosine)")]
    assert save.await_args.kwargs["concepts"] == []
