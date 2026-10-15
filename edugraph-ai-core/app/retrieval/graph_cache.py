import time
import logging
from typing import Dict, Any, Tuple, List, Optional
import networkx as nx

from app.config import settings
from app.db.neo4j_client import neo4j_client

logger = logging.getLogger(__name__)

class GraphConflictError(Exception):
    def __init__(self, conflicts: List[List[str]]):
        super().__init__(f"Graph contains cycle conflicts: {conflicts}")
        self.conflicts = conflicts

# Bộ nhớ đệm đồ thị trong RAM theo course_id
# course_id -> {"version": int, "graph": DiGraph, "evidences": dict, "conflicts": list, "last_checked": float}
_COURSE_GRAPH_CACHE: Dict[str, Dict[str, Any]] = {}

async def get_course_version(course_id: str) -> Optional[int]:
    query = "MATCH (c:Course {id: $course_id}) RETURN coalesce(c.version, 0) AS version"
    async with neo4j_client.driver.session() as session:
        res = await session.run(query, course_id=course_id)
        rec = await res.single()
        return rec["version"] if rec and rec["version"] is not None else 0

async def load_graph_from_neo4j(course_id: str) -> Tuple[nx.DiGraph, Dict[str, Any], List[List[str]]]:
    """
    Nạp đồ thị từ Neo4j:
    - Loại bỏ cạnh REJECTED (chỉ lấy AUTO, CONFIRMED, MANUAL).
    - Lấy evidence chunk theo thứ tự (Document.seq, Chunk.index) nhỏ nhất.
    - Phát hiện chu trình cứng toàn cạnh CONFIRMED/MANUAL.
    """
    # 1. Lấy tất cả Concept của khóa học
    q_concepts = """
    MATCH (c:Concept {course_id: $course_id})
    RETURN c.id AS id, c.name AS name, coalesce(c.order, 0) AS order
    """
    # 2. Lấy cạnh PREREQUISITE_OF không phải REJECTED
    q_edges = """
    MATCH (src:Concept {course_id: $course_id})-[r:PREREQUISITE_OF]->(tgt:Concept {course_id: $course_id})
    WHERE r.status <> 'REJECTED'
    RETURN src.id AS src_id, tgt.id AS tgt_id, r.status AS status, 
           coalesce(r.score, 0.0) AS score, coalesce(r.evidence, '') AS evidence
    """
    # 3. Lấy evidence chunk có (Document.seq, Chunk.index) nhỏ nhất
    q_evidences = """
    MATCH (c:Concept {course_id: $course_id})<-[m:MENTIONS {is_evidence: true}]-(ch:Chunk)
    OPTIONAL MATCH (d:Document {id: ch.doc_id, course_id: $course_id})
    RETURN c.id AS concept_id, ch.id AS chunk_id, ch.page AS page, 
           coalesce(d.seq, 0) AS doc_seq, coalesce(ch.index, 0) AS chunk_index,
           substring(ch.text, 0, 200) AS snippet
    ORDER BY doc_seq ASC, chunk_index ASC
    """

    async with neo4j_client.driver.session() as session:
        res_nodes = await session.run(q_concepts, course_id=course_id)
        nodes_data = await res_nodes.data()

        res_edges = await session.run(q_edges, course_id=course_id)
        edges_data = await res_edges.data()

        res_ev = await session.run(q_evidences, course_id=course_id)
        ev_data = await res_ev.data()

    # Dựng DiGraph
    G = nx.DiGraph()
    for n in nodes_data:
        G.add_node(n["id"], name=n["name"], order=n["order"])

    for e in edges_data:
        # Bỏ self-loop nếu có
        if e["src_id"] != e["tgt_id"]:
            G.add_edge(e["src_id"], e["tgt_id"], status=e["status"], score=e["score"], evidence=e["evidence"])

    # Map evidence (chọn theo (doc_seq, chunk_index) nhỏ nhất đã được sort)
    evidences: Dict[str, Dict[str, Any]] = {}
    for ev in ev_data:
        cid = ev["concept_id"]
        if cid not in evidences:
            evidences[cid] = {
                "chunk_id": ev["chunk_id"],
                "page": ev["page"],
                "snippet": ev["snippet"]
            }

    # Phát hiện chu trình cứng (toàn CONFIRMED/MANUAL)
    conflicts: List[List[str]] = []
    if not nx.is_directed_acyclic_graph(G):
        hard_edges = [
            (u, v) for u, v, d in G.edges(data=True) 
            if d.get("status") in ("CONFIRMED", "MANUAL")
        ]
        hard_G = nx.DiGraph()
        hard_G.add_edges_from(hard_edges)
        try:
            for cycle in nx.simple_cycles(hard_G):
                cycle_str = [f"{u}->{v}" for u, v in zip(cycle, cycle[1:] + [cycle[0]])]
                conflicts.append(cycle_str)
        except Exception as err:
            logger.warning(f"Error checking hard cycles: {err}")

    return G, evidences, conflicts

async def get_course_graph(
    course_id: str, 
    current_version: Optional[int] = None,
    force_reload: bool = False
) -> Tuple[nx.DiGraph, int, Dict[str, Any], List[List[str]]]:
    """
    Trả về (DiGraph, version, evidences, conflicts) được cache theo (course_id, version).
    Nếu caller truyền current_version (từ câu truy vấn gộp):
    - Nếu cache trùng version thì dùng luôn.
    - Nếu version khác cache thì nạp lại từ Neo4j.
    """
    now = time.time()
    cached = _COURSE_GRAPH_CACHE.get(course_id)

    # 1. Nếu caller đã cung cấp current_version
    if current_version is not None:
        if not force_reload and cached and (cached["version"] == current_version):
            cached["last_checked"] = now
            return cached["graph"], cached["version"], cached["evidences"], cached["conflicts"]
        
        # Nạp lại từ Neo4j
        G, evidences, conflicts = await load_graph_from_neo4j(course_id)
        _COURSE_GRAPH_CACHE[course_id] = {
            "version": current_version,
            "graph": G,
            "evidences": evidences,
            "conflicts": conflicts,
            "last_checked": now
        }
        return G, current_version, evidences, conflicts

    # 2. Nếu caller không truyền version, kiểm tra chu kỳ TTL
    interval = getattr(settings, "GRAPH_VERSION_CHECK_INTERVAL", 10.0)
    if not force_reload and cached and (now - cached["last_checked"] < interval):
        return cached["graph"], cached["version"], cached["evidences"], cached["conflicts"]

    # Đọc version từ DB
    db_version = await get_course_version(course_id)
    if db_version is None:
        db_version = 0

    if not force_reload and cached and (cached["version"] == db_version):
        cached["last_checked"] = now
        return cached["graph"], cached["version"], cached["evidences"], cached["conflicts"]

    # Nạp mới từ Neo4j
    G, evidences, conflicts = await load_graph_from_neo4j(course_id)
    _COURSE_GRAPH_CACHE[course_id] = {
        "version": db_version,
        "graph": G,
        "evidences": evidences,
        "conflicts": conflicts,
        "last_checked": now
    }
    return G, db_version, evidences, conflicts

def clear_course_graph_cache(course_id: Optional[str] = None):
    """Xóa cache bộ nhớ khi cần test hoặc reset."""
    if course_id:
        _COURSE_GRAPH_CACHE.pop(course_id, None)
    else:
        _COURSE_GRAPH_CACHE.clear()
