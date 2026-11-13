from app.db.queries import learning_paths as queries
from app.schemas.learning_paths import LearningPathPlanRequest
from fastapi import HTTPException
from fastapi.responses import JSONResponse

from app.db import graph_repository
from app.core.cache import cache, generate_cache_key
from app.retrieval.graph_cache import get_course_graph, GraphConflictError
from app.retrieval.path_engine import generate_learning_path


def _document_scope(req, record):
    if not req.document_id:
        return None
    status = record["doc_status"]
    if status is None:
        raise HTTPException(status_code=404, detail=f"Document '{req.document_id}' not found")
    if status != "DONE":
        return JSONResponse(status_code=409, content={
            "status": status, "progress": record.get("doc_progress"),
            "detail": f"Document is {status}, not DONE"})
    return set(record["doc_concept_ids"] or [])


async def _load_plan_context(req):
    async with graph_repository.session() as session:
        res = await graph_repository.run(session, queries.CREATE_LEARNING_PATH_PLAN_QUERY_1,
                                         course_id=req.course_id, doc_id=req.document_id)
        rec = await res.single()
        if not rec:
            raise HTTPException(status_code=404, detail="Course not found")
        return rec, _document_scope(req, rec)


def _validate_plan_concepts(graph, req):
    for ids, kind, status in [(req.goal_concept_ids, "Goal", 404), (req.known_concept_ids, "Known", 400)]:
        for concept_id in ids or []:
            if concept_id not in graph:
                raise HTTPException(status_code=status, detail=f"{kind} concept '{concept_id}' not found in course")


def _plan_mode(req):
    if req.goal_concept_ids:
        return "goal"
    if req.document_id:
        return "document"
    return "course"


async def create_learning_path_plan(req: LearningPathPlanRequest):
    # Java authenticates ownership/enrollment; user_id identifies the learner,
    # not necessarily the course owner.
    rec, doc_concept_ids = await _load_plan_context(req)
    if isinstance(doc_concept_ids, JSONResponse):
        return doc_concept_ids
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
    _validate_plan_concepts(G, req)

    # 4. Xác định chế độ lộ trình: goal | document | course
    mode = _plan_mode(req)

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

