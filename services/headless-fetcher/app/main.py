from __future__ import annotations

import asyncio
import ipaddress
import os
import secrets
import socket
from dataclasses import dataclass
from typing import Annotated
from urllib.parse import urlparse

from fastapi import FastAPI, Header, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field, field_validator
from playwright.async_api import TimeoutError as PlaywrightTimeoutError
from playwright.async_api import async_playwright

MAX_RESPONSE_BYTES = 5_000_000
MAX_TIMEOUT_MS = 45_000
MAX_EXTRA_WAIT_MS = 5_000
DEFAULT_TIMEOUT_MS = 30_000
DEFAULT_EXTRA_WAIT_MS = 1_500

app = FastAPI(title="TokenSea Headless Fetcher", docs_url=None, redoc_url=None, openapi_url=None)


class RenderRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    url: str
    allowed_hosts: list[str] = Field(alias="allowedHosts", min_length=1, max_length=20)
    timeout_ms: int = Field(DEFAULT_TIMEOUT_MS, alias="timeoutMs", ge=1_000, le=MAX_TIMEOUT_MS)
    extra_wait_ms: int = Field(DEFAULT_EXTRA_WAIT_MS, alias="extraWaitMs", ge=0, le=MAX_EXTRA_WAIT_MS)
    max_response_bytes: int = Field(MAX_RESPONSE_BYTES, alias="maxResponseBytes", ge=1_024, le=MAX_RESPONSE_BYTES)

    @field_validator("allowed_hosts")
    @classmethod
    def normalize_allowed_hosts(cls, values: list[str]) -> list[str]:
        normalized: list[str] = []
        for raw in values:
            value = raw.strip().lower().rstrip(".")
            if not value or ":" in value or "/" in value or value.startswith(".") or "*" in value:
                raise ValueError("allowedHosts 只能包含不带端口和通配符的官方域名")
            if value not in normalized:
                normalized.append(value)
        if not normalized:
            raise ValueError("allowedHosts 不能为空")
        return normalized


class RenderResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    status_code: int = Field(alias="statusCode")
    final_url: str = Field(alias="finalUrl")
    content_type: str = Field(alias="contentType")
    html: str
    response_bytes: int = Field(alias="responseBytes")
    blocked_hosts: list[str] = Field(alias="blockedHosts")


@dataclass(frozen=True)
class ValidatedTarget:
    url: str
    host: str
    port: int


def _host_allowed(host: str, allowed_hosts: list[str]) -> bool:
    value = host.lower().rstrip(".")
    return any(value == allowed or value.endswith(f".{allowed}") for allowed in allowed_hosts)


def _validate_public_dns(host: str, port: int) -> None:
    try:
        addresses = {
            item[4][0]
            for item in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
            if item and item[4]
        }
    except socket.gaierror as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, f"官方域名无法解析：{host}") from exc
    if not addresses:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, f"官方域名没有可用地址：{host}")
    for raw in addresses:
        try:
            address = ipaddress.ip_address(raw)
        except ValueError as exc:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, f"官方域名返回无效地址：{host}") from exc
        if not address.is_global:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, f"官方域名解析到非公网地址：{host}")


def validate_target(url: str, allowed_hosts: list[str], *, resolve_dns: bool = True) -> ValidatedTarget:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "只允许 HTTP/HTTPS 官方页面")
    if parsed.username or parsed.password:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "官方页面地址不能包含用户信息")
    host = (parsed.hostname or "").lower().rstrip(".")
    if not host or not _host_allowed(host, allowed_hosts):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, f"目标域名不在价格源官方域名白名单：{host or 'unknown'}")
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    if port not in {80, 443}:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "官方页面端口只允许 80 或 443")
    if resolve_dns:
        _validate_public_dns(host, port)
    return ValidatedTarget(url=url, host=host, port=port)


def _require_token(supplied: str | None) -> None:
    expected = os.getenv("TOKENSEA_HEADLESS_FETCHER_TOKEN", "")
    if not expected:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Headless Fetcher 内部认证未配置")
    if supplied is None or not secrets.compare_digest(supplied.encode(), expected.encode()):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Headless Fetcher 内部认证失败")


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "UP"}


@app.post("/render", response_model=RenderResponse, response_model_by_alias=True)
async def render(
    request: RenderRequest,
    x_tokensea_headless_token: Annotated[str | None, Header()] = None,
) -> RenderResponse:
    _require_token(x_tokensea_headless_token)
    validate_target(request.url, request.allowed_hosts)

    blocked_hosts: set[str] = set()
    proxy_server = os.getenv("TOKENSEA_HEADLESS_PROXY_URL", "").strip()
    launch_options: dict[str, object] = {
        "headless": True,
        "chromium_sandbox": False,
        "args": ["--disable-dev-shm-usage"],
    }
    if proxy_server:
        launch_options["proxy"] = {"server": proxy_server}

    try:
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(**launch_options)
            try:
                context = await browser.new_context(
                    service_workers="block",
                    ignore_https_errors=False,
                    user_agent="TokenSea-HeadlessFetcher/1.0",
                )
                page = await context.new_page()

                async def guard(route) -> None:
                    parsed = urlparse(route.request.url)
                    if parsed.scheme in {"about", "blob", "data"}:
                        await route.continue_()
                        return
                    host = (parsed.hostname or "").lower().rstrip(".")
                    if parsed.scheme in {"http", "https"} and host and _host_allowed(host, request.allowed_hosts):
                        await route.continue_()
                        return
                    if host:
                        blocked_hosts.add(host)
                    await route.abort("blockedbyclient")

                await page.route("**/*", guard)
                navigation = await page.goto(
                    request.url,
                    wait_until="domcontentloaded",
                    timeout=request.timeout_ms,
                )
                try:
                    await page.wait_for_load_state("networkidle", timeout=min(10_000, request.timeout_ms))
                except PlaywrightTimeoutError:
                    pass
                if request.extra_wait_ms:
                    await asyncio.sleep(request.extra_wait_ms / 1000)

                final_url = page.url
                validate_target(final_url, request.allowed_hosts)
                html = await page.content()
                encoded = html.encode("utf-8")
                if len(encoded) > request.max_response_bytes:
                    raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "渲染页面超过允许的 5MB 限制")
                status_code = navigation.status if navigation is not None else 200
                if status_code < 200 or status_code >= 400:
                    raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"官方页面渲染返回 HTTP {status_code}")
                content_type = "text/html; charset=utf-8"
                if navigation is not None:
                    content_type = navigation.headers.get("content-type", content_type)
                return RenderResponse(
                    statusCode=status_code,
                    finalUrl=final_url,
                    contentType=content_type,
                    html=html,
                    responseBytes=len(encoded),
                    blockedHosts=sorted(blocked_hosts),
                )
            finally:
                await browser.close()
    except HTTPException:
        raise
    except PlaywrightTimeoutError as exc:
        raise HTTPException(status.HTTP_504_GATEWAY_TIMEOUT, "官方页面渲染超时") from exc
    except Exception as exc:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"官方页面渲染失败：{type(exc).__name__}") from exc
