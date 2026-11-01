import os
import logging
import asyncio

from fastapi import APIRouter, Header, Query, HTTPException, BackgroundTasks, UploadFile, File
from typing import Optional
from pydantic import BaseModel

from app.config import settings
from app.api.internal_auth import verify_internal_key
from app.db.neo4j_client import neo4j_client
from app.concept_extraction.pipeline import process_document_pipeline
from app.prerequisite.pipeline import run_prerequisite_pipeline
from app.ingestion.loaders import get_safe_path
from app.core.errors import InvalidPathError
from app.core.identifiers import DocumentId
from scripts.run_pipeline import delete_document
from app.core.document_tasks import document_tasks

logger = logging.getLogger(__name__)
router = APIRouter()

class IngestRequest(BaseModel):
    document_id: DocumentId
    course_id: str
    user_id: str
    filename: Optional[str] = "unknown.pdf"
    restart: bool = False
    run_id: Optional[str] = None

async def _background_ingest(doc_id: str, course_id: str, filename: str):
    try:
        query = """
        MATCH (d:Document {id: $doc_id, course_id: $course_id})
        SET d.status = 'PROCESSING',
            d.error = null,
            d.current_step = 'EXTRACTING_TEXT_AND_CHUNKING',
            d.progress = null,
            d.updated_at = timestamp()
        """
        async with neo4j_client.driver.session() as session:
            await session.run(query, doc_id=doc_id, course_id=course_id)
            
        import app.concept_extraction.pipeline as extraction_pipeline
        import app.prerequisite.pipeline as prereq_pipeline

        logger.info(f"[{doc_id}] ===== BẮT ĐẦU ingest (course={course_id}, file={filename}) =====")
        import time as _time
        t0 = _time.perf_counter()
        logger.info(f"[{doc_id}] ► Bước 1/2: trích xuất concept...")
        extraction_result = await extraction_pipeline.process_document_pipeline(doc_id, course_id, filename)
        # Lấy danh sách concept mới để chạy prereq theo incremental mode
        new_concept_ids = (extraction_result or {}).get("new_concept_ids", None)
        changed_concept_ids = (extraction_result or {}).get("changed_concept_ids", None)
        logger.info(
            f"[{doc_id}] ► Bước 2/2: tiên quyết... "
            f"(trích xuất mất {_time.perf_counter() - t0:.1f}s)"
            + (f" | {len(new_concept_ids)} concept mới" if new_concept_ids is not None else " | full mode")
        )
        async with neo4j_client.driver.session() as session:
            await session.run(
                """
                MATCH (d:Document {id: $doc_id, course_id: $course_id})
                SET d.current_step = 'INFERRING_PREREQUISITES',
                    d.progress = null,
                    d.updated_at = timestamp()
                """,
                doc_id=doc_id,
                course_id=course_id
            )
        await prereq_pipeline.run_prerequisite_pipeline(course_id, new_concept_ids=new_concept_ids, changed_concept_ids=changed_concept_ids)
        logger.info(f"[{doc_id}] ===== HOÀN TẤT cả 2 bước =====")

        query = "MATCH (d:Document {id: $doc_id, course_id: $course_id}) SET d.status = 'DONE', d.updated_at = timestamp()"
        async with neo4j_client.driver.session() as session:
            await session.run(query, doc_id=doc_id, course_id=course_id)

    except asyncio.CancelledError:
        logger.info("[%s] Ingest stopped", doc_id)
        raise
    except Exception as e:
        logger.exception(f"[{doc_id}] ✖ LỖI background ingest: {e}")
        query = """
        MATCH (d:Document {id: $doc_id, course_id: $course_id})
        SET d.status = 'FAILED', d.error = $error, d.updated_at = timestamp()
        """
        async with neo4j_client.driver.session() as session:
            await session.run(query, doc_id=doc_id, course_id=course_id, error=str(e))

