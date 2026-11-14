"""HTTP validation/authentication adapter; use cases live in app.services."""

from fastapi import APIRouter, Header, Query, BackgroundTasks, UploadFile, File
from typing import Annotated
from app.core.identifiers import DocumentId
from app.core.internal_auth import verify_internal_key
from app.schemas.ingest import IngestRequest, PublishRequest
from app.services import ingest as service
from app.services import document_review

router = APIRouter()


@router.get('/documents/{doc_id}/review')
async def preview_document(doc_id: DocumentId, course_id: str, user_id: str, x_internal_key: Annotated[str, Header()]):
    verify_internal_key(x_internal_key)
    return await document_review.preview(doc_id, course_id, user_id)


@router.post('/documents/{doc_id}/publish')
async def publish_document(doc_id: DocumentId, request: PublishRequest,
                           course_id: str, user_id: str, x_internal_key: Annotated[str, Header()]):
    verify_internal_key(x_internal_key)
    return await document_review.publish(doc_id, course_id, user_id, request)


@router.post('/ingest')
async def ingest_document(req: IngestRequest, x_internal_key: Annotated[str, Header()]):
    verify_internal_key(x_internal_key)
    return await service.ingest_document(req=req)


@router.patch('/documents/{doc_id}/pause')
async def pause_document_endpoint(doc_id: DocumentId, course_id: str, user_id: str, x_internal_key: Annotated[str, Header()]):
    verify_internal_key(x_internal_key)
    return await service.pause_document_endpoint(doc_id=doc_id, course_id=course_id, user_id=user_id)


@router.delete('/documents/{doc_id}')
async def delete_document_endpoint(doc_id: str, course_id: str, user_id: str, x_internal_key: Annotated[str, Header()]):
    verify_internal_key(x_internal_key)
    return await service.delete_document_endpoint(doc_id=doc_id, course_id=course_id, user_id=user_id)


@router.post('/courses/{course_id}/prerequisites')
async def generate_course_prerequisites(course_id: str, background_tasks: BackgroundTasks,
                                       user_id: Annotated[str, Query(description='ID người dùng sở hữu khóa học')],
                                       x_internal_key: Annotated[str, Header()]):
    verify_internal_key(x_internal_key)
    return await service.generate_course_prerequisites(course_id=course_id, user_id=user_id, background_tasks=background_tasks)


@router.post('/documents/{doc_id}/file')
async def upload_document_file(doc_id: DocumentId, file: Annotated[UploadFile, File()], x_internal_key: Annotated[str, Header()]):
    'Nhận file tài liệu từ Java core-service và lưu vào RAW_DATA_DIR/{doc_id}/{filename}.\nPhải được gọi TRƯỚC POST /ingest vì pipeline đọc file từ đĩa local.'
    verify_internal_key(x_internal_key)
    return await service.upload_document_file(doc_id=doc_id, file=file)


@router.get('/documents/{doc_id}/status')
async def get_document_status(doc_id: str, course_id: Annotated[str, Query(description='ID khóa học chứa document')],
                              x_internal_key: Annotated[str, Header()]):
    'Trả trạng thái xử lý của document trong Neo4j — Java poll để đồng bộ sang Postgres.'
    verify_internal_key(x_internal_key)
    return await service.get_document_status(doc_id=doc_id, course_id=course_id)
