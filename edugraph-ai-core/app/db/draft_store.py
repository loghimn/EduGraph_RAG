"""Durable private ingestion workspace, on the same persistent volume as raw files.

Drafts are deliberately outside Neo4j: retrieval, graph and learning paths cannot
accidentally consume unapproved chunks. The service uses the document task lock
and a single worker, as does the existing ingestion task registry.
"""
import json
import os
from pathlib import Path
from app.config import settings
from app.core.identifiers import validate_document_id
from app.core.identifiers import IdentifierValidationError


def _path(doc_id):
    validate_document_id(doc_id)
    return Path(settings.RAW_DATA_DIR) / ".graph-drafts" / f"{doc_id}.json"


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
    if include_payload and draft.get("payload_stored"):
        draft["payload"] = json.loads(path.with_suffix(".payload").read_text(encoding="utf-8"))
    return draft


def save(draft):
    path = _path(draft["document_id"])
    path.parent.mkdir(parents=True, exist_ok=True)
    metadata = dict(draft)
    if metadata.get("payload") is not None:
        _atomic_save(path.with_suffix(".payload"), metadata.pop("payload"))
        metadata["payload_stored"] = True
    elif "payload" in metadata:
        metadata["payload_stored"] = False
    _atomic_save(path, metadata)
    if metadata.get("payload_stored") is False:
        path.with_suffix(".payload").unlink(missing_ok=True)


def _atomic_save(path, value):
    temporary = path.with_suffix(".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def recover():
    folder = Path(settings.RAW_DATA_DIR) / ".graph-drafts"
    for path in folder.glob("*.json"):
        draft = load(path.stem, include_payload=False)
        if draft["status"] == "PROCESSING":
            draft.update(status="FAILED", error="Processing interrupted; retry extraction")
            save(draft)