@router.post("/ingest")
async def ingest_document(
    req: IngestRequest, 
    background_tasks: BackgroundTasks,
    x_internal_key: str = Header(...)
):
    verify_internal_key(x_internal_key)
    
    # 1. Upsert Course & kiểm tra quyền sở hữu (Java là nguồn sự thật về metadata;
    #    node Course/Document được tạo tại đây vì Python sở hữu graph)
    query_course = """
    MERGE (c:Course {id: $course_id})
    ON CREATE SET c.owner_id = $user_id, c.version = 0
    SET c.version = coalesce(c.version, 0)
    RETURN c.owner_id AS owner_id
    """
    async with neo4j_client.driver.session() as session:
        rec = await session.run(query_course, course_id=req.course_id, user_id=req.user_id)
        single_course = await rec.single()
        if single_course["owner_id"] != req.user_id:
            raise HTTPException(status_code=403, detail="Permission denied: user is not course owner")

    # Register under the same lock used by pause/delete; duplicate requests are
    # acknowledgements of the existing run, never a second background pipeline.
    query_doc = """
    MERGE (d:Document {id: $doc_id, course_id: $course_id})
    ON CREATE SET d.status = 'UPLOADED'
    SET d.updated_at = timestamp()
    RETURN d.status AS status, properties(d)['run_id'] AS run_id
    """
    async with document_tasks.lock(req.document_id):
        if document_tasks.active(req.document_id):
            return {"status": "processing"}
        async with neo4j_client.driver.session() as session:
            result = await session.run(query_doc, doc_id=req.document_id, course_id=req.course_id)
            doc = await result.single()
            if doc and doc["status"] == "CANCELLED":
                raise HTTPException(status_code=409, detail="Document has been cancelled")
            if doc and doc["status"] == "PAUSED" and (
                not req.restart or (req.run_id is not None and req.run_id == doc.get("run_id"))
            ):
                raise HTTPException(status_code=409, detail="Document is paused; explicit retry required")
            await session.run(
                "MATCH (d:Document {id: $doc_id, course_id: $course_id}) "
                "SET d.status = 'PROCESSING', d.run_id = $run_id, d.error = null, d.updated_at = timestamp()",
                doc_id=req.document_id, course_id=req.course_id, run_id=req.run_id)
        document_tasks.start(req.document_id, lambda: _background_ingest(
            req.document_id, req.course_id, req.filename))
    return {"status": "processing"}


async def verify_document_owner(course_id: str, user_id: str):
    async with neo4j_client.driver.session() as session:
        result = await session.run(
            "MATCH (c:Course {id: $course_id}) RETURN c.owner_id AS owner_id",
            course_id=course_id)
        course = await result.single()
    if not course:
        raise HTTPException(status_code=404, detail="Course not found")
    if course["owner_id"] != user_id:
        raise HTTPException(status_code=403, detail="Permission denied: user is not course owner")


@router.patch("/documents/{doc_id}/pause")
async def pause_document_endpoint(
    doc_id: DocumentId, course_id: str, user_id: str,
    x_internal_key: str = Header(...)
):
    verify_internal_key(x_internal_key)
    await verify_document_owner(course_id, user_id)
    async with document_tasks.lock(doc_id):
        async with neo4j_client.driver.session() as session:
            result = await session.run(
                "MATCH (d:Document {id: $doc_id, course_id: $course_id}) RETURN d.status AS status",
                doc_id=doc_id, course_id=course_id)
            doc = await result.single()
        if not doc:
            raise HTTPException(status_code=404, detail="Document not found")
        if doc["status"] not in {"UPLOADED", "PROCESSING", "PAUSED"}:
            raise HTTPException(status_code=409, detail="Document cannot be paused in its current state")
        await document_tasks.stop(doc_id)
        async with neo4j_client.driver.session() as session:
            await session.run(
                "MATCH (d:Document {id: $doc_id, course_id: $course_id}) "
                "SET d.status = 'PAUSED', d.error = null, d.updated_at = timestamp()",
                doc_id=doc_id, course_id=course_id)
    return {"status": "paused"}

@router.delete("/documents/{doc_id}")
async def delete_document_endpoint(
    doc_id: str,
    course_id: str ,
    user_id: str ,
    x_internal_key: str = Header(...)
):
    verify_internal_key(x_internal_key)
    
    await verify_document_owner(course_id, user_id)
    async with document_tasks.lock(doc_id):
        async with neo4j_client.driver.session() as session:
            result = await session.run(
                "MATCH (d:Document {id: $doc_id, course_id: $course_id}) RETURN d.status AS status",
                doc_id=doc_id, course_id=course_id)
            if not await result.single():
                raise HTTPException(status_code=404, detail="Document not found")
        # Drain extraction/prerequisite children before deleting any graph data.
        await document_tasks.stop(doc_id)
        await delete_document(course_id, doc_id)
        # Retain only a cancellation tombstone to reject delayed ingest requests.
        async with neo4j_client.driver.session() as session:
            await session.run(
                "MERGE (d:Document {id: $doc_id, course_id: $course_id}) "
                "SET d.status = 'CANCELLED', d.updated_at = timestamp()",
                doc_id=doc_id, course_id=course_id)
    return {"status": "deleted"}

