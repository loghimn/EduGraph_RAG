import json
from unittest.mock import AsyncMock, MagicMock, patch

import networkx as nx
import pytest

from app.chat.path_intent import detect_path_intent
from app.schemas.chat import GraphRelationship
from app.services.chat import chat_explain_stream_generator
from app.services.chat_paths import build_chat_path, resolve_target


@pytest.mark.parametrize("question,target", [
    ("Muốn học Servlet cần biết gì trước?", "servlet"),
    ("Muốn học Servlet thì cần biết những gì trước?", "servlet"),
    ("Để học Servlet tôi cần biết những gì?", "servlet"),
    ("Cho tôi lộ trình học để master Servlet", "servlet"),
    ("Cho tôi lộ trình học để thành thạo Servlet.", "servlet"),
    ("cho toi lo trinh hoc ve servlet", "servlet"),
    ("Lập kế hoạch học Servlet", "servlet"),
    ("Tôi cần hiểu những gì trước khi học Servlet? Hãy dựa trên tài liệu.", "servlet"),
    ("Điều kiện tiên quyết của Servlet là gì?", "servlet"),
    ("Servlet cần học gì trước?", "servlet"),
    ("Create a learning path to master Servlet.", "servlet"),
    ("Give me a roadmap for learning Servlet", "servlet"),
    ("What should I know before learning Servlet?", "servlet"),
    ("What do I need to know before learning Servlet?", "servlet"),
    ("What should I understand before learning Servlet? Use selected sources.", "servlet"),
    ("Prerequisites for Servlet?", "servlet"),
])
def test_path_intent_recognizes_goal_and_prerequisite_requests(question, target):
    assert detect_path_intent(question).target == target


@pytest.mark.parametrize("question", ["Servlet là gì?", "Giải thích quan hệ Class → Object",
    "Lộ trình học là gì?", "What is a learning path?", "Explain a learning path",
    "Đừng tạo lộ trình, chỉ giải thích Servlet", "Do not create a roadmap for Servlet",
    "Cho tôi ví dụ Servlet"])
def test_explanations_and_negated_requests_do_not_create_paths(question):
    assert detect_path_intent(question) is None


@pytest.mark.parametrize("question", ["Tại sao lộ trình học Servlet bắt đầu từ Java?", "Explain why Java comes first in the learning path for Servlet", "Why is Java the first step in the roadmap for Servlet?"])
def test_questions_explaining_an_existing_order_do_not_regenerate_it(question):
    assert detect_path_intent(question) is None


def concept(cid="servlet", name="Servlet", course="7"):
    return {"id": cid, "name": name, "aliases": [], "course_id": course, "course_name": "Java Web"}


def graph():
    g = nx.DiGraph()
    for cid, name in [("java", "Java"), ("oop", "OOP"), ("http", "HTTP"), ("servlet", "Servlet")]:
        g.add_node(cid, name=name, order=0)
    g.add_edges_from([("java", "oop"), ("oop", "servlet"), ("http", "servlet")], status="CONFIRMED")
    return g


async def build(g=None, concepts=None, known=None, ids=None, evidences=None, courses=None):
    g = g if g is not None else graph()
    with patch("app.services.chat_paths.get_course_concepts_for_linking", AsyncMock(return_value=concepts if concepts is not None else [concept()])) as linker, \
         patch("app.services.chat_paths.get_course_graph", AsyncMock(return_value=(g, 12, evidences or {}, []))) as loader:
        result = await build_chat_path(detect_path_intent("Cho tôi lộ trình học để master Servlet"), courses or ["7"], ids, {"7": "Java Web"}, known)
    return result, linker, loader


@pytest.mark.asyncio
async def test_path_uses_topology_with_direct_and_indirect_prerequisites():
    result, _, loader = await build()
    text = result["answer"]
    assert "**Tiên quyết trực tiếp:** HTTP, OOP" in text
    assert "**Tiên quyết gián tiếp:** Java" in text
    assert text.index("### Giai đoạn 1") < text.index("### Giai đoạn 2") < text.index("### Giai đoạn 3")
    steps = [part for part in text.splitlines() if part.startswith("- **")]
    assert steps == ["- **HTTP**", "- **Java**", "- **OOP**", "- **Servlet** — mục tiêu"]
    loader.assert_awaited_once_with("7")


