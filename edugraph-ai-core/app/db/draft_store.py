"""Durable private ingestion workspace, on the same persistent volume as raw files.

Drafts are deliberately outside Neo4j: retrieval, graph and learning paths cannot
accidentally consume unapproved chunks. The service uses the document task lock
and a single worker, as does the existing ingestion task registry.
"""
import json
import os
import tempfile
from pathlib import Path
from app.config import settings
from app.core.identifiers import validate_document_id
from app.core.identifiers import IdentifierValidationError

METADATA_SUFFIX = ".json"
PAYLOAD_SUFFIX = ".payload"


def _directory():
    raw_root = Path(settings.RAW_DATA_DIR).resolve()
    folder = raw_root / ".graph-drafts"
    root = folder.resolve()
    if folder.is_symlink() or root != folder:
        raise ValueError("Draft directory must not be a symbolic link or junction")
    return root


def _path(doc_id, suffix=METADATA_SUFFIX):
    # Reconstruct the filename from a number, never from file-content text.
    document_number = int(validate_document_id(doc_id))
    if suffix not in {METADATA_SUFFIX, PAYLOAD_SUFFIX}:
        raise ValueError("Unsupported draft file type")
    root = _directory()
    candidate = root / f"{document_number:d}{suffix}"
    if candidate.is_symlink() or candidate.resolve().parent != root:
        raise ValueError("Draft path escapes its storage directory")
    return candidate


def load(doc_id, *, include_payload=True):
    try:
        path = _path(doc_id)
    except IdentifierValidationError:
        # Older graph-only documents may use non-numeric IDs. Never use these
        # as filesystem paths; let the existing graph lookup handle them.
        return None
    if not path.exists():
        return None
    draft = json.loads(path.read_text(encoding="utf-8"))
    if draft.get("document_id") != doc_id:
        raise IdentifierValidationError("Stored document_id does not match the draft filename")
    # Carry the validated lookup identity forward instead of the JSON field.
    draft["document_id"] = str(int(doc_id))
    if include_payload and draft.get("payload_stored"):
        draft["payload"] = json.loads(_path(doc_id, PAYLOAD_SUFFIX).read_text(encoding="utf-8"))
    return draft


def save(draft):
    doc_id = draft["document_id"]
    path = _path(doc_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    metadata = dict(draft)
    if metadata.get("payload") is not None:
        _atomic_save(doc_id, metadata.pop("payload"), PAYLOAD_SUFFIX)
        metadata["payload_stored"] = True
    elif "payload" in metadata:
        metadata["payload_stored"] = False
    _atomic_save(doc_id, metadata)
    if metadata.get("payload_stored") is False:
        _path(doc_id, PAYLOAD_SUFFIX).unlink(missing_ok=True)


def _atomic_save(doc_id, value, suffix=METADATA_SUFFIX):
    path = _path(doc_id, suffix)
    # Exclusive random temporary files cannot follow a planted <id>.tmp link.
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix="draft-", suffix=".tmp", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(value, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, _path(doc_id, suffix))
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def recover():
    folder = _directory()
    for path in folder.glob("*.json"):
        draft = load(path.stem, include_payload=False)
        if draft["status"] == "PROCESSING":
            draft.update(status="FAILED", error="Processing interrupted; retry extraction")
            save(draft)
