import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.api.internal_auth import verify_internal_key
from app.config import Settings, settings


def test_accepts_configured_internal_key():
    assert verify_internal_key(settings.INTERNAL_API_KEY) == settings.INTERNAL_API_KEY


@pytest.mark.parametrize("key", ["correct", "dummy_key"])
def test_rejects_previous_hard_coded_internal_keys(key):
    with pytest.raises(HTTPException) as exc_info:
        verify_internal_key(key)

    assert exc_info.value.status_code == 403


def test_internal_api_key_must_be_configured_and_nonempty(monkeypatch):
    monkeypatch.delenv("INTERNAL_API_KEY", raising=False)

    with pytest.raises(ValidationError):
        Settings(_env_file=None)
    with pytest.raises(ValidationError):
        Settings(_env_file=None, INTERNAL_API_KEY="")
