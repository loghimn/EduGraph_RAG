"""Exact-response cache. Authorization and evidence retrieval always run before lookup."""
import hashlib
import json
import logging
import sqlite3
from app.config import settings
from app.core.cache import cache

logger = logging.getLogger(__name__)


def response_key(prompt, chunks, scope):
    if not scope or settings.CHAT_ANSWER_CACHE_TTL_SECONDS == 0:
        return None
    payload = {"prompt": prompt, "chunks": chunks, "scope": sorted(scope),
               "models": [settings.MODEL_MAIN, settings.MODEL_FALLBACK]}
    serialized = json.dumps(payload, sort_keys=True, ensure_ascii=False, default=str)
    return "chat-answer-v1:" + hashlib.sha256(serialized.encode()).hexdigest()


def read_answer(key):
    if key is None:
        return None
    try:
        value = cache.get(key)
        return value if isinstance(value, str) else None
    except (OSError, sqlite3.Error):
        logger.warning("Answer cache unavailable; generating from current evidence")
        return None


def save_answer(key, answer):
    if key and answer.strip() and len(answer) <= 100_000:
        try:
            cache.set(key, answer, expire=settings.CHAT_ANSWER_CACHE_TTL_SECONDS)
        except (OSError, sqlite3.Error):
            logger.warning("Answer cache write skipped")