@pytest.mark.asyncio
async def test_mastered_nodes_are_removed_without_assuming_their_ancestors_are_known():
    result, _, _ = await build(known={"7": ["oop", "unknown"], "8": ["java", "http", "servlet"]})
    steps = [part for part in result["answer"].splitlines() if part.startswith("- **")]
    assert "- **OOP**" not in steps
    assert "- **Java**" in steps
    assert "- **HTTP**" in steps
    assert "**Đã nắm vững, được bỏ khỏi các bước:** OOP" in result["answer"]
    assert result["answer"].index("- **Java**") < result["answer"].index("### Giai đoạn 2") < result["answer"].index("- **Servlet**")


@pytest.mark.asyncio
async def test_all_mastered_goal_has_no_fake_steps():
    result, _, _ = await build(known={"7": list(graph().nodes)})
    assert "đánh dấu nắm vững toàn bộ" in result["answer"]
    assert "### Giai đoạn" not in result["answer"]


@pytest.mark.asyncio
async def test_related_cycles_block_generation_but_unrelated_cycles_do_not():
    g = graph()
    g.add_edges_from([("x", "y"), ("y", "x")])
    result, _, _ = await build(g)
    assert "### Giai đoạn" in result["answer"]
    g.add_edge("servlet", "java")
    result, _, _ = await build(g)
    assert "chu trình" in result["answer"]
    assert "### Giai đoạn" not in result["answer"]


@pytest.mark.asyncio
async def test_duplicate_names_require_a_choice_instead_of_merging_courses():
    result, _, loader = await build(concepts=[concept(), concept("other-servlet", course="8")], courses=["7", "8"])
    assert len(result["candidates"]) == 2
    loader.assert_not_awaited()


@pytest.mark.asyncio
async def test_invalid_explicit_goal_cannot_fall_back_to_another_concept():
    result, _, loader = await build(ids=["not-authorized"])
    assert "Chưa tìm thấy concept" in result["answer"]
    loader.assert_not_awaited()


@pytest.mark.asyncio
async def test_scope_is_checked_again_even_if_a_linker_returns_foreign_metadata():
    result, linker, loader = await build(concepts=[concept(course="8")])
    assert "Chưa tìm thấy concept" in result["answer"]
    assert linker.call_args.args == (["7"],)
    loader.assert_not_awaited()


@pytest.mark.asyncio
async def test_no_authorized_scope_never_reads_concepts_or_graph():
    with patch("app.services.chat_paths.get_course_concepts_for_linking", AsyncMock()) as linker:
        result = await build_chat_path(detect_path_intent("Learning path for Servlet"), [])
    assert result["chunks"] == []
    linker.assert_not_awaited()


@pytest.mark.asyncio
async def test_isolated_goal_explains_missing_dependencies_and_inferred_edges_are_labeled():
    g = graph(); g.remove_edges_from(list(g.edges()))
    result, _, _ = await build(g)
    assert "không có nghĩa mục tiêu không cần kiến thức nền" in result["answer"]
    g.add_edge("java", "servlet", status="AUTO")
    result, _, _ = await build(g)
    assert "1 quan hệ AUTO" in result["answer"]


@pytest.mark.asyncio
async def test_oversized_path_is_not_silently_truncated():
    g = graph()
    for i in range(201):
        g.add_edge(f"node-{i}", "servlet")
    result, _, _ = await build(g)
    assert "vượt giới hạn 200" in result["answer"]
    assert "### Giai đoạn" not in result["answer"]


def test_long_names_and_aliases_are_exact_matches_and_not_limited_to_four_words():
    items = [concept(name="A long concept with more than four words")]
    assert resolve_target(items[0]["name"], items, None) == items
    items[0]["aliases"] = ["Servlet"]
    assert resolve_target("Servlet", items, None) == items


@pytest.mark.asyncio
async def test_step_citations_use_scoped_document_passages():
    ev = {"java": {"chunk_id": "proof", "snippet": "Java", "page": 3}}
    response = MagicMock(); response.data = AsyncMock(return_value=[{"id": "proof", "doc_id": "9", "text": "Java text", "page": 3, "index": 0}])
    session = MagicMock(); session.__aenter__ = AsyncMock(return_value=session); session.__aexit__ = AsyncMock(return_value=None)
    with patch("app.services.chat_paths.graph_repository.session", return_value=session), \
         patch("app.services.chat_paths.graph_repository.run", AsyncMock(return_value=response)) as run:
        result, _, _ = await build(evidences=ev)
    assert "**Java** [1]" in result["answer"]
    assert result["chunks"][0]["doc_id"] == "9"
    assert run.call_args.kwargs == {"course_id": "7", "chunk_ids": ["proof"]}


