import os

import pytest
from fastapi import HTTPException

from app.main import _host_allowed, _require_token, validate_target


def test_exact_and_subdomain_are_allowed():
    assert _host_allowed("platform.kimi.com", ["platform.kimi.com"])
    assert _host_allowed("assets.platform.kimi.com", ["platform.kimi.com"])
    assert not _host_allowed("platform.kimi.com.evil.example", ["platform.kimi.com"])


def test_target_must_match_official_host():
    target = validate_target(
        "https://platform.kimi.com/docs/pricing/chat-k26",
        ["platform.kimi.com"],
        resolve_dns=False,
    )
    assert target.host == "platform.kimi.com"
    with pytest.raises(HTTPException) as exc:
        validate_target("https://example.com/", ["platform.kimi.com"], resolve_dns=False)
    assert exc.value.status_code == 400


def test_target_rejects_credentials_and_nonstandard_port():
    with pytest.raises(HTTPException):
        validate_target("https://user:pass@platform.kimi.com/", ["platform.kimi.com"], resolve_dns=False)
    with pytest.raises(HTTPException):
        validate_target("https://platform.kimi.com:8443/", ["platform.kimi.com"], resolve_dns=False)


def test_internal_token_is_required(monkeypatch):
    monkeypatch.setenv("TOKENSEA_HEADLESS_FETCHER_TOKEN", "expected-token")
    _require_token("expected-token")
    with pytest.raises(HTTPException) as exc:
        _require_token("wrong-token")
    assert exc.value.status_code == 401
    monkeypatch.delenv("TOKENSEA_HEADLESS_FETCHER_TOKEN")
    with pytest.raises(HTTPException) as exc:
        _require_token("expected-token")
    assert exc.value.status_code == 503
