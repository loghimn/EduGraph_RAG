import logging
from typing import List, Dict, Any, Set, Optional, Tuple
import networkx as nx

from app.config import settings
from app.retrieval.graph_cache import GraphConflictError

logger = logging.getLogger(__name__)

def validate_path(G: nx.DiGraph, linear_order: List[str]) -> int:
    """
    Kiểm tra tính hợp lệ của thứ tự tuyến tính trên đồ thị gốc:
    Với mọi cạnh A -> B trong đồ thị gốc G mà cả A và B đều xuất hiện trong linear_order,
    A phải đứng trước B (pos_map[A] < pos_map[B]).
    Trả về số lượng vi phạm (0 = hợp lệ).
    """
    pos_map = {node_id: idx for idx, node_id in enumerate(linear_order)}
    violations = 0
    for u, v in G.edges():
        if u in pos_map and v in pos_map:
            if pos_map[u] >= pos_map[v]:
                violations += 1
    return violations

def generate_learning_path(
    G: nx.DiGraph,
    evidences: Dict[str, Dict[str, Any]],
    mode: str = "course",
    doc_concept_ids: Optional[Set[str]] = None,
    goal_concept_ids: Optional[List[str]] = None,
    known_concept_ids: Optional[List[str]] = None,
    include_external_prereqs: bool = True,
    assume_known_prerequisites: bool = True
) -> Dict[str, Any]:
    """
    Sinh lộ trình học tập từ đồ thị NetworkX:
    - Mode "course": Toàn bộ concept trong khóa học.
    - Mode "document": Toàn bộ concept của tài liệu + tiên quyết ngoài tài liệu (nếu bật).
    - Mode "goal": need = (ancestors(goal) ∪ goal) - known_all.
      + assume_known_prerequisites=True: known_all = known ∪ ancestors(known).
      + assume_known_prerequisites=False: known_all = known.
    - Tính stages bằng topological_generations (sắp xếp trong tầng theo Concept.order).
    - Tính linear_order bằng lexicographical_topological_sort (tie-break theo Concept.order).
    - cytoscape_data chỉ chứa: id, name, order, role, outside_document.
    - Cảnh báo đồ thị thưa: 1 tầng (>1 concept) và tỉ lệ concept cô lập vượt ngưỡng config.
    """
    outside_map: Dict[str, Optional[bool]] = {}

    if mode == "goal" and goal_concept_ids:
        # Chế độ theo mục tiêu
        known_set = set(known_concept_ids or [])
        if assume_known_prerequisites and known_set:
            known_ancestors = set().union(*(nx.ancestors(G, k) for k in known_set if k in G))
            known_all = known_set | known_ancestors
        else:
            known_all = known_set

        goal_set = set(goal_concept_ids)
        goal_ancestors = set().union(*(nx.ancestors(G, g) for g in goal_set if g in G)) if goal_set else set()
        
        need = (goal_set | goal_ancestors) - known_all
        for cid in need:
            outside_map[cid] = None  # Theo yêu cầu: chế độ goal đặt outside_document = null

    elif mode == "document" and doc_concept_ids is not None:
        # Chế độ theo tài liệu
        base_concepts = set(doc_concept_ids)
        if include_external_prereqs:
            ext_ancestors = set().union(*(nx.ancestors(G, c) for c in base_concepts if c in G)) if base_concepts else set()
            need = base_concepts | ext_ancestors
            for cid in need:
                outside_map[cid] = (cid not in base_concepts)
        else:
            need = base_concepts
            for cid in need:
                outside_map[cid] = False
    else:
        # Chế độ cả khóa học (mặc định nếu không có doc_id và goal_id)
        mode = "course"
        need = set(G.nodes())
        for cid in need:
            outside_map[cid] = False

    # Trường hợp mục tiêu đã được học hết hoặc need rỗng
    if not need:
        return {
            "mode": mode,
            "stages": [],
            "linear_order": [],
            "graph": {"elements": {"nodes": [], "edges": []}},
            "stats": {"concepts": 0, "edges": 0, "isolated_concepts": 0},
            "warnings": ["Mục tiêu học tập đã được nắm vững (nằm trong danh sách đã biết)."],
            "validation": {"violations": 0}
        }

    # 1. Trích xuất subgraph trên tập concept cần học
    subG = G.subgraph(need).copy()

    # 2. Kiểm tra chu trình trên subgraph của need-set (mọi vòng còn lại đều trả 409)
    if not nx.is_directed_acyclic_graph(subG):
        cycles = []
        try:
            for cycle in nx.simple_cycles(subG):
                cycle_str = [f"{u}->{v}" for u, v in zip(cycle, cycle[1:] + [cycle[0]])]
                cycles.append(cycle_str)
        except Exception as e:
            logger.warning(f"Error extracting cycles: {e}")
        raise GraphConflictError(cycles or [["Cycle detected in learning path subgraph"]])

    # 3. Phân tầng: topological_generations
    generations = list(nx.topological_generations(subG))
    stages = []
    for lvl, gen in enumerate(generations):
        # Sắp xếp các concept trong cùng tầng theo (Concept.order, name, id)
        sorted_gen = sorted(gen, key=lambda c: (subG.nodes[c].get("order", 0), subG.nodes[c].get("name", ""), c))
        steps = []
        for cid in sorted_gen:
            node_data = subG.nodes[cid]
            # Tiên quyết trong lộ trình là các predecessor trực tiếp trong subgraph
            preds = sorted(list(subG.predecessors(cid)), key=lambda u: (subG.nodes[u].get("order", 0), subG.nodes[u].get("name", ""), u))
            steps.append({
                "concept_id": cid,
                "name": node_data.get("name", cid),
                "order": node_data.get("order", 0),
                "prerequisites_in_path": preds,
                "outside_document": outside_map.get(cid),
                "evidence": evidences.get(cid)
            })
        stages.append({"level": lvl, "steps": steps})

    # 4. Thứ tự tuyến tính: lexicographical_topological_sort (tie-break theo Concept.order)
    linear_order = list(nx.lexicographical_topological_sort(
        subG, 
        key=lambda c: (subG.nodes[c].get("order", 0), subG.nodes[c].get("name", ""), c)
    ))

    # 5. Dữ liệu đồ thị Cytoscape (chỉ chứa id, name, order, role, outside_document)
    cyto_nodes = []
    for cid in subG.nodes:
        if mode == "goal":
            role = "goal" if cid in (goal_concept_ids or []) else "missing"
            out_doc = None
        elif mode == "document":
            out_doc = outside_map.get(cid, False)
            role = "external_prereq" if out_doc else "document"
        else:
            out_doc = False
            role = "course"

        cyto_nodes.append({
            "data": {
                "id": cid,
                "name": subG.nodes[cid].get("name", cid),
                "order": subG.nodes[cid].get("order", 0),
                "role": role,
                "outside_document": out_doc
            }
        })

    cyto_edges = []
    for u, v, d in subG.edges(data=True):
        cyto_edges.append({
            "data": {
                "source": u,
                "target": v,
                "status": d.get("status", "AUTO"),
                "score": d.get("score", 0.0)
            }
        })

    cytoscape = {
        "elements": {
            "nodes": cyto_nodes,
            "edges": cyto_edges
        }
    }

    # 6. Thống kê & Cảnh báo thưa
    num_concepts = subG.number_of_nodes()
    num_edges = subG.number_of_edges()
    isolated_list = list(nx.isolates(subG))
    isolated_count = len(isolated_list)

    stats = {
        "concepts": num_concepts,
        "edges": num_edges,
        "isolated_concepts": isolated_count
    }

    warnings = []
    # Cảnh báo 1: Lộ trình phẳng (toàn bộ concept ở 1 tầng duy nhất khi có > 1 concept)
    if len(stages) == 1 and num_concepts > 1:
        warnings.append("Lộ trình phẳng: toàn bộ concept nằm ở một tầng duy nhất.")
    
    # Cảnh báo 2: Tỉ lệ concept cô lập vượt ngưỡng cấu hình
    if num_concepts > 0:
        iso_ratio = isolated_count / num_concepts
        threshold = getattr(settings, "ISOLATED_CONCEPTS_WARNING_THRESHOLD", 0.2)
        if iso_ratio >= threshold and isolated_count > 0:
            warnings.append(f"Tỉ lệ concept cô lập cao: {isolated_count}/{num_concepts} ({iso_ratio*100:.1f}%).")

    # 7. Hàm kiểm chứng (Validation trên đồ thị gốc G)
    violations = validate_path(G, linear_order)
    validation = {"violations": violations}

    return {
        "mode": mode,
        "stages": stages,
        "linear_order": linear_order,
        "graph": cytoscape,
        "stats": stats,
        "warnings": warnings,
        "validation": validation
    }
