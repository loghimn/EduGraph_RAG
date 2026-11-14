"""Render graph-computed steps; the language model cannot reorder dependencies."""
import re
import networkx as nx


def label(value) -> str:
    text = " ".join(str(value).split()).replace("[", "［").replace("]", "］")
    return re.sub(r"([\\`*_{}()#+!|<>])", r"\\\1", text)


def _stage_lines(result, goal_id, chunks):
    if not result["linear_order"]:
        return ["Bạn đã đánh dấu nắm vững toàn bộ các concept cần cho mục tiêu này."]
    lines = ["Các concept cùng giai đoạn có thể học song song; hoàn thành các giai đoạn trước rồi chuyển tiếp."]
    source_numbers = {ch["id"]: i for i, ch in enumerate(chunks, 1)}
    for stage in result["stages"]:
        lines.append(f"### Giai đoạn {stage['level'] + 1}")
        for step in stage["steps"]:
            citation = source_numbers.get((step.get("evidence") or {}).get("chunk_id"))
            refs = f" [{citation}]" if citation else ""
            goal = " — mục tiêu" if step["concept_id"] == goal_id else ""
            lines.append(f"- **{label(step['name'])}**{goal}{refs}")
    lines.append("Với mỗi concept: đọc tài liệu nguồn, tự giải thích bằng lời của mình và làm một ví dụ hoặc bài tập trước khi chuyển sang phần phụ thuộc vào nó.")
    return lines


def _source_warning_lines(graph, relevant_ids, result, chunks):
    lines = []
    # Graph edges can be inferred; they are not documentary proof.
    inferred = sum(data.get("status", "AUTO") == "AUTO" for _, _, data in graph.subgraph(relevant_ids).edges(data=True))
    if inferred:
        lines.append(f"Có **{inferred} quan hệ AUTO** trong phần đồ thị dẫn đến mục tiêu; đây là quan hệ suy luận chưa được xác nhận.")
    available = {ch["id"] for ch in chunks}
    missing = sum((step.get("evidence") or {}).get("chunk_id") not in available for stage in result["stages"] for step in stage["steps"])
    return lines, missing


def render_path_answer(target, course_name, graph, result, known, chunks, version):
    goal_id = target["id"]
    direct = set(graph.predecessors(goal_id))
    indirect = nx.ancestors(graph, goal_id) - direct
    ordered = lambda ids: sorted(ids, key=lambda cid: (graph.nodes[cid].get("order", 0), graph.nodes[cid].get("name", ""), cid))
    names = lambda ids: ", ".join(label(graph.nodes[cid].get("name", cid)) for cid in ordered(ids))
    lines = [f"## Lộ trình học: {label(target['name'])}",
             f"Nguồn đồ thị: **{label(course_name)}** · phiên bản {version}.",
             f"**Tiên quyết trực tiếp:** {names(direct) if direct else 'Chưa có quan hệ được ghi nhận trong đồ thị.'}",
             f"**Tiên quyết gián tiếp:** {names(indirect) if indirect else 'Chưa có quan hệ được ghi nhận trong đồ thị.'}"]
    if known:
        relevant = (direct | indirect | {goal_id}) & set(known)
        if relevant:
            lines.append(f"**Đã nắm vững, được bỏ khỏi các bước:** {names(relevant)}.")
    lines.extend(_stage_lines(result, goal_id, chunks))
    warnings, missing = _source_warning_lines(graph, direct | indirect | {goal_id}, result, chunks)
    lines.extend(warnings)
    if not direct:
        lines.append("Đồ thị chưa ghi nhận tiên quyết cho mục tiêu; điều này không có nghĩa mục tiêu không cần kiến thức nền.")
    lines.append("Các trích dẫn gắn với nội dung concept; thứ tự học được tính từ quan hệ trong đồ thị.")
    if missing:
        lines.append(f"Có {missing} concept chưa có đoạn trích dẫn hiển thị trong lượt này.")
    for warning in result.get("warnings", []):
        lines.append(f"- {label(warning)}")
    return "\n\n".join(lines)