@pytest.mark.asyncio
async def test_chat_stream_routes_to_path_engine_without_model_or_standard_retrieval():
    with patch("app.services.chat.build_chat_path", AsyncMock(return_value={"answer": "## Lộ trình\nJava → Servlet [1]", "chunks": [{"id": "proof", "doc_id": "9", "text": "Java"}]})) as path, \
         patch("app.services.chat.llm_client") as llm, \
         patch("app.services.chat.retrieve_chunks", AsyncMock()) as retrieve:
        frames = [frame async for frame in chat_explain_stream_generator("7", "Muốn học Servlet cần biết gì trước?", authorized_course_ids=["7", "8"], history=[{"role": "user", "content": "Another topic"}], known_concepts_by_course={"7": ["java"]})]
    assert path.call_args.args[1] == ["7"]
    assert path.call_args.args[-1] == {"7": ["java"]}
    retrieve.assert_not_awaited(); llm.generate.assert_not_called(); llm.generate_stream.assert_not_called()
    events = {f.splitlines()[0][7:]: json.loads(f.splitlines()[1][6:]) for f in frames if f.startswith("event:")}
    assert events["answer"]["content"].startswith("## Lộ trình")
    assert events["sources"][0]["doc_id"] == "9"
    assert frames[-1].startswith("event: done")


@pytest.mark.asyncio
async def test_path_clarification_replays_original_question_with_selected_goal():
    with patch("app.services.chat.build_chat_path", AsyncMock(return_value={"candidates": [concept()]})) as path:
        frames = [f async for f in chat_explain_stream_generator("7", "Cho tôi lộ trình học để master Servlet", ["servlet"], ["7"])]
    assert path.call_args.args[2] == ["servlet"]
    assert "event: clarify" in "".join(frames)
    assert "event: sources" not in "".join(frames)


@pytest.mark.asyncio
async def test_explicit_relationship_context_keeps_the_normal_explanation_flow():
    async def generate(*args, **kwargs):
        yield "Relationship explanation [1]."
    with patch("app.services.chat.build_chat_path", AsyncMock()) as path, \
         patch("app.services.chat.retrieve_chunks", AsyncMock(return_value=[{"id": "proof", "text": "Java"}])), \
         patch("app.services.chat.retrieve_relationship", AsyncMock(return_value=({"recorded": True}, []))), \
         patch("app.services.chat.llm_client.generate_stream", generate):
        frames = [f async for f in chat_explain_stream_generator("7", "Learning path for Servlet", ["java", "servlet"], ["7"], graph_relationship=GraphRelationship(source_id="java", target_id="servlet"))]
    path.assert_not_awaited()
    assert "Relationship explanation" in "".join(frames)


def test_internal_chat_endpoint_accepts_server_mastery_context_and_returns_complete_path():
    from fastapi.testclient import TestClient
    from app.main import app
    with patch("app.services.chat.verify_course_exists", AsyncMock()), \
         patch("app.services.chat_paths.get_course_concepts_for_linking", AsyncMock(return_value=[concept()])), \
         patch("app.services.chat_paths.get_course_graph", AsyncMock(return_value=(graph(), 12, {}, []))), \
         patch("app.services.chat.llm_client") as llm:
        response = TestClient(app).post("/chat", headers={"X-Internal-Key": "test-internal-api-key"}, json={
            "course_id": "7", "user_id": "2", "authorized_course_ids": ["7"],
            "message": "Cho tôi lộ trình học để master Servlet", "course_names": {"7": "Java Web"},
            "known_concepts_by_course": {"7": ["oop"]},
        })
    assert response.status_code == 200
    assert "### Giai đoạn" in response.text
    assert "**Đã nắm vững, được bỏ khỏi các bước:** OOP" in response.text
    assert "event: answer" in response.text
    assert "event: sources\ndata: []" in response.text
    assert response.text.endswith("event: done\ndata: {}\n\n")
    llm.generate_stream.assert_not_called()
