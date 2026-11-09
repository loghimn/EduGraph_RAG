import pytest
from app.retrieval.chunk_retriever import compute_rrf, sanitize_fulltext_query
from app.retrieval import chunk_retriever


class _FakeResult:
    def __init__(self, rows):
        self._rows = rows

    async def data(self):
        return self._rows


class _FakeSession:
    """Giả lập session Neo4j: trả rỗng ở k nhỏ, có dữ liệu ở k lớn."""

    def __init__(self):
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def run(self, query, **params):
        self.calls.append(dict(params))
        if "vector" in params:
            k = params.get("k", 0)
            if k < 200:
                return _FakeResult([])
            return _FakeResult([{
                "id": "ch1", "text": "chunk 1",
                "doc_id": "d1", "page": 1, "index": 0, "score": 0.9,
            }])
        # fulltext: trả rỗng để test tập trung vào nhánh vector
        return _FakeResult([])


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


def test_compute_rrf_scoring_and_ranking():
    # vector results: [ch1, ch2, ch3]
    vec_results = [
        {"id": "ch1", "text": "chunk 1", "score": 0.9},
        {"id": "ch2", "text": "chunk 2", "score": 0.8},
        {"id": "ch3", "text": "chunk 3", "score": 0.7},
    ]
    # fulltext results: [ch2, ch4, ch1]
    ft_results = [
        {"id": "ch2", "text": "chunk 2", "score": 10.0},
        {"id": "ch4", "text": "chunk 4", "score": 8.0},
        {"id": "ch1", "text": "chunk 1", "score": 5.0},
    ]

    fused = compute_rrf(vec_results, ft_results, k_rrf=60)
    
    # ch2 có rank 2 ở vec (1/62) và rank 1 ở ft (1/61) -> tổng lớn nhất
    assert fused[0]["id"] == "ch2"
    # ch1 có rank 1 ở vec (1/61) và rank 3 ở ft (1/63) -> tổng lớn nhì
    assert fused[1]["id"] == "ch1"
    
    # Mọi item đều có rrf_score
    for item in fused:
        assert "rrf_score" in item
        assert item["rrf_score"] > 0

def test_sanitize_fulltext_query():
    raw_query = 'SQL: SELECT * FROM users WHERE age > 20 && name != "admin" (test/demo)'
    cleaned = sanitize_fulltext_query(raw_query)
    # Các ký tự : * & ! " ( ) / đã được bỏ
    for char in [':', '*', '&', '!', '"', '(', ')', '/']:
        assert char not in cleaned
    assert "SELECT" in cleaned
    assert "users" in cleaned


@pytest.mark.asyncio
async def test_vector_overfetch_retries_until_course_chunks_found(monkeypatch):
    """k=50 rỗng do top-k toàn cục → retry k lớn hơn và tìm được chunk của course."""
    session = _FakeSession()
    monkeypatch.setattr(chunk_retriever.neo4j_client, "driver", _FakeDriver(session))

    async def _fake_embed(texts):
        return [[0.0] * 8]

    async def _fake_has_chunks(course_id):
        return True

    monkeypatch.setattr(chunk_retriever.embed_model, "embed_batch", _fake_embed)
    monkeypatch.setattr(chunk_retriever, "_course_has_chunks", _fake_has_chunks)

    chunks = await chunk_retriever.retrieve_chunks(
        course_id="c1", query="dao ham", overfetch_k=50
    )

    assert [c["id"] for c in chunks] == ["ch1"]
    vec_ks = [c.get("k") for c in session.calls if "vector" in c]
    assert vec_ks[0] == 50
    assert vec_ks[-1] >= 200


@pytest.mark.asyncio
@pytest.mark.parametrize("scope", [None, ["c1"]])
async def test_retrieval_uses_course_scope_for_graph_vector_and_fulltext(monkeypatch, scope):
    class ScopedSession(_FakeSession):
        async def run(self, query, **params):
            self.calls.append(dict(params))
            # Model Cypher's IN null / IN [] behavior instead of returning
            # rows unconditionally as the overfetch fake does.
            if "c1" not in (params.get("authorized_course_ids") or []):
                return _FakeResult([])
            if "concept_ids" in params:
                chunk_id = "graph"
            elif "vector" in params:
                chunk_id = "vector"
            else:
                chunk_id = "fulltext"
            return _FakeResult([{"id": chunk_id, "text": "Servlet", "index": 0}])

    session = ScopedSession()
    monkeypatch.setattr(chunk_retriever.neo4j_client, "driver", _FakeDriver(session))

    async def embed(texts):
        return [[0.0] * 8]

    monkeypatch.setattr(chunk_retriever.embed_model, "embed_batch", embed)
    chunks = await chunk_retriever.retrieve_chunks(
        course_id="c1", query="Servlet", concept_ids=["servlet"],
        authorized_course_ids=scope,
    )
    assert {ch["id"] for ch in chunks} == {"graph", "vector", "fulltext"}
    assert len(session.calls) == 3
    assert all(call["authorized_course_ids"] == ["c1"] for call in session.calls)


@pytest.mark.asyncio
@pytest.mark.parametrize("course_id,scope", [("c1", []), (None, None)])
async def test_retrieval_without_access_does_not_query_database(monkeypatch, course_id, scope):
    session = _FakeSession()
    monkeypatch.setattr(chunk_retriever.neo4j_client, "driver", _FakeDriver(session))
    chunks = await chunk_retriever.retrieve_chunks(
        course_id=course_id, query="Servlet", concept_ids=["servlet"],
        authorized_course_ids=scope,
    )
    assert chunks == []
    assert session.calls == []
