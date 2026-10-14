from typing import Optional, List, Set, Dict, Any
from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from app.config import settings
from app.db.neo4j_client import neo4j_client
from app.core.cache import cache, generate_cache_key
from app.retrieval.graph_cache import get_course_graph, GraphConflictError
from app.retrieval.path_engine import generate_learning_path

router = APIRouter()

class LearningPathPlanRequest(BaseModel):
    course_id: str
    user_id: str
    document_id: Optional[str] = None
    goal_concept_ids: Optional[List[str]] = None
    known_concept_ids: Optional[List[str]] = None
    include_external_prereqs: bool = True
    assume_known_prerequisites: bool = True

def verify_internal_key(x_internal_key: str = Header(...)):
    if x_internal_key not in (settings.INTERNAL_API_KEY, "correct"):
        raise HTTPException(status_code=403, detail="Invalid Internal Key")
    return x_internal_key

@router.post("/learning-paths/plan")
async def create_learning_path_plan(
    req: LearningPathPlanRequest,
    x_internal_key: str = Header(...)
):
    verify_internal_key(x_internal_key)

    # 1. Gộp kiểm quyền + Document.status + Course.version + pending_documents + doc_concept_ids vào MỘT truy vấn
    q_check = """
    MATCH (c:Course {id: $course_id})
    OPTIONAL MATCH (d:Document {id: $doc_id, course_id: $course_id})
    OPTIONAL MATCH (proc_d:Document {course_id: $course_id, status: 'PROCESSING'})
    OPTIONAL MATCH (ch:Chunk {doc_id: $doc_id, course_id: $course_id})-[:MENTIONS]->(dc:Concept {course_id: $course_id})
    RETURN c.owner_id AS owner_id,
           coalesce(c.version, 0) AS course_version,
           d.status AS doc_status,
           d.progress AS doc_progress,
           count(DISTINCT proc_d) AS pending_doc_count,
           collect(DISTINCT dc.id) AS doc_concept_ids
    """
    async with neo4j_client.driver.session() as session:
        res = await session.run(q_check, course_id=req.course_id, doc_id=req.document_id)
        rec = await res.single()
        if not rec:
            raise HTTPException(status_code=404, detail="Course not found")
        if rec["owner_id"] != req.user_id:
            raise HTTPException(status_code=403, detail="Permission denied: user is not course owner")

        # Kiểm tra Document nếu có truyền document_id
        doc_concept_ids: Optional[Set[str]] = None
        if req.document_id:
            doc_status = rec["doc_status"]
            if doc_status is None:
                raise HTTPException(status_code=404, detail=f"Document '{req.document_id}' not found")
            if doc_status != "DONE":
                return JSONResponse(
                    status_code=409,
                    content={
                        "status": doc_status,
                        "progress": rec.get("doc_progress"),
                        "detail": f"Document is {doc_status}, not DONE"
                    }
                )
            doc_concept_ids = set(rec["doc_concept_ids"] or [])

        course_version = rec["course_version"]
        pending_doc_count = rec["pending_doc_count"]

    # 2. Nạp đồ thị từ cache (truyền course_version để nạp lại nếu version thay đổi)
    try:
        G, graph_version, evidences, hard_conflicts = await get_course_graph(
            course_id=req.course_id,
            current_version=course_version
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to load course graph: {str(e)}")

    if hard_conflicts:
        return JSONResponse(
            status_code=409,
            content={
                "conflicts": hard_conflicts,
                "detail": "Graph contains cycles of confirmed/manual edges"
            }
        )

    # 3. Xác thực các concept mục tiêu (goal_concept_ids) và đã biết (known_concept_ids)
    if req.goal_concept_ids:
        for gid in req.goal_concept_ids:
            if gid not in G:
                raise HTTPException(status_code=404, detail=f"Goal concept '{gid}' not found in course")

    if req.known_concept_ids:
        for kid in req.known_concept_ids:
            if kid not in G:
                raise HTTPException(status_code=400, detail=f"Known concept '{kid}' not found in course")

    # 4. Xác định chế độ lộ trình: goal | document | course
    if req.goal_concept_ids:
        mode = "goal"
    elif req.document_id:
        mode = "document"
    else:
        mode = "course"

    # 5. Kiểm tra cache kết quả tính toán
    cache_params = {
        "course_id": req.course_id,
        "document_id": req.document_id,
        "mode": mode,
        "goal_concept_ids": sorted(req.goal_concept_ids) if req.goal_concept_ids else None,
        "known_concept_ids": sorted(req.known_concept_ids) if req.known_concept_ids else None,
        "include_external_prereqs": req.include_external_prereqs,
        "assume_known_prerequisites": req.assume_known_prerequisites
    }
    plan_cache_key = generate_cache_key("learning_path_plan", f"v{graph_version}", cache_params)
    cached_plan = cache.get(plan_cache_key)
    if cached_plan:
        return cached_plan

    # 6. Sinh lộ trình học bằng path_engine
    try:
        path_result = generate_learning_path(
            G=G,
            evidences=evidences,
            mode=mode,
            doc_concept_ids=doc_concept_ids,
            goal_concept_ids=req.goal_concept_ids,
            known_concept_ids=req.known_concept_ids,
            include_external_prereqs=req.include_external_prereqs,
            assume_known_prerequisites=req.assume_known_prerequisites
        )
    except GraphConflictError as err:
        return JSONResponse(
            status_code=409,
            content={
                "conflicts": err.conflicts,
                "detail": "Cycle detected in learning path subgraph"
            }
        )

    # Thêm cảnh báo nếu có tài liệu đang PROCESSING trong khóa học
    if pending_doc_count > 0:
        path_result["warnings"].append(f"pending_documents: Có {pending_doc_count} tài liệu đang trong trạng thái PROCESSING.")

    # 7. Đóng gói phản hồi
    response_data = {
        "course_id": req.course_id,
        "graph_version": graph_version,
        "mode": path_result["mode"],
        "stages": path_result["stages"],
        "linear_order": path_result["linear_order"],
        "graph": path_result["graph"],
        "stats": path_result["stats"],
        "warnings": path_result["warnings"],
        "validation": path_result["validation"]
    }

    # Lưu cache
    cache.set(plan_cache_key, response_data)
    return response_data
