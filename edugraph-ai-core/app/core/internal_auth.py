import hmac

from fastapi import HTTPException

from app.config import settings


def verify_internal_key(x_internal_key: str) -> str:
    configured_key = settings.INTERNAL_API_KEY
    if not configured_key or not hmac.compare_digest(
        x_internal_key.encode("utf-8"), configured_key.encode("utf-8")
    ):
        raise HTTPException(status_code=403, detail="Invalid Internal Key")
    return x_internal_key
