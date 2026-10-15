import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch, MagicMock, AsyncMock

from app.main import app
client = TestClient(app)

def test_ingest_auth_403():
    response = client.post("/ingest", headers={"X-Internal-Key": "wrong"}, json={"document_id": "1", "course_id": "2", "user_id": "3"})
    assert response.status_code == 403

@patch("app.api.ingest._background_ingest", new=AsyncMock())
@patch("app.api.ingest.neo4j_client")
def test_ingest_merges_nodes_200(mock_neo4j):
    # /ingest giờ MERGE (upsert) Course + Document thay vì 404 khi node chưa tồn tại
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session

    mock_rec_course = AsyncMock()
    mock_rec_course.single.return_value = {"owner_id": "user1"}

    mock_rec_doc = AsyncMock()
    mock_rec_doc.single.return_value = {"status": "UPLOADED"}

    mock_session.run.side_effect = [mock_rec_course, mock_rec_doc]

    response = client.post("/ingest", headers={"X-Internal-Key": "test-internal-api-key"}, json={"document_id": "doc1", "course_id": "c1", "user_id": "user1"})
    assert response.status_code == 200
    assert response.json()["status"] == "processing"
    # Query Course phải là MERGE (tạo node nếu chưa có)
    course_query = mock_session.run.call_args_list[0][0][0]
    assert "MERGE (c:Course" in course_query
    # Query Document phải là MERGE (tạo node nếu chưa có)
    doc_query = mock_session.run.call_args_list[1][0][0]
    assert "MERGE (d:Document" in doc_query


@patch("app.api.ingest._background_ingest", new=AsyncMock())
@patch("app.api.ingest.neo4j_client")
def test_ingest_owner_mismatch_403(mock_neo4j):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session

    mock_rec_course = AsyncMock()
    mock_rec_course.single.return_value = {"owner_id": "real_owner"}
    mock_session.run.return_value = mock_rec_course

    response = client.post("/ingest", headers={"X-Internal-Key": "test-internal-api-key"}, json={"document_id": "doc1", "course_id": "c1", "user_id": "imposter"})
    assert response.status_code == 403
    assert "Permission denied" in response.json()["detail"]


def test_upload_file_auth_403():
    response = client.post("/documents/doc1/file", headers={"X-Internal-Key": "wrong"}, files={"file": ("a.pdf", b"123", "application/pdf")})
    assert response.status_code == 403


def test_upload_file_stores_raw(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "RAW_DATA_DIR", str(tmp_path))

    response = client.post(
        "/documents/doc-upload-1/file",
        headers={"X-Internal-Key": "test-internal-api-key"},
        files={"file": ("bai1.pdf", b"%PDF-1.4 fake", "application/pdf")}
    )
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "stored"
    assert body["filename"] == "bai1.pdf"
    stored = tmp_path / "doc-upload-1" / "bai1.pdf"
    assert stored.exists()
    assert stored.read_bytes() == b"%PDF-1.4 fake"


def test_upload_file_accepts_pptx(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "RAW_DATA_DIR", str(tmp_path))

    response = client.post(
        "/documents/doc-upload-pptx/file",
        headers={"X-Internal-Key": "test-internal-api-key"},
        files={"file": ("slides.pptx", b"PK fake pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")}
    )
    assert response.status_code == 200
    assert response.json()["filename"] == "slides.pptx"
    stored = tmp_path / "doc-upload-pptx" / "slides.pptx"
    assert stored.exists()


def test_upload_file_rejects_bad_extension(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "RAW_DATA_DIR", str(tmp_path))

    response = client.post(
        "/documents/doc-upload-2/file",
        headers={"X-Internal-Key": "test-internal-api-key"},
        files={"file": ("malware.exe", b"MZ", "application/octet-stream")}
    )
    assert response.status_code == 400


def test_upload_file_rejects_empty(tmp_path, monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "RAW_DATA_DIR", str(tmp_path))

    response = client.post(
        "/documents/doc-upload-3/file",
        headers={"X-Internal-Key": "test-internal-api-key"},
        files={"file": ("empty.txt", b"", "text/plain")}
    )
    assert response.status_code == 400