async def recover_processing_documents():
    # Single-worker deployment: BackgroundTasks die with the process, so every
    # leftover PROCESSING document at startup is orphaned by definition.
    # No age filter on purpose: heartbeat keeps updated_at fresh, which would
    # otherwise let recently-interrupted docs slip through and hang forever.
    query = """
    MATCH (d:Document {status: 'PROCESSING'})
    SET d.status = 'FAILED', d.error = 'interrupted by restart', d.updated_at = timestamp()
    """
    async with neo4j_client.driver.session() as session:
        await session.run(query)
        logger.info("Startup recovery: marked orphaned PROCESSING documents as FAILED")

@router.post("/courses/{course_id}/prerequisites")
async def generate_course_prerequisites(
    course_id: str,
    user_id: str = Query(..., description="ID người dùng sở hữu khóa học"),
    background_tasks: BackgroundTasks = BackgroundTasks(),
    x_internal_key: str = Header(...)
):
    verify_internal_key(x_internal_key)
    
    # Kiểm tra Course & Quyền sở hữu
    query_course = "MATCH (c:Course {id: $course_id}) RETURN c.owner_id AS owner_id"
    async with neo4j_client.driver.session() as session:
        result = await session.run(query_course, course_id=course_id)
        single = await result.single()
        if not single:
            raise HTTPException(status_code=404, detail="Course not found")
        if single["owner_id"] != user_id:
            raise HTTPException(status_code=403, detail="Permission denied: user is not course owner")
            
    async def _bg_prereq():
        try:
            await run_prerequisite_pipeline(course_id)
        except Exception as e:
            print(f"Error in background prerequisites: {e}")

    background_tasks.add_task(_bg_prereq)
    return {"status": "processing prerequisites"}


# ---------------------------------------------------------------------------
# Internal endpoints phục vụ Java core-service (không public ra ngoài)
# ---------------------------------------------------------------------------

ALLOWED_UPLOAD_EXTENSIONS = {".pdf", ".docx", ".pptx", ".md", ".txt"}


@router.post("/documents/{doc_id}/file")
async def upload_document_file(
    doc_id: DocumentId,
    file: UploadFile = File(...),
    x_internal_key: str = Header(...)
):
    """
    Nhận file tài liệu từ Java core-service và lưu vào RAW_DATA_DIR/{doc_id}/{filename}.
    Phải được gọi TRƯỚC POST /ingest vì pipeline đọc file từ đĩa local.
    """
    verify_internal_key(x_internal_key)

    safe_name = os.path.basename(file.filename or "").strip()
    if not safe_name:
        raise HTTPException(status_code=400, detail="File name must not be empty")

    ext = os.path.splitext(safe_name)[1].lower()
    if ext not in ALLOWED_UPLOAD_EXTENSIONS:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported file type '{ext}'. Allowed: {sorted(ALLOWED_UPLOAD_EXTENSIONS)}"
        )

    content = await file.read()
    max_bytes = settings.MAX_UPLOAD_MB * 1024 * 1024
    if len(content) > max_bytes:
        raise HTTPException(status_code=413, detail=f"File exceeds {settings.MAX_UPLOAD_MB} MB limit")
    if not content:
        raise HTTPException(status_code=400, detail="File is empty")

    try:
        target_path = get_safe_path(doc_id, safe_name)
    except InvalidPathError as e:
        raise HTTPException(status_code=400, detail=str(e))

    async with document_tasks.lock(doc_id):
        if document_tasks.active(doc_id):
            raise HTTPException(status_code=409, detail="Document is currently processing")
        try:
            os.makedirs(os.path.dirname(target_path), exist_ok=True)
            with open(target_path, "wb") as f:
                f.write(content)
        except OSError as e:
            raise HTTPException(status_code=500, detail=f"Failed to store file: {e}")

    return {"status": "stored", "document_id": doc_id, "filename": safe_name}


@router.get("/documents/{doc_id}/status")
async def get_document_status(
    doc_id: str,
    course_id: str = Query(..., description="ID khóa học chứa document"),
    x_internal_key: str = Header(...)
):
    """Trả trạng thái xử lý của document trong Neo4j — Java poll để đồng bộ sang Postgres."""
    verify_internal_key(x_internal_key)

    query = """
    MATCH (d:Document {id: $doc_id, course_id: $course_id})
    RETURN d.status AS status,
           properties(d)['progress'] AS progress,
           properties(d)['error'] AS error,
           properties(d)['current_step'] AS current_step
    """
    async with neo4j_client.driver.session() as session:
        rec = await session.run(query, doc_id=doc_id, course_id=course_id)
        doc = await rec.single()

    if not doc:
        raise HTTPException(status_code=404, detail=f"Document '{doc_id}' not found")

    return {
        "document_id": doc_id,
        "course_id": course_id,
        "status": doc["status"],
        "progress": doc["progress"],
        "error": doc["error"],
        "current_step": doc.get("current_step"),
    }
