from unittest.mock import AsyncMock

import pytest

from app.concept_extraction import llm_filter as filtering


class MemoryCache(dict):
    def set(self, key, value):
        self[key] = value


@pytest.fixture
def bounded_filter(monkeypatch):
    monkeypatch.setattr(filtering, "PROMPT_TEMPLATE", "<<TEXT>>\n<<CANDIDATES>>")
    monkeypatch.setattr(filtering, "cache", MemoryCache())
    monkeypatch.setattr(filtering.settings, "LLM_JSON_MAX_OUTPUT_TOKENS", 100)
    monkeypatch.setattr(filtering.token_limiter, "tpm_limit", 260)

    async def generate(prompt, version, data, json_mode):
        assert (filtering.estimate_request_tokens(prompt, json_mode) + 128
                <= filtering.token_limiter.tpm_limit)
        evidence = data["text"].split("\n", 1)[1].split("\n\n[CHUNK", 1)[0]
        return {"concepts": [{"canonical": "array", "aliases": [],
                              "language": "en", "evidence": evidence, "chunk": 1}]}

    generate_mock = AsyncMock(side_effect=generate)
    monkeypatch.setattr(filtering.llm_client, "generate", generate_mock)
    return generate_mock


def chunk(index, text):
    return {"id": f"doc_chunk_{index}", "index": index, "text": text}


@pytest.mark.asyncio
async def test_oversized_group_splits_and_preserves_chunk_identity(bounded_filter):
    chunks = [chunk(i, chr(65 + i) * 80) for i in range(4)]
    concepts, stats = await filtering.process_chunk_group(0, chunks, [[]] * 4)
    assert bounded_filter.await_count == 4
    assert [c["evidence_chunk_id"] for c in concepts] == [c["id"] for c in chunks]
    assert stats["total_returned"] == 4
    assert stats["dropped_no_evidence"] == 0


@pytest.mark.asyncio
async def test_single_oversized_chunk_translates_evidence_offsets(bounded_filter):
    text = "A" * 80 + "B" * 80
    concepts, stats = await filtering.process_chunk_group(0, [chunk(7, text)], [[]])
    assert bounded_filter.await_count == 2
    assert [(c["evidence_start"], c["evidence_end"]) for c in concepts] == [(0, 120), (40, 160)]
    assert all(c["evidence_chunk_id"] == "doc_chunk_7" for c in concepts)
    assert stats["total_returned"] == 2


@pytest.mark.asyncio
async def test_small_group_still_uses_one_request(bounded_filter):
    await filtering.process_chunk_group(0, [chunk(0, "array of objects")], [[]])
    assert bounded_filter.await_count == 1


@pytest.mark.asyncio
async def test_impossible_budget_fails_without_calling_provider(bounded_filter, monkeypatch):
    monkeypatch.setattr(filtering.token_limiter, "tpm_limit", 100)
    with pytest.raises(ValueError, match="cannot fit the token budget"):
        await filtering.process_chunk_group(0, [chunk(0, "abc")], [[]])
    bounded_filter.assert_not_awaited()


@pytest.mark.asyncio
async def test_group_over_6000_tokens_is_split_before_generation(bounded_filter, monkeypatch):
    monkeypatch.setattr(filtering.settings, "LLM_JSON_MAX_OUTPUT_TOKENS", 3000)
    monkeypatch.setattr(filtering.token_limiter, "tpm_limit", 6000)
    chunks = [chunk(i, chr(65 + i) * 3220) for i in range(4)]
    prompt = "\n\n".join(f"[CHUNK {i+1}]\n{c['text']}" for i, c in enumerate(chunks))
    assert filtering.estimate_request_tokens(prompt, True) > 6000
    await filtering.process_chunk_group(0, chunks, [[]] * 4)
    assert bounded_filter.await_count == 2