def test_document_status_auth_403():
    response = client.get("/documents/doc1/status?course_id=c1", headers={"X-Internal-Key": "wrong"})
    assert response.status_code == 403


@patch("app.api.ingest.neo4j_client")
def test_document_status_200(mock_neo4j):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session

    mock_rec = AsyncMock()
    mock_rec.single.return_value = {"status": "DONE", "progress": None, "error": None}
    mock_session.run.return_value = mock_rec

    response = client.get("/documents/doc1/status?course_id=c1", headers={"X-Internal-Key": "test-internal-api-key"})
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "DONE"
    assert body["document_id"] == "doc1"
    assert body["course_id"] == "c1"


@patch("app.api.ingest.neo4j_client")
def test_document_status_404(mock_neo4j):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session

    mock_rec = AsyncMock()
    mock_rec.single.return_value = None
    mock_session.run.return_value = mock_rec

    response = client.get("/documents/missing/status?course_id=c1", headers={"X-Internal-Key": "test-internal-api-key"})
    assert response.status_code == 404

@patch("app.api.ingest.neo4j_client")
def test_delete_processing_409(mock_neo4j):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session
    
    mock_rec_course = AsyncMock()
    mock_rec_course.single.return_value = {"owner_id": "user1"}
    
    mock_rec_doc = AsyncMock()
    mock_rec_doc.single.return_value = {"status": "PROCESSING"}
    
    mock_session.run.side_effect = [mock_rec_course, mock_rec_doc]
    
    response = client.delete("/documents/doc-processing?course_id=c1&user_id=user1", headers={"X-Internal-Key": "test-internal-api-key"})
    assert response.status_code == 409

@pytest.mark.asyncio
@patch("app.api.ingest.neo4j_client")
async def test_recover_processing_on_startup(mock_neo4j):
    from app.api.ingest import recover_processing_documents
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session
    
    await recover_processing_documents()
    # verify that SET d.status = 'FAILED' query was executed
    assert mock_session.run.call_count == 1
    query_called = mock_session.run.call_args[0][0]
    assert "SET d.status = 'FAILED'" in query_called
    assert "error = 'interrupted by restart'" in query_called
    # Option A (single-worker): no age filter — every orphan must fail.
    assert "updated_at >" not in query_called
    assert "timeout" not in mock_session.run.call_args.kwargs

def test_prerequisites_auth_403():
    response = client.post("/courses/c1/prerequisites?user_id=u1", headers={"X-Internal-Key": "wrong"})
    assert response.status_code == 403

@patch("app.api.ingest.neo4j_client")
def test_prerequisites_course_404(mock_neo4j):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session
    mock_rec = AsyncMock()
    mock_rec.single.return_value = None
    mock_session.run.return_value = mock_rec

    response = client.post("/courses/c_none/prerequisites?user_id=u1", headers={"X-Internal-Key": "test-internal-api-key"})
    assert response.status_code == 404

@patch("app.api.ingest.neo4j_client")
def test_prerequisites_owner_403(mock_neo4j):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session
    mock_rec = AsyncMock()
    mock_rec.single.return_value = {"owner_id": "real_owner"}
    mock_session.run.return_value = mock_rec

    response = client.post("/courses/c1/prerequisites?user_id=imposter", headers={"X-Internal-Key": "test-internal-api-key"})
    assert response.status_code == 403
    assert "Permission denied" in response.json()["detail"]

@patch("app.api.ingest.run_prerequisite_pipeline")
@patch("app.api.ingest.neo4j_client")
def test_prerequisites_success_200(mock_neo4j, mock_run_prereq):
    mock_session = AsyncMock()
    mock_neo4j.driver.session.return_value.__aenter__.return_value = mock_session
    mock_rec = AsyncMock()
    mock_rec.single.return_value = {"owner_id": "real_owner"}
    mock_session.run.return_value = mock_rec

    response = client.post("/courses/c1/prerequisites?user_id=real_owner", headers={"X-Internal-Key": "test-internal-api-key"})
    assert response.status_code == 200
    assert response.json()["status"] == "processing prerequisites"



