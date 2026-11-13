import json
import os

import pytest

from app.config import settings
from app.core.identifiers import IdentifierValidationError
from app.db import draft_store


@pytest.fixture
def storage(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "RAW_DATA_DIR", str(tmp_path / "raw"))
    return tmp_path / "raw" / ".graph-drafts"


@pytest.mark.parametrize("doc_id", ["../outside", "101/../../outside", r"..\outside",
                                    r"C:\outside", "/outside", "101:payload", "00101", "0"])
def test_untrusted_document_ids_cannot_create_files(storage, doc_id):
    with pytest.raises(IdentifierValidationError):
        draft_store.save({"document_id": doc_id, "status": "PROCESSING"})
    assert not storage.exists()


@pytest.mark.parametrize("stored_id", ["../outside", "102"])
def test_file_content_cannot_redirect_recovery_to_another_document(storage, stored_id):
    storage.mkdir(parents=True)
    metadata = storage / "101.json"
    metadata.write_text(json.dumps({"document_id": stored_id, "status": "PROCESSING"}), encoding="utf-8")
    other = storage / "102.json"
    existing = json.dumps({"document_id": "102", "status": "AWAITING_REVIEW"})
    other.write_text(existing, encoding="utf-8")
    with pytest.raises(IdentifierValidationError, match="does not match"):
        draft_store.load("101")
    with pytest.raises(IdentifierValidationError, match="does not match"):
        draft_store.recover()
    assert other.read_text(encoding="utf-8") == existing


def symlink_or_skip(link, destination, *, directory=False):
    try:
        link.symlink_to(destination, target_is_directory=directory)
    except OSError:
        pytest.skip("Symbolic links are unavailable for this Windows account")


@pytest.mark.parametrize("suffix", [".json", ".payload"])
def test_draft_writes_cannot_follow_file_symlinks(storage, tmp_path, suffix):
    storage.mkdir(parents=True)
    outside = tmp_path / "outside.txt"
    outside.write_text("preserve me", encoding="utf-8")
    symlink_or_skip(storage / f"101{suffix}", outside)
    with pytest.raises(ValueError, match="escapes"):
        draft_store.save({"document_id": "101", "status": "AWAITING_REVIEW", "payload": {"chunks": []}})
    assert outside.read_text(encoding="utf-8") == "preserve me"


def test_draft_directory_cannot_redirect_storage_outside_raw_root(storage, tmp_path):
    storage.parent.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    symlink_or_skip(storage, outside, directory=True)
    with pytest.raises(ValueError, match="directory"):
        draft_store.save({"document_id": "101", "status": "PROCESSING"})
    assert list(outside.iterdir()) == []


@pytest.mark.skipif(os.name != "nt", reason="Windows junction regression")
def test_windows_junction_cannot_redirect_writes_or_recovery(storage, tmp_path):
    import _winapi
    storage.parent.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    _winapi.CreateJunction(str(outside), str(storage))
    with pytest.raises(ValueError, match="junction"):
        draft_store.save({"document_id": "101", "status": "PROCESSING"})
    with pytest.raises(ValueError, match="junction"):
        draft_store.recover()
    assert list(outside.iterdir()) == []


def test_predictable_temporary_symlink_is_never_opened(storage, tmp_path):
    storage.mkdir(parents=True)
    outside = tmp_path / "outside.txt"
    outside.write_text("preserve me", encoding="utf-8")
    symlink_or_skip(storage / "101.tmp", outside)
    draft_store.save({"document_id": "101", "status": "PROCESSING"})
    assert draft_store.load("101")["status"] == "PROCESSING"
    assert outside.read_text(encoding="utf-8") == "preserve me"


def test_failed_atomic_replace_keeps_old_draft_and_removes_temporary_file(storage, monkeypatch):
    draft_store.save({"document_id": "101", "status": "FAILED"})
    def fail_replace(_source, _destination):
        raise OSError("replacement failed")
    monkeypatch.setattr(draft_store.os, "replace", fail_replace)
    with pytest.raises(OSError, match="replacement failed"):
        draft_store.save({"document_id": "101", "status": "DONE"})
    assert draft_store.load("101")["status"] == "FAILED"
    assert list(storage.glob("*.tmp")) == []


def test_metadata_polling_does_not_read_payload_and_terminal_state_removes_it(storage):
    draft_store.save({"document_id": "101", "status": "AWAITING_REVIEW", "payload": {"chunks": ["private"]}})
    payload_path = storage / "101.payload"
    assert payload_path.exists()
    assert "payload" not in draft_store.load("101", include_payload=False)
    assert draft_store.load("101")["payload"] == {"chunks": ["private"]}
    draft_store.save({"document_id": "101", "status": "DONE", "payload": None})
    assert not payload_path.exists()
