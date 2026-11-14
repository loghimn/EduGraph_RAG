"""Build a goal path in one authorized course and render it for the chat stream."""
import asyncio
import networkx as nx

from app.chat.path_answer import render_path_answer, label
from app.db import graph_repository
from app.db.queries.chat import PATH_EVIDENCE
from app.retrieval.concept_linker import get_course_concepts_for_linking, link_concepts_from_data
from app.retrieval.graph_cache import get_course_graph
from app.retrieval.path_engine import generate_learning_path
from app.core.text_utils import normalize_concept_key, normalize_text_unaccent

MAX_PATH_CONCEPTS = 200
MAX_PATH_SOURCES = 24


def resolve_target(target, concepts, concept_ids):
    if concept_ids:
        ids = set(concept_ids)
        return [c for c in concepts if c["id"] in ids]
    normalized = normalize_text_unaccent(normalize_concept_key(target))
    exact = [c for c in concepts if any(normalize_text_unaccent(normalize_concept_key(name)) == normalized
                                      for name in [c["name"], *(c.get("aliases") or [])] if name)]
    if exact:
        return exact
    linked = link_concepts_from_data(target, concepts)
    ids = set(linked.get("concept_ids") or [c["id"] for c in linked.get("clarify") or []])
    return [c for c in concepts if c["id"] in ids]


async def build_chat_path(intent, courses, concept_ids=None, course_names=None, known_by_course=None):
    if not courses:
        return {"answer": "Chưa có nguồn kiến thức được phép truy cập để tạo lộ trình.", "chunks": []}
    concepts = await get_course_concepts_for_linking(courses, course_names=course_names)
    concepts = [c for c in concepts if c.get("course_id") in courses]
    targets = await asyncio.to_thread(resolve_target, intent.target, concepts, concept_ids)
    if not targets:
        return {"answer": "Chưa tìm thấy concept mục tiêu trong nguồn đang chọn. Hãy nhập tên concept cụ thể hoặc chọn đúng khóa học/sổ tay.", "chunks": []}
    if len(targets) > 1:
        return {"candidates": [{k: c.get(k) for k in ("id", "name", "course_id", "course_name")} for c in targets[:50]]}
    target = targets[0]
    course_id = target.get("course_id")
    if course_id not in courses:
        return {"answer": "Concept mục tiêu không thuộc nguồn được phép truy cập.", "chunks": []}
    graph, version, evidences, _ = await get_course_graph(course_id)
    goal_id = target["id"]
    if goal_id not in graph:
        return {"answer": "Concept mục tiêu đã thay đổi hoặc được lưu trữ. Hãy chọn lại concept đang hoạt động.", "chunks": []}
    ancestors = await asyncio.to_thread(nx.ancestors, graph, goal_id)
    relevant = ancestors | {goal_id}
    if len(relevant) > MAX_PATH_CONCEPTS:
        return {"answer": f"Mục tiêu **{label(target['name'])}** có {len(relevant)} concept liên quan, vượt giới hạn {MAX_PATH_CONCEPTS} concept mỗi lượt. Hãy chọn một mục tiêu nhỏ hơn để tạo lộ trình đầy đủ.", "chunks": []}
    if not nx.is_directed_acyclic_graph(graph.subgraph(relevant)):
        return {"answer": "Phần đồ thị dẫn đến mục tiêu có chu trình tiên quyết nên chưa thể tạo thứ tự học hợp lệ. Hãy sửa các quan hệ tạo vòng trong đồ thị rồi thử lại.", "chunks": []}
    known = set((known_by_course or {}).get(course_id, [])) & set(graph.nodes)
    # Never infer that knowing one node means all its ancestors are mastered.
    result = await asyncio.to_thread(generate_learning_path, graph, evidences, mode="goal",
                                     goal_concept_ids=[goal_id], known_concept_ids=sorted(known),
                                     assume_known_prerequisites=False)
    chunk_ids = list(dict.fromkeys((evidences.get(cid) or {}).get("chunk_id") for cid in result["linear_order"]))
    chunk_ids = [cid for cid in chunk_ids if cid][:MAX_PATH_SOURCES]
    chunks = []
    if chunk_ids:
        async with graph_repository.session() as session:
            response = await graph_repository.run(session, PATH_EVIDENCE, course_id=course_id, chunk_ids=chunk_ids)
            rows = await response.data()
        by_id = {row["id"]: row for row in rows if row.get("text")}
        chunks = [by_id[cid] for cid in chunk_ids if cid in by_id]
    name = (course_names or {}).get(course_id) or target.get("course_name") or f"Nguồn #{course_id}"
    answer = render_path_answer(target, name, graph, result, known, chunks, version)
    if len(chunk_ids) >= MAX_PATH_SOURCES:
        answer += f"\n\nHiển thị tối đa {MAX_PATH_SOURCES} đoạn trích trong lượt này."
    return {"answer": answer, "chunks": chunks}
