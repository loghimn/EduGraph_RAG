import json
from unittest.mock import AsyncMock, patch

import pytest

from app.retrieval.chunk_retriever import rank_chunks
from app.retrieval.relationship_retriever import retrieve_relationship
from app.schemas.chat import GraphRelationship
from app.services.chat import chat_explain_stream_generator


def chunk(cid, text, doc="1", **extra):
    return {"id": cid, "text": text, "doc_id": doc, "page": 3, "index": 0, **extra}


def test_fusion_does_not_let_graph_results_hide_more_relevant_vector_hits():
    graph = [chunk(f"g{i}", f"unrelated passage {i}") for i in range(10)]
    vector = [chunk("v", "inheritance extends Vehicle", "2", score=.9)]
    result = rank_chunks("inheritance extends", graph, vector, [], 3)
    assert result[0]["id"] == "v"
    assert len(result) == 3


def test_ranking_filters_low_scores_and_duplicate_text_and_diversifies_documents():
    graph = [chunk(str(i), f"inheritance passage {i}") for i in range(5)]
    vectors = [chunk("weak", "inheritance", "2", score=.1)]
    fulltext = [chunk("other", "second source inheritance", "3"), chunk("duplicate", "inheritance passage 0")]
    result = rank_chunks("inheritance", graph, vectors, fulltext, 4)
    assert "weak" not in [item["id"] for item in result]
    assert "other" in [item["id"] for item in result[:3]]
    assert len({item["text"] for item in result}) == len(result)


@pytest.mark.asyncio
async def test_no_concept_match_still_answers_from_hybrid_search_and_emits_clean_citations():
    async def generate(*args, **kwargs):
        yield "Inheritance [1] and invalid [999]."
    with patch("app.services.chat.link_concepts", AsyncMock(return_value={"concept_ids": []})), \
         patch("app.services.chat.retrieve_chunks", AsyncMock(return_value=[chunk("ch1", "Inheritance extends")])) as retrieve, \
         patch("app.services.chat.llm_client.generate_stream", generate):
        frames = [frame async for frame in chat_explain_stream_generator("7", "Inheritance?", authorized_course_ids=["7"])]
    events = {frame.splitlines()[0][7:]: json.loads(frame.splitlines()[1][6:]) for frame in frames if frame.startswith("event:")}
    retrieve.assert_awaited_once()
    assert retrieve.call_args.kwargs["concept_ids"] == []
    assert events["answer"]["content"] == "Inheritance [1] and invalid ."
    assert len(events["sources"]) == 1
    assert events["sources"][0]["chunk_id"] == "ch1"
    assert frames[-1].startswith("event: done")


@pytest.mark.asyncio
async def test_exact_edge_evidence_is_prioritized_and_passed_with_status_to_prompt():
    relationship = GraphRelationship(source_id="method", target_id="object")
    evidence = chunk("edge-proof", "Method requires object", relationship_evidence=True)
    async def generate(*args, **kwargs):
        yield "Edge [1]."
    with patch("app.services.chat.retrieve_chunks", AsyncMock(return_value=[chunk("other", "general text")])), \
         patch("app.services.chat.retrieve_relationship", AsyncMock(return_value=({"recorded": True, "status": "AUTO"}, [evidence]))) as retrieve, \
         patch("app.services.chat.build_explain_prompt", return_value="prompt") as prompt, \
         patch("app.services.chat.llm_client.generate_stream", generate):
        frames = [frame async for frame in chat_explain_stream_generator("7", "Explain edge", ["method", "object"], ["7"], graph_relationship=relationship)]
    retrieve.assert_awaited_once_with(relationship, ["7"])
    assert prompt.call_args.args[1][0]["id"] == "edge-proof"
    assert prompt.call_args.kwargs["relationship_context"]["status"] == "AUTO"
    assert '"citation_type": "PREREQUISITE_SURFACED"' in "".join(frames)


@pytest.mark.asyncio
async def test_relationship_retrieval_never_queries_without_authorized_scope():
    with patch("app.retrieval.relationship_retriever.neo4j_client") as client:
        assert await retrieve_relationship(GraphRelationship(source_id="s", target_id="t"), []) == (None, [])
        client.driver.session.assert_not_called()


@pytest.mark.asyncio
async def test_relationship_query_uses_exact_direction_and_same_course_evidence():
    from app.db.queries.chat import EXACT_RELATIONSHIP
    assert "(s:Concept {id: $source})-[r:PREREQUISITE_OF]->(t:Concept {id: $target})" in EXACT_RELATIONSHIP
    assert "t.course_id = s.course_id" in EXACT_RELATIONSHIP
    assert "ch.course_id = s.course_id" in EXACT_RELATIONSHIP
    assert "<> 'REJECTED'" in EXACT_RELATIONSHIP
