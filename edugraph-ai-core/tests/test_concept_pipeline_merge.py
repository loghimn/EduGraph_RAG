from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.concept_extraction.pipeline import process_document_pipeline


@pytest.mark.asyncio
@pytest.mark.parametrize("draft", [False, True])
@pytest.mark.parametrize("existing", [False, True])
async def test_pipeline_embeds_new_concepts_before_cosine_merge(draft, existing):
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
    # The pipeline also reads previous mentions and pending affected concepts.
    session = MagicMock()
    session.__aenter__ = AsyncMock(return_value=session)
    session.__aexit__ = AsyncMock(return_value=None)
    rows = MagicMock()
    rows.data = AsyncMock(return_value=[])
    rows.single = AsyncMock(return_value=None)
    session.run = AsyncMock(return_value=rows)
    driver = MagicMock(session=MagicMock(return_value=session))

    with (
        patch("app.concept_extraction.pipeline.neo4j_client.driver", driver),
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
        patch("app.concept_extraction.pipeline.get_course_concepts", new=AsyncMock(return_value=[db_concept] if existing else [])),
        patch("app.concept_extraction.pipeline.embed_model.embed_batch", new=embed_batch),
        patch("app.concept_extraction.mention_scanner.scan_mentions", return_value=[]),
        patch("app.concept_extraction.pipeline.save_ingestion_transaction", new=AsyncMock(return_value=None)) as save,
    ):
        result = await process_document_pipeline("doc-1", "course-1", "file.pdf", draft=draft)

    assert embed_batch.await_args_list[1].args[0] == ["đạo hàm"]
    if draft:
        save.assert_not_awaited()
        assert all("MATCH" in call.args[0] and "SET " not in call.args[0] for call in session.run.await_args_list)
        concepts = result["concepts"]
    else:
        concepts = save.await_args.kwargs["concepts"]
        assert result["merge_candidates"] == ([("đạo hàm", "derivative", "Merged (Cosine)")] if existing else [])
    if existing:
        assert concepts == []
    else:
        assert len(concepts) == 1
        assert concepts[0]["name"] == "đạo hàm"
        assert concepts[0]["order"] == 100000
        assert concepts[0]["embedding"] == [1.0, 0.0]
