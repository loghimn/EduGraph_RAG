import pytest
from app.retrieval.concept_linker import (
    extract_ngrams,
    get_course_concepts_for_linking,
    link_concepts,
    link_concepts_from_data,
)

def test_extract_ngrams_longest_first():
    tokens = ["gradient", "descent", "algorithm"]
    ngrams = extract_ngrams(tokens, max_n=3)
    # Longest first: length 3, then 2, then 1
    lengths = [len(ng[0].split()) for ng in ngrams]
    assert lengths[0] == 3
    assert lengths[-1] == 1
    assert ngrams[0][0] == "gradient descent algorithm"

def test_link_concepts_exact_and_unaccented():
    concepts = [
        {"id": "c1", "name": "Đại số tuyến tính", "aliases": ["Linear Algebra"]},
        {"id": "c2", "name": "Giải tích", "aliases": ["Calculus"]},
    ]
    
    # Exact có dấu
    res = link_concepts_from_data("Tôi muốn học Đại số tuyến tính", concepts)
    assert res["concept_ids"] == ["c1"]
    assert res["clarify"] is None

    # Khớp không dấu (unaccented)
    res_unaccent = link_concepts_from_data("Tôi muốn học dai so tuyen tinh", concepts)
    assert res_unaccent["concept_ids"] == ["c1"]
    assert res_unaccent["clarify"] is None

def test_link_concepts_unaccented_ambiguity_clarify():
    # 'ban' khớp cả 'bàn' và 'bản' -> SPEC yêu cầu gửi clarify, không tự chọn
    concepts = [
        {"id": "c_ban1", "name": "Bàn", "aliases": ["Table"]},
        {"id": "c_ban2", "name": "Bản", "aliases": ["Record"]},
    ]
    
    # Message dùng từ không dấu trùng cả hai
    res = link_concepts_from_data("Tìm kiếm thông tin ve ban", concepts)
    assert res["concept_ids"] == []
    assert res["clarify"] is not None
    clarify_ids = {c["id"] for c in res["clarify"]}
    assert clarify_ids == {"c_ban1", "c_ban2"}

def test_link_concepts_longest_match_first():
    concepts = [
        {"id": "c_grad", "name": "Gradient", "aliases": []},
        {"id": "c_descent", "name": "Gradient Descent", "aliases": []},
    ]
    
    res = link_concepts_from_data("Giải thích thuật toán Gradient Descent trong ML", concepts)
    # Phải match Gradient Descent (dài hơn), không match đơn lẻ Gradient
    assert res["concept_ids"] == ["c_descent"]

def test_link_concepts_alias_short_no_fuzzy():
    concepts = [
        {"id": "c_sql", "name": "Structured Query Language", "aliases": ["SQL"]},
    ]
    # 'SQL' có độ dài 3 < 4 (ALIAS_FUZZY_MIN_LEN), chỉ exact match
    res_exact = link_concepts_from_data("Học SQL cơ bản", concepts)
    assert res_exact["concept_ids"] == ["c_sql"]

    # Fuzzy lệch 'SQX' không được khớp vì alias quá ngắn
    res_fuzzy = link_concepts_from_data("Học SQX cơ bản", concepts)
    assert "c_sql" not in res_fuzzy["concept_ids"]

def test_unmatched_question_does_not_suggest_nearest_concepts():
    concepts = [
        {"id": "c1", "name": "OPTIONS", "aliases": [], "embedding": [1.0, 0.0]},
        {"id": "c2", "name": "Request Object", "aliases": [], "embedding": [0.9, 0.1]},
        {"id": "c3", "name": "Response Object", "aliases": [], "embedding": [0.8, 0.2]},
    ]
    result = link_concepts_from_data("weather tomorrow", concepts)
    assert result == {"concept_ids": [], "clarify": None}


def test_tied_fuzzy_matches_across_courses_request_clarification(monkeypatch):
    from app.config import settings

    monkeypatch.setattr(settings, "ALIAS_FUZZY_MIN_LEN", 4)
    monkeypatch.setattr(settings, "FUZZY_THRESHOLD", 85)
    concepts = [
        {"id": "course-a", "name": "abcdefx", "aliases": [], "course_id": "a", "course_name": "A"},
        {"id": "course-b", "name": "abcdefy", "aliases": [], "course_id": "b", "course_name": "B"},
    ]

    result = link_concepts_from_data("abcdefg", concepts)

    assert result["concept_ids"] == []
    assert {candidate["id"] for candidate in result["clarify"]} == {"course-a", "course-b"}


@pytest.mark.asyncio
async def test_linker_matches_concepts_after_candidate_limit(monkeypatch):
    from app.retrieval import concept_linker

    concepts = [
        {"id": f"c{i:03}", "name": f"Unrelated {i:03}", "aliases": [], "course_id": "course"}
        for i in range(100)
    ]
    concepts.append({"id": "target", "name": "Zebra Concept", "aliases": [], "course_id": "course"})

    class Result:
        def __init__(self, rows):
            self.rows = rows

        async def data(self):
            return self.rows

    class Session:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def run(self, query, **params):
            assert "LIMIT" not in query
            assert params["course_ids"] == ["course"]
            if "RETURN course.id AS course_id" in query:
                return Result([{"course_id": "course", "version": 0}])
            return Result(concepts)

    class Driver:
        def session(self):
            return Session()

    monkeypatch.setattr(concept_linker.neo4j_client, "driver", Driver())

    result = await link_concepts(course_id=None, message="Zebra Concept", course_ids=["course"])

    assert result == {"concept_ids": ["target"], "clarify": None}


@pytest.mark.asyncio
async def test_clarification_returns_all_matching_candidates(monkeypatch):
    from app.retrieval import concept_linker

    concepts = [
        {"id": f"concept-{i}", "name": "Shared Topic", "aliases": [], "course_id": "clarify-course"}
        for i in range(125)
    ]

    class Result:
        def __init__(self, rows):
            self.rows = rows

        async def data(self):
            return self.rows

    class Session:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def run(self, query, **params):
            if "RETURN course.id AS course_id" in query:
                return Result([{"course_id": "clarify-course", "version": 0}])
            return Result(concepts)

    class Driver:
        def session(self):
            return Session()

    monkeypatch.setattr(concept_linker.neo4j_client, "driver", Driver())

    result = await link_concepts(
        course_id=None, message="Shared Topic", course_ids=["clarify-course"]
    )

    assert len(result["clarify"]) == 125


@pytest.mark.asyncio
async def test_empty_course_scope_does_not_query_concepts(monkeypatch):
    from app.retrieval import concept_linker

    class Driver:
        def session(self):
            raise AssertionError("Neo4j must not be queried for an empty scope")

    monkeypatch.setattr(concept_linker.neo4j_client, "driver", Driver())
    assert await get_course_concepts_for_linking(course_ids=[]) == []


@pytest.mark.asyncio
async def test_scoped_concept_index_is_reused_between_turns(monkeypatch):
    from app.retrieval import concept_linker

    concept_queries = []
    version_queries = []

    class Result:
        def __init__(self, rows):
            self.rows = rows

        async def data(self):
            return self.rows

    class Session:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def run(self, query, **params):
            if "RETURN course.id AS course_id" in query:
                version_queries.append(query)
                return Result([{"course_id": "cached-course", "version": 1}])
            concept_queries.append(query)
            return Result([{"id": "cached", "name": "Cached Topic", "aliases": [],
                            "course_id": "cached-course"}])

    class Driver:
        def session(self):
            return Session()

    monkeypatch.setattr(concept_linker.neo4j_client, "driver", Driver())

    for _ in range(2):
        result = await link_concepts(
            course_id=None, message="Cached Topic", course_ids=["cached-course"]
        )
        assert result["concept_ids"] == ["cached"]

    assert len(concept_queries) == 1
    assert len(version_queries) == 2


@pytest.mark.asyncio
async def test_scoped_index_refreshes_after_course_version_changes(monkeypatch):
    from app.config import settings
    from app.retrieval import concept_linker

    version = 1
    concept_queries = []
    monkeypatch.setattr(settings, "CONCEPT_LINKING_CACHE_TTL_SECONDS", 60)
    concept_linker._CONCEPT_INDEX_CACHE.clear()

    class Result:
        def __init__(self, rows):
            self.rows = rows

        async def data(self):
            return self.rows

    class Session:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def run(self, query, **params):
            if "RETURN course.id AS course_id" in query:
                return Result([{"course_id": "archive-course", "version": version}])
            concept_queries.append(query)
            concepts = [
                {"id": "active", "name": "Shared Topic", "aliases": [], "course_id": "archive-course"}
            ]
            if version == 1:
                concepts.insert(0, {
                    "id": "archived", "name": "Shared Topic", "aliases": [],
                    "course_id": "archive-course",
                })
            return Result(concepts)

    class Driver:
        def session(self):
            return Session()

    monkeypatch.setattr(concept_linker.neo4j_client, "driver", Driver())

    before_archive = await link_concepts(
        course_id=None, message="Shared Topic", course_ids=["archive-course"]
    )
    assert {candidate["id"] for candidate in before_archive["clarify"]} == {"active", "archived"}

    version = 2
    after_archive = await link_concepts(
        course_id=None, message="Shared Topic", course_ids=["archive-course"]
    )

    assert after_archive == {"concept_ids": ["active"], "clarify": None}
    assert len(concept_queries) == 2


@pytest.mark.asyncio
async def test_concept_index_build_runs_outside_event_loop(monkeypatch):
    import threading
    from app.config import settings
    from app.retrieval import concept_linker

    loop_thread = threading.get_ident()
    build_threads = []
    monkeypatch.setattr(settings, "CONCEPT_LINKING_CACHE_TTL_SECONDS", 0)

    class Result:
        async def data(self):
            return [{"id": "offloaded", "name": "Offloaded Topic", "aliases": []}]

    class Session:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def run(self, query, **params):
            return Result()

    class Driver:
        def session(self):
            return Session()

    original_build = concept_linker._build_concept_index

    def build_index(concepts):
        build_threads.append(threading.get_ident())
        return original_build(concepts)

    monkeypatch.setattr(concept_linker.neo4j_client, "driver", Driver())
    monkeypatch.setattr(concept_linker, "_build_concept_index", build_index)

    result = await link_concepts(message="Offloaded Topic", course_ids=["offload-course"])

    assert result["concept_ids"] == ["offloaded"]
    assert build_threads
    assert all(thread_id != loop_thread for thread_id in build_threads)
