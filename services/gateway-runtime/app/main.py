import asyncio
import base64
import hashlib
import ipaddress
import json
import logging
import os
import random
import re
import socket
import threading
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_CEILING
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

import asyncpg
import httpx
import redis.asyncio as redis
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, PlainTextResponse, StreamingResponse
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest

DB_DSN = os.getenv("TOKENSEA_DB_DSN")
DB_HOST = os.getenv("TOKENSEA_DB_HOST")
DB_PORT = int(os.getenv("TOKENSEA_DB_PORT", "5432"))
DB_NAME = os.getenv("TOKENSEA_DB_NAME")
DB_USER = os.getenv("TOKENSEA_DB_USER")
DB_PASSWORD = os.getenv("TOKENSEA_DB_PASSWORD")
REDIS_URL = os.getenv("TOKENSEA_REDIS_URL", "redis://localhost:39214/0")
REDIS_PASSWORD = os.getenv("TOKENSEA_REDIS_PASSWORD")
ENGINE_URL = os.getenv("TOKENSEA_RUNTIME_ENGINE_URL", "http://localhost:39218").rstrip("/")
ENGINE_KEY = os.getenv("TOKENSEA_RUNTIME_ENGINE_KEY")
CRYPTO_KEY = os.getenv("TOKENSEA_CRYPTO_KEY")
BUDGET_CURRENCY = os.getenv("TOKENSEA_BUDGET_CURRENCY", "CNY").strip().upper()
if BUDGET_CURRENCY != "CNY":
    raise RuntimeError("TOKENSEA_BUDGET_CURRENCY 必须配置为 CNY")
REGISTRATION_TTL = max(5, int(os.getenv("TOKENSEA_REGISTRATION_TTL_SECONDS", "60")))
DEFAULT_OUTPUT_RESERVATION = max(1, int(os.getenv("TOKENSEA_DEFAULT_OUTPUT_RESERVATION_TOKENS", "1024")))
TRUSTED_PROXY_CIDRS = os.getenv("TOKENSEA_TRUSTED_PROXY_CIDRS", "")
RETRYABLE_STATUS = {408, 425, 429, 500, 502, 503, 504}
ACTIVE_VALUES = {"ACTIVE", "启用", "已启用"}
REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
DNS_RECHECK_TTL = max(30, int(os.getenv("TOKENSEA_DNS_RECHECK_TTL_SECONDS", "300")))
CONNECTION_TEST_MAX_AGE_SECONDS = max(
    3600, int(os.getenv("TOKENSEA_CONNECTION_TEST_MAX_AGE_SECONDS", "604800"))
)
LOCAL_TEST_UPSTREAM_ENABLED = os.getenv("TOKENSEA_LOCAL_TEST_UPSTREAM_ENABLED", "false").strip().lower() in {"1", "true", "yes", "on"}
LOCAL_TEST_UPSTREAM_HOSTS = {
    value.strip().lower().rstrip(".")
    for value in os.getenv("TOKENSEA_LOCAL_TEST_UPSTREAM_HOSTS", "").split(",")
    if value.strip()
}
WAL_DIR = Path(os.getenv("TOKENSEA_OUTBOX_DIR", "/var/lib/tokensea-gateway/outbox"))
WAL_MAX_BYTES = max(1_048_576, min(int(os.getenv("TOKENSEA_OUTBOX_MAX_BYTES", "67108864")), 1_073_741_824))
WAL_FILE_NAME = "gateway-outbox.wal"
WAL_DEAD_FILE_NAME = "gateway-outbox.dead.wal"
OUTBOX_MAX_ATTEMPTS = max(1, int(os.getenv("TOKENSEA_OUTBOX_MAX_ATTEMPTS", "12")))
INTENT_RECOVERY_SECONDS = max(300, int(os.getenv("TOKENSEA_INTENT_RECOVERY_SECONDS", "86400")))
def configured_cors_origins() -> list[str]:
    raw = (
        os.getenv("TOKENSEA_CORS_ORIGINS")
        or os.getenv("TOKENSEA_CORS_ALLOWED_ORIGINS")
        or "http://localhost:39210,http://127.0.0.1:39210"
    )
    return [origin.strip().rstrip("/") for origin in raw.split(",") if origin.strip()]


CORS_ORIGINS = configured_cors_origins()
CORS_ORIGIN_REGEX = os.getenv(
    "TOKENSEA_CORS_ORIGIN_REGEX",
    r"^https?://(?:(?:localhost|127\.0\.0\.1|\[::1\])|(?:10(?:\.\d{1,3}){3})|(?:192\.168(?:\.\d{1,3}){2})|(?:172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2}))(?::39210)?$",
).strip()

REQUESTS = Counter("tokensea_gateway_requests_total", "Gateway requests", ["endpoint", "status"])
LATENCY = Histogram("tokensea_gateway_latency_seconds", "Gateway latency", ["endpoint"])

pool: Optional[asyncpg.Pool] = None
cache: Optional[redis.Redis] = None
runtime_models: Dict[str, Dict[str, Any]] = {}
runtime_model_lock = asyncio.Lock()
outbox_task: Optional[asyncio.Task] = None
wal_lock = threading.Lock()
wal_pending: Dict[str, Dict[str, Any]] = {}
dns_validation_cache: Dict[str, Dict[str, Any]] = {}
LOGGER = logging.getLogger("tokensea.gateway")

RATE_LIMIT_LUA = """
for i=1,#KEYS do
  local limit=tonumber(ARGV[(i-1)*3+1])
  local amount=tonumber(ARGV[(i-1)*3+2])
  local ttl=tonumber(ARGV[(i-1)*3+3])
  if limit > 0 and amount > 0 then
    local current=tonumber(redis.call('GET',KEYS[i]) or '0')
    if current + amount > limit then return i end
  end
end
for i=1,#KEYS do
  local limit=tonumber(ARGV[(i-1)*3+1])
  local amount=tonumber(ARGV[(i-1)*3+2])
  local ttl=tonumber(ARGV[(i-1)*3+3])
  if limit > 0 and amount > 0 then
    redis.call('INCRBY',KEYS[i],amount)
    redis.call('EXPIRE',KEYS[i],ttl)
  end
end
return 0
"""

BUDGET_RESERVE_LUA = """
if redis.call('EXISTS',KEYS[1]) == 1 then
  if redis.call('HGET',KEYS[1],'token') == ARGV[3] then return -1 end
  return -2
end
local amount=tonumber(ARGV[1])
local ttl=tonumber(ARGV[2])
for i=2,#KEYS do
  local base=tonumber(ARGV[4+(i-2)*2])
  local limit=tonumber(ARGV[5+(i-2)*2])
  redis.call('SET',KEYS[i],base,'EX',ttl,'NX')
  local current=tonumber(redis.call('GET',KEYS[i]) or '0')
  if limit <= 0 or current+amount > limit then return i-1 end
end
for i=2,#KEYS do redis.call('INCRBY',KEYS[i],amount) end
redis.call('HSET',KEYS[1],'token',ARGV[3],'status','RESERVED','reserved',amount,'key_count',#KEYS-1,'updated_at',ARGV[#ARGV])
redis.call('EXPIRE',KEYS[1],ttl)
return 0
"""

BUDGET_RELEASE_LUA = """
local state=redis.call('HGET',KEYS[1],'status')
if state == 'RELEASED' or state == 'SETTLED' or state == 'OVERRUN' then return 0 end
if state ~= 'RESERVED' then return -1 end
local amount=tonumber(redis.call('HGET',KEYS[1],'reserved') or '0')
for i=2,#KEYS do
  local current=tonumber(redis.call('GET',KEYS[i]) or '0')
  redis.call('SET',KEYS[i],math.max(0,current-amount),'KEEPTTL')
end
redis.call('HSET',KEYS[1],'status','RELEASED','updated_at',ARGV[1])
return 1
"""

BUDGET_SETTLE_LUA = """
local state=redis.call('HGET',KEYS[1],'status')
if state == 'SETTLED' then return 0 end
if state ~= 'RESERVED' then return -1 end
local reserved=tonumber(redis.call('HGET',KEYS[1],'reserved') or '0')
local actual=tonumber(ARGV[1])
local delta=actual-reserved
local overrun=0
if delta > 0 then
  for i=2,#KEYS do
    local limit=tonumber(ARGV[i])
    local current=tonumber(redis.call('GET',KEYS[i]) or '0')
    if current+delta > limit then
      overrun=1
    end
  end
end
for i=2,#KEYS do
  local current=tonumber(redis.call('GET',KEYS[i]) or '0')
  redis.call('SET',KEYS[i],math.max(0,current+delta),'KEEPTTL')
end
local final_state='SETTLED'
if overrun == 1 then final_state='OVERRUN' end
redis.call('HSET',KEYS[1],'status',final_state,'actual',actual,'updated_at',ARGV[#ARGV])
return overrun
"""


def wal_path() -> Path:
    return WAL_DIR / WAL_FILE_NAME


def initialize_wal():
    global wal_pending
    WAL_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(WAL_DIR, 0o700)
    path = wal_path()
    path.touch(mode=0o600, exist_ok=True)
    os.chmod(path, 0o600)
    pending: Dict[str, Dict[str, Any]] = {}
    with path.open("r", encoding="utf-8") as source:
        for number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
                event_id = record["id"]
                if record["op"] == "put":
                    pending[event_id] = record["event"]
                elif record["op"] == "ack":
                    pending.pop(event_id, None)
                else:
                    raise ValueError("unknown WAL operation")
            except Exception as exc:
                raise RuntimeError(f"gateway outbox WAL is corrupt at line {number}") from exc
    wal_pending = pending
    if path.stat().st_size > WAL_MAX_BYTES:
        compact_wal_locked()


def compact_wal_locked():
    path = wal_path()
    temporary = path.with_suffix(".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as output:
        os.chmod(temporary, 0o600)
        for event_id, event in sorted(wal_pending.items()):
            output.write(json.dumps({"op": "put", "id": event_id, "event": event}, ensure_ascii=False,
                                    separators=(",", ":")) + "\n")
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)
    os.chmod(path, 0o600)
    if os.name != "nt":
        directory_fd = os.open(WAL_DIR, os.O_DIRECTORY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    if path.stat().st_size > WAL_MAX_BYTES:
        raise RuntimeError("gateway outbox WAL capacity exceeded")


def append_wal_record(record: Dict[str, Any]):
    encoded = (json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
    with wal_lock:
        path = wal_path()
        if path.stat().st_size + len(encoded) > WAL_MAX_BYTES:
            compact_wal_locked()
        if path.stat().st_size + len(encoded) > WAL_MAX_BYTES:
            raise RuntimeError("gateway outbox WAL capacity exceeded")
        with path.open("ab", buffering=0) as output:
            output.write(encoded)
            os.fsync(output.fileno())


def wal_put(kind: str, payload: Dict[str, Any], event_id: Optional[str] = None,
            available_at: Optional[float] = None) -> str:
    stable = json.dumps({"kind": kind, "payload": payload}, ensure_ascii=False, sort_keys=True,
                        separators=(",", ":"))
    event_id = event_id or hashlib.sha256(stable.encode()).hexdigest()
    with wal_lock:
        if event_id in wal_pending:
            return event_id
    event = {"kind": kind, "payload": payload, "created_at": utc_timestamp(), "attempts": 0,
             "available_at": available_at or time.time()}
    append_wal_record({"op": "put", "id": event_id, "event": event})
    with wal_lock:
        wal_pending[event_id] = event
    return event_id


def wal_replace(event_id: str, event: Dict[str, Any]):
    append_wal_record({"op": "put", "id": event_id, "event": event})
    with wal_lock:
        wal_pending[event_id] = event


def wal_dead_letter(event_id: str, event: Dict[str, Any]):
    path = WAL_DIR / WAL_DEAD_FILE_NAME
    encoded = (json.dumps({"id": event_id, "event": event}, ensure_ascii=False,
                          separators=(",", ":")) + "\n").encode("utf-8")
    with path.open("ab", buffering=0) as output:
        os.chmod(path, 0o600)
        output.write(encoded)
        os.fsync(output.fileno())
    wal_ack(event_id)


def wal_ack(event_id: str):
    with wal_lock:
        if event_id not in wal_pending:
            return
    append_wal_record({"op": "ack", "id": event_id})
    with wal_lock:
        wal_pending.pop(event_id, None)
        if wal_path().stat().st_size > WAL_MAX_BYTES // 2:
            compact_wal_locked()


def database_pool_kwargs() -> Dict[str, Any]:
    if DB_DSN:
        return {"dsn": DB_DSN, "min_size": 1, "max_size": 20}
    missing = [name for name, value in (("TOKENSEA_DB_HOST", DB_HOST), ("TOKENSEA_DB_NAME", DB_NAME),
                                        ("TOKENSEA_DB_USER", DB_USER), ("TOKENSEA_DB_PASSWORD", DB_PASSWORD)) if not value]
    if missing:
        raise RuntimeError("database connection settings are incomplete: " + ",".join(missing))
    return {"host": DB_HOST, "port": DB_PORT, "database": DB_NAME, "user": DB_USER,
            "password": DB_PASSWORD, "min_size": 1, "max_size": 20}


@asynccontextmanager
async def lifespan(_: FastAPI):
    global pool, cache, outbox_task
    await asyncio.to_thread(initialize_wal)
    pool = await asyncpg.create_pool(**database_pool_kwargs())
    if not REDIS_PASSWORD:
        await pool.close()
        pool = None
        raise RuntimeError("TOKENSEA_REDIS_PASSWORD is required")
    cache = redis.from_url(REDIS_URL, password=REDIS_PASSWORD, decode_responses=True)
    try:
        if not await cache.ping():
            raise RuntimeError("authenticated Redis connection failed")
    except Exception:
        await cache.aclose()
        cache = None
        await pool.close()
        pool = None
        raise
    await activate_recovered_request_intents()
    outbox_task = asyncio.create_task(outbox_worker())
    try:
        yield
    finally:
        if outbox_task:
            outbox_task.cancel()
            try:
                await outbox_task
            except asyncio.CancelledError:
                pass
        if pool:
            await pool.close()
        if cache:
            await cache.aclose()


app = FastAPI(title="TokenSea Gateway Runtime", version="0.3.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=CORS_ORIGINS,
    allow_origin_regex=CORS_ORIGIN_REGEX or None,
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
    expose_headers=["X-Request-ID"],
)


@app.get("/health")
async def health():
    return {"status": "ok", "service": "tokensea-gateway-runtime"}


@app.get("/health/readiness")
async def readiness():
    try:
        database_pool_kwargs()
        database_missing = False
    except RuntimeError:
        database_missing = True
    missing = (["database"] if database_missing else []) + [name for name, value in
        (("runtimeKey", ENGINE_KEY), ("cryptoKey", CRYPTO_KEY), ("redisPassword", REDIS_PASSWORD)) if not value]
    if pool is None:
        missing.append("databasePool")
    if cache is None:
        missing.append("redisClient")
    if missing:
        return JSONResponse({"status": "not_ready", "failed": "configuration", "missing": sorted(set(missing))}, status_code=503)
    try:
        await pool.fetchval("SELECT 1")
    except Exception as exc:
        LOGGER.exception("gateway_readiness_failed dependency=database")
        return JSONResponse({"status": "not_ready", "failed": "database", "errorType": type(exc).__name__}, status_code=503)
    try:
        if not await cache.ping():
            raise RuntimeError("redis ping returned false")
    except Exception as exc:
        LOGGER.exception("gateway_readiness_failed dependency=redis")
        return JSONResponse({"status": "not_ready", "failed": "redis", "errorType": type(exc).__name__}, status_code=503)
    try:
        async with httpx.AsyncClient(timeout=5, trust_env=False) as client:
            response = await client.get(f"{ENGINE_URL}/health/liveliness")
        if response.status_code >= 300:
            raise RuntimeError(f"runtime status {response.status_code}")
    except Exception as exc:
        LOGGER.exception("gateway_readiness_failed dependency=runtime-core url=%s", ENGINE_URL)
        return JSONResponse({"status": "not_ready", "failed": "runtime-core", "errorType": type(exc).__name__}, status_code=503)
    return {"status": "ready", "service": "tokensea-gateway-runtime"}


@app.get("/metrics")
async def metrics():
    return PlainTextResponse(generate_latest().decode(), media_type=CONTENT_TYPE_LATEST)


@app.post("/v1/chat/completions")
async def chat_completions(request: Request):
    return await proxy_openai_compatible(request, "/v1/chat/completions")


@app.post("/v1/embeddings")
async def embeddings(request: Request):
    return await proxy_openai_compatible(request, "/v1/embeddings")


@app.post("/v1/responses")
async def responses(request: Request):
    return await proxy_openai_compatible(request, "/v1/responses")


@app.get("/v1/models")
async def models(request: Request):
    require_runtime_settings()
    key_ctx = await validate_key(extract_bearer(request), request_ip(request))
    assert pool is not None
    rows = await pool.fetch("""
      SELECT pm.platform_model_name,pm.visibility_scope
      FROM platform_model pm
      JOIN route_policy rp ON rp.id=pm.route_policy_id
      WHERE pm.status='已发布' AND UPPER(rp.status)='ACTIVE'
        AND rp.model_alias=pm.platform_model_name
      ORDER BY pm.platform_model_name
    """)
    data = []
    for row in rows:
        alias = row["platform_model_name"]
        try:
            validate_model_scope(key_ctx, alias)
            validate_visibility(row["visibility_scope"], key_ctx)
            data.append({"id": alias, "object": "model", "owned_by": "tokensea"})
        except HTTPException:
            continue
    return {"object": "list", "data": data}


async def proxy_openai_compatible(request: Request, endpoint: str):
    started = time.monotonic()
    validate_client_request_id(request.headers.get("x-request-id"))
    request_id = uuid.uuid4().hex
    budget: Optional[Dict[str, Any]] = None
    deferred_budget = False
    try:
        require_runtime_settings()
        try:
            body = await request.json()
        except Exception:
            raise gateway_error(400, "TOKENSEA_INVALID_JSON", "请求体必须是 JSON")
        if not isinstance(body, dict):
            raise gateway_error(400, "TOKENSEA_INVALID_REQUEST", "请求体必须是对象")
        model_alias = body.get("model")
        if not isinstance(model_alias, str) or not model_alias.strip():
            raise gateway_error(400, "TOKENSEA_MODEL_REQUIRED", "model 不能为空")
        key_ctx = await validate_key(extract_bearer(request), request_ip(request), model_alias)
        routes = await select_routes(model_alias, key_ctx)
        if not routes:
            raise gateway_error(404, "TOKENSEA_MODEL_NOT_FOUND", "模型未配置可执行路由")
        reserved_tokens = estimate_reserved_tokens(body)
        await reserve_rate_limits("key", key_ctx["id"], key_ctx, reserved_tokens)
        budget = await reserve_budget(key_ctx, routes, reserved_tokens, request_id)
        if budget.get("degrade_model_alias"):
            degraded_alias = budget["degrade_model_alias"]
            validate_model_scope(key_ctx, degraded_alias)
            routes = await select_routes(degraded_alias, key_ctx)
            if not routes:
                raise gateway_error(503, "TOKENSEA_BUDGET_DEGRADE_UNAVAILABLE", "预算降级模型不可用")
            body = dict(body); body["model"] = degraded_alias
            budget = await reserve_budget(key_ctx, routes, reserved_tokens, request_id, allow_degrade=False)
        if body.get("stream"):
            body = dict(body)
            body.setdefault("stream_options", {})
            if isinstance(body["stream_options"], dict):
                body["stream_options"]["include_usage"] = True
            result = await execute_stream(endpoint, body, request_id, started, key_ctx, routes, budget)
            deferred_budget = isinstance(result, StreamingResponse)
            return result
        return await execute_non_stream(endpoint, body, request_id, started, key_ctx, routes, budget)
    except HTTPException as exc:
        REQUESTS.labels(endpoint=endpoint, status="FAILED").inc()
        LATENCY.labels(endpoint=endpoint).observe(time.monotonic() - started)
        headers = dict(exc.headers or {})
        headers.setdefault("x-request-id", request_id)
        exc.headers = headers
        raise exc
    except Exception:
        REQUESTS.labels(endpoint=endpoint, status="FAILED").inc()
        LATENCY.labels(endpoint=endpoint).observe(time.monotonic() - started)
        LOGGER.exception("gateway_unhandled_error request_id=%s endpoint=%s", request_id, endpoint)
        return JSONResponse(
            error_body("TOKENSEA_GATEWAY_ERROR", "网关内部异常，请根据请求 ID 检查 Gateway 日志"),
            status_code=502,
            headers={"x-request-id": request_id},
        )
    finally:
        if budget and not deferred_budget:
            if not await release_budget_safely(budget):
                raise gateway_error(503, "TOKENSEA_ACCOUNTING_RELEASE_FAILED", "预算释放无法持久化")


async def create_request_intent(request_id: str, key_ctx: Dict[str, Any], route: Dict[str, Any],
                                model_alias: str, budget: Dict[str, Any]) -> str:
    usage_id = hashlib.sha256(f"usage:{request_id}".encode()).hexdigest()[:32]
    payload = {"request_id": request_id, "usage_id": usage_id, "tenant_id": key_ctx.get("tenant_id"),
               "project_id": key_ctx.get("project_id"), "app_id": key_ctx.get("app_id"),
               "api_key_id": key_ctx.get("id"), "model_alias": model_alias,
               "runtime_model_name": route.get("runtime_model_name"), "provider_id": route.get("provider_id"),
               "price_version_id": route["price"]["id"], "currency": route["price"]["currency"],
               "reservation": serializable_reservation(budget), "created_at": utc_timestamp()}
    event_id = hashlib.sha256(f"intent:{request_id}".encode()).hexdigest()
    await enqueue_outbox("request_intent", payload, event_id=event_id,
                         available_at_epoch=time.time() + INTENT_RECOVERY_SECONDS,
                         require_db_and_wal=True)
    budget["intent_id"] = event_id
    return event_id


async def complete_request_intent(budget: Dict[str, Any]):
    event_id = budget.get("intent_id")
    if not event_id:
        return
    try:
        await mark_db_outbox_done(event_id)
    except Exception:
        pass
    try:
        await asyncio.to_thread(wal_ack, event_id)
    except Exception:
        pass


async def execute_non_stream(endpoint, body, request_id, started, key_ctx, routes, budget):
    await create_request_intent(request_id, key_ctx, routes[0], body["model"], budget)
    fallback_chain: List[Dict[str, Any]] = []
    attempt_no = 0
    last_status = 503
    last_code = "TOKENSEA_UPSTREAM_UNAVAILABLE"
    for route in routes:
        for retry_no in range(route["max_retries"] + 1):
            attempt_no += 1
            attempt_started = time.monotonic()
            await reserve_provider_limits(route, estimate_reserved_tokens(body))
            try:
                try:
                    await ensure_runtime_model(route)
                except HTTPException:
                    raise
                except Exception:
                    LOGGER.exception(
                        "runtime_model_prepare_failed request_id=%s provider_id=%s runtime_model=%s",
                        request_id, route.get("provider_id"), route.get("runtime_model_name"),
                    )
                    raise gateway_error(
                        503,
                        "TOKENSEA_RUNTIME_CONFIG_FAILED",
                        "运行时模型配置失败，请根据请求 ID 检查 Gateway 日志",
                    )
                payload = dict(body)
                payload["model"] = route["runtime_alias"]
                async with httpx.AsyncClient(timeout=request_timeout(route), trust_env=False) as client:
                    response = await client.post(f"{ENGINE_URL}{endpoint}", headers=runtime_headers(request_id), json=payload)
                data = safe_json(response)
                if is_runtime_model_missing(response, data):
                    invalidate_runtime_model(route)
                    await ensure_runtime_model(route, force=True)
                    async with httpx.AsyncClient(timeout=request_timeout(route), trust_env=False) as client:
                        payload["model"] = route["runtime_alias"]
                        response = await client.post(f"{ENGINE_URL}{endpoint}", headers=runtime_headers(request_id), json=payload)
                    data = safe_json(response)
                usage = extract_usage(data, route.get("provider_name"))
                error_code = None if response.status_code < 400 else normalize_error(response.status_code)
                status = "SUCCESS" if response.status_code < 400 else "FAILED"
                if status == "SUCCESS" and usage["total_tokens"] <= 0:
                    status, error_code = "FAILED", "TOKENSEA_USAGE_MISSING"
                    response = httpx.Response(502)
                    data = error_body(error_code, "上游未返回可核算用量")
                await safe_record_attempt(request_id, attempt_no, route, status, response.status_code, error_code, usage, attempt_started)
                fallback_chain.append(attempt_summary(route, attempt_no, response.status_code, error_code))
                if status == "SUCCESS" or response.status_code not in RETRYABLE_STATUS:
                    finalization = await finalize_request(request_id, key_ctx, route, model_alias=body["model"], usage=usage,
                                                          status=status, error_code=error_code, started=started,
                                                          fallback_chain=fallback_chain, budget=budget)
                    REQUESTS.labels(endpoint=endpoint, status=status).inc()
                    LATENCY.labels(endpoint=endpoint).observe(time.monotonic() - started)
                    if status == "SUCCESS" and not finalization["durable"]:
                        return JSONResponse(error_body("TOKENSEA_ACCOUNTING_UNAVAILABLE", "账务最终化不可持久化"),
                                            status_code=503, headers={"x-request-id": request_id})
                    headers = {"x-request-id": request_id, "x-tokensea-budget-status": finalization["budget_status"]}
                    return JSONResponse(data, status_code=response.status_code, headers=headers)
                last_status, last_code = response.status_code, error_code or last_code
            except (httpx.TimeoutException, httpx.RequestError):
                last_status, last_code = 504, "TOKENSEA_UPSTREAM_TIMEOUT"
                await safe_record_attempt(request_id, attempt_no, route, "FAILED", None, last_code, {}, attempt_started)
                fallback_chain.append(attempt_summary(route, attempt_no, None, last_code))
            if retry_no < route["max_retries"]:
                await asyncio.sleep(route["retry_backoff_ms"] / 1000)
        if not route["fallback_enabled"]:
            break
    await finalize_request(request_id, key_ctx, routes[-1], body["model"], {}, "FAILED", last_code,
                         started, fallback_chain, budget)
    REQUESTS.labels(endpoint=endpoint, status="FAILED").inc()
    LATENCY.labels(endpoint=endpoint).observe(time.monotonic() - started)
    return JSONResponse(error_body(last_code, "上游服务暂不可用"), status_code=stable_gateway_status(last_status),
                        headers={"x-request-id": request_id})


async def execute_stream(endpoint, body, request_id, started, key_ctx, routes, budget):
    await create_request_intent(request_id, key_ctx, routes[0], body["model"], budget)
    fallback_chain: List[Dict[str, Any]] = []
    attempt_no = 0
    last_status, last_code = 503, "TOKENSEA_UPSTREAM_UNAVAILABLE"
    for route in routes:
        for retry_no in range(route["max_retries"] + 1):
            attempt_no += 1
            attempt_started = time.monotonic()
            await reserve_provider_limits(route, estimate_reserved_tokens(body))
            client: Optional[httpx.AsyncClient] = None
            response: Optional[httpx.Response] = None
            try:
                try:
                    await ensure_runtime_model(route)
                except HTTPException:
                    raise
                except Exception:
                    LOGGER.exception(
                        "runtime_model_prepare_failed request_id=%s provider_id=%s runtime_model=%s",
                        request_id, route.get("provider_id"), route.get("runtime_model_name"),
                    )
                    raise gateway_error(
                        503,
                        "TOKENSEA_RUNTIME_CONFIG_FAILED",
                        "运行时模型配置失败，请根据请求 ID 检查 Gateway 日志",
                    )
                payload = dict(body)
                payload["model"] = route["runtime_alias"]
                if payload.get("stream"):
                    stream_options = dict(payload.get("stream_options") or {})
                    stream_options["include_usage"] = True
                    payload["stream_options"] = stream_options
                client = httpx.AsyncClient(timeout=request_timeout(route), trust_env=False)
                upstream_request = client.build_request("POST", f"{ENGINE_URL}{endpoint}", headers=runtime_headers(request_id), json=payload)
                response = await client.send(upstream_request, stream=True)
                if response.status_code >= 400:
                    raw = await response.aread()
                    data = safe_json_bytes(raw)
                    missing = is_runtime_model_missing(response, data)
                    await response.aclose()
                    await client.aclose()
                    if missing:
                        invalidate_runtime_model(route)
                        await ensure_runtime_model(route, force=True)
                        payload["model"] = route["runtime_alias"]
                        client = httpx.AsyncClient(timeout=request_timeout(route), trust_env=False)
                        retry_request = client.build_request("POST", f"{ENGINE_URL}{endpoint}",
                                                             headers=runtime_headers(request_id), json=payload)
                        response = await client.send(retry_request, stream=True)
                        if response.status_code < 400:
                            return stream_response(response, client, endpoint, body["model"], request_id, started,
                                                   key_ctx, route, budget, fallback_chain, attempt_no, attempt_started)
                        raw = await response.aread()
                        data = safe_json_bytes(raw)
                        await response.aclose()
                        await client.aclose()
                    code = normalize_error(response.status_code)
                    await safe_record_attempt(request_id, attempt_no, route, "FAILED", response.status_code, code, {}, attempt_started)
                    fallback_chain.append(attempt_summary(route, attempt_no, response.status_code, code))
                    if missing or response.status_code in RETRYABLE_STATUS:
                        last_status, last_code = response.status_code, code
                        if retry_no < route["max_retries"]:
                            await asyncio.sleep(route["retry_backoff_ms"] / 1000)
                            continue
                        break
                    await finalize_request(request_id, key_ctx, route, body["model"], {}, "FAILED", code,
                                         started, fallback_chain, budget)
                    REQUESTS.labels(endpoint=endpoint, status="FAILED").inc()
                    LATENCY.labels(endpoint=endpoint).observe(time.monotonic() - started)
                    return JSONResponse(error_body(code, "上游拒绝请求"), status_code=stable_gateway_status(response.status_code),
                                        headers={"x-request-id": request_id})
                return stream_response(response, client, endpoint, body["model"], request_id, started, key_ctx,
                                       route, budget, fallback_chain, attempt_no, attempt_started)
            except (httpx.TimeoutException, httpx.RequestError):
                if response:
                    await response.aclose()
                if client:
                    await client.aclose()
                last_status, last_code = 504, "TOKENSEA_UPSTREAM_TIMEOUT"
                await safe_record_attempt(request_id, attempt_no, route, "FAILED", None, last_code, {}, attempt_started)
                fallback_chain.append(attempt_summary(route, attempt_no, None, last_code))
                if retry_no < route["max_retries"]:
                    await asyncio.sleep(route["retry_backoff_ms"] / 1000)
        if not route["fallback_enabled"]:
            break
    await finalize_request(request_id, key_ctx, routes[-1], body["model"], {}, "FAILED", last_code,
                         started, fallback_chain, budget)
    REQUESTS.labels(endpoint=endpoint, status="FAILED").inc()
    LATENCY.labels(endpoint=endpoint).observe(time.monotonic() - started)
    return JSONResponse(error_body(last_code, "上游服务暂不可用"), status_code=stable_gateway_status(last_status),
                        headers={"x-request-id": request_id})


def stream_response(response, client, endpoint, model_alias, request_id, started, key_ctx,
                    route, budget, fallback_chain, attempt_no, attempt_started):
    async def generate():
        usage: Dict[str, int] = empty_usage()
        status, code = "SUCCESS", None
        buffer = b""
        try:
            async for chunk in response.aiter_bytes():
                buffer += chunk
                while b"\n" in buffer:
                    line, buffer = buffer.split(b"\n", 1)
                    merge_usage(usage, usage_from_sse_line(line, route.get("provider_name")))
                yield chunk
        except asyncio.CancelledError:
            status, code = "FAILED", "TOKENSEA_CLIENT_DISCONNECTED"
            raise
        except Exception:
            status, code = "FAILED", "TOKENSEA_STREAM_INTERRUPTED"
            yield ("data: " + json.dumps(error_body(code, "流式响应中断"), ensure_ascii=False) + "\n\n").encode()
        finally:
            if status == "SUCCESS" and usage["total_tokens"] <= 0:
                status, code = "FAILED", "TOKENSEA_USAGE_MISSING"
            fallback_chain.append(attempt_summary(route, attempt_no, response.status_code, code))
            async def finish():
                await response.aclose()
                await client.aclose()
                try:
                    await safe_record_attempt(request_id, attempt_no, route, status, response.status_code, code, usage, attempt_started)
                    await finalize_request(request_id, key_ctx, route, model_alias, usage, status, code,
                                           started, fallback_chain, budget)
                finally:
                    await release_budget_safely(budget)
                    REQUESTS.labels(endpoint=endpoint, status=status).inc()
                    LATENCY.labels(endpoint=endpoint).observe(time.monotonic() - started)
            await asyncio.shield(finish())
    media_type = response.headers.get("content-type", "text/event-stream").split(";", 1)[0]
    forwarded = {"x-request-id": request_id}
    if response.headers.get("cache-control"):
        forwarded["cache-control"] = response.headers["cache-control"]
    return StreamingResponse(generate(), status_code=response.status_code, media_type=media_type, headers=forwarded)


async def validate_key(token: str, source_ip: str, model_alias: Optional[str] = None) -> Dict[str, Any]:
    assert pool is not None
    row = await pool.fetchrow("""
      SELECT k.id,k.tenant_id,k.project_id,k.app_id,k.status,k.approval_status,k.model_scope,
             k.budget_amount,k.rpm_limit,k.tpm_limit,k.qps_limit,k.ip_whitelist,k.expires_at,
             t.status tenant_status,t.type tenant_type,t.model_scope tenant_model_scope,t.monthly_budget tenant_budget,
             p.id joined_project_id,p.tenant_id project_tenant_id,p.status project_status,p.monthly_budget project_budget,
             a.id joined_app_id,a.tenant_id app_tenant_id,a.project_id app_project_id,a.status app_status
      FROM api_key k JOIN tenant t ON t.id=k.tenant_id
      LEFT JOIN project p ON p.id=k.project_id LEFT JOIN app a ON a.id=k.app_id
      WHERE k.key_hash=$1
    """, hashlib.sha256(token.encode()).hexdigest())
    if not row:
        raise gateway_error(401, "TOKENSEA_KEY_INVALID", "API Key 无效")
    ctx = dict(row)
    if ctx["status"] != "ACTIVE" or ctx["approval_status"] != "APPROVED":
        raise gateway_error(403, "TOKENSEA_KEY_DISABLED", "API Key 未启用")
    if ctx["expires_at"] and ctx["expires_at"].timestamp() <= time.time():
        raise gateway_error(403, "TOKENSEA_KEY_EXPIRED", "API Key 已过期")
    if str(ctx["tenant_status"]).upper() not in ACTIVE_VALUES:
        raise gateway_error(403, "TOKENSEA_TENANT_DISABLED", "租户未启用")
    if ctx["project_id"] and (not ctx["joined_project_id"] or ctx["project_tenant_id"] != ctx["tenant_id"] or str(ctx["project_status"]).upper() not in ACTIVE_VALUES):
        raise gateway_error(403, "TOKENSEA_PROJECT_FORBIDDEN", "项目不可用或不属于该租户")
    if ctx["app_id"] and (not ctx["joined_app_id"] or ctx["app_tenant_id"] != ctx["tenant_id"] or
                          str(ctx["app_status"]).upper() not in ACTIVE_VALUES or
                          (ctx["project_id"] and ctx["app_project_id"] != ctx["project_id"])):
        raise gateway_error(403, "TOKENSEA_APP_FORBIDDEN", "应用不可用或从属关系无效")
    ctx["model_scope_parsed"] = parse_scope(ctx["model_scope"])
    ctx["tenant_scope_parsed"] = parse_scope(ctx["tenant_model_scope"])
    validate_ip_whitelist(ctx["ip_whitelist"], source_ip)
    if model_alias:
        validate_model_scope(ctx, model_alias)
    return ctx


def validate_model_scope(ctx: Dict[str, Any], model_alias: str):
    if not scope_allows(ctx["model_scope_parsed"], model_alias) or not scope_allows(ctx["tenant_scope_parsed"], model_alias):
        raise gateway_error(403, "TOKENSEA_MODEL_FORBIDDEN", "当前 Key 或租户无模型权限")


async def select_routes(model_alias: str, key_ctx: Dict[str, Any]) -> List[Dict[str, Any]]:
    assert pool is not None
    model = await pool.fetchrow("""
      SELECT pm.id,pm.platform_model_name,pm.display_name,pm.provider_instance_ids,pm.actual_models,
             pm.visibility_scope,pm.price_policy_id,pm.route_policy_id,
             rp.strategy,rp.fallback_enabled,rp.status route_status,rp.config route_config,rp.model_alias route_model_alias
      FROM platform_model pm LEFT JOIN route_policy rp ON rp.id=pm.route_policy_id
      WHERE pm.platform_model_name=$1 AND pm.status='已发布'
    """, model_alias)
    if not model:
        return []
    validate_visibility(model["visibility_scope"], key_ctx)
    if not model["route_policy_id"] or str(model["route_status"]).upper() != "ACTIVE" or model["route_model_alias"] != model_alias:
        raise gateway_error(503, "TOKENSEA_ROUTE_POLICY_INVALID", "模型路由策略未生效")
    config = strict_object(model["route_config"], "TOKENSEA_ROUTE_POLICY_INVALID")
    instance_ids = strict_string_list(model["provider_instance_ids"])
    actual_models = strict_string_list(model["actual_models"])
    if not instance_ids or not actual_models or len(set(instance_ids)) != len(instance_ids) or len(set(actual_models)) != len(actual_models):
        raise gateway_error(503, "TOKENSEA_ROUTE_MAPPING_INVALID", "模型路由映射无效")
    deployment_rows = await pool.fetch("""
      SELECT provider_instance_id,provider_model_name
      FROM channel_model_deployment
      WHERE provider_instance_id=ANY($1::varchar[]) AND discovery_status<>'MISSING_CONFIRMED'
    """, instance_ids)
    deployments_by_model: Dict[str, List[str]] = {}
    for deployment in deployment_rows:
        deployments_by_model.setdefault(str(deployment["provider_model_name"]).lower(), []).append(
            str(deployment["provider_instance_id"]))
    mappings = []
    for index, actual in enumerate(actual_models):
        matches = sorted(set(deployments_by_model.get(actual.lower(), [])))
        if len(matches) != 1:
            raise gateway_error(503, "TOKENSEA_ROUTE_MAPPING_INVALID", "模型路由映射无效")
        mappings.append({"provider_instance_id": matches[0],
                         "actual_model": actual, "priority": index + 1, "weight": 100,
                         "timeout_seconds": 120, "max_retries": 0,
                         "price_version_id": model["price_policy_id"]})
    if {mapping["provider_instance_id"] for mapping in mappings} != set(instance_ids):
        raise gateway_error(503, "TOKENSEA_ROUTE_MAPPING_INVALID", "模型路由映射无效")
    configured = config.get("candidates")
    if configured is not None:
        if not isinstance(configured, list) or not configured:
            raise gateway_error(503, "TOKENSEA_ROUTE_POLICY_INVALID", "候选路由配置无效")
        allowed = {(m["provider_instance_id"], m["actual_model"]) for m in mappings}
        mappings = []
        for item in configured:
            if not isinstance(item, dict):
                raise gateway_error(503, "TOKENSEA_ROUTE_POLICY_INVALID", "候选路由配置无效")
            pair = (item.get("providerInstanceId"), item.get("actualModel"))
            if pair not in allowed:
                raise gateway_error(503, "TOKENSEA_ROUTE_POLICY_INVALID", "候选路由不属于已发布映射")
            mappings.append({"provider_instance_id": pair[0], "actual_model": pair[1],
                             "priority": bounded_int(item.get("priority", len(mappings) + 1), 1, 10000),
                             "weight": bounded_int(item.get("weight", 100), 1, 10000),
                             "timeout_seconds": bounded_int(item.get("timeoutSeconds", 120), 1, 300),
                             "max_retries": bounded_int(item.get("maxRetries", config.get("maxRetries", 0)), 0, 3),
                             "price_version_id": item.get("priceVersionId") or model["price_policy_id"]})
    rows = await pool.fetch("""
      SELECT id,provider_type,api_style,api_base,credential_ref,key_status,status,health_status,
             last_connection_test_status,last_connection_test_at,last_connection_test_host,
             last_connection_test_addresses,last_connection_test_port,rate_limit_rpm,rate_limit_tpm,updated_at
      FROM provider_instance WHERE id=ANY($1::varchar[])
    """, list({m["provider_instance_id"] for m in mappings}))
    providers = {row["id"]: row for row in rows}
    routes = []
    for mapping in mappings:
        instance = providers.get(mapping["provider_instance_id"])
        if not provider_is_routable(instance):
            continue
        secret, secret_version = await resolve_provider_secret(instance)
        if instance["key_status"] != "无需 Key" and not secret:
            continue
        price = await load_price(mapping["price_version_id"], model["id"], instance["id"], mapping["actual_model"])
        route = dict(mapping)
        route.update({"platform_model_id": model["id"], "service_model_name": model_alias,
                      "provider_id": instance["id"], "provider_name": instance["provider_type"],
                      "runtime_model_name": runtime_model_name(instance, mapping["actual_model"]),
                      "api_base": instance["api_base"], "api_key": secret,
                      "provider_rpm": instance["rate_limit_rpm"], "provider_tpm": instance["rate_limit_tpm"],
                      "verified_host": instance["last_connection_test_host"],
                      "verified_addresses": instance["last_connection_test_addresses"],
                      "verified_port": instance["last_connection_test_port"],
                      "secret_version": secret_version, "price": price,
                      "fallback_enabled": bool(model["fallback_enabled"]),
                      "retry_backoff_ms": bounded_int(config.get("retryBackoffMs", 100), 0, 5000)})
        route["deployment_id"] = f"{model['id']}:{instance['id']}:{mapping['actual_model']}"
        routes.append(route)
    return ordered_routes(routes, model["strategy"])


async def load_price(_price_id: Any, platform_model_id: str, provider_instance_id: str, actual_model: str) -> Dict[str, Any]:
    assert pool is not None
    row = await pool.fetchrow("""
      SELECT actual.id,actual.price_layer,actual.currency,d.id channel_deployment_id,
             actual.billing_basis cost_billing_basis,
             actual.billing_quantity cost_billing_quantity,
             actual.input_unit_price input_cost_unit_price,
             actual.cache_read_unit_price cache_read_cost_unit_price,
             actual.cache_write_unit_price cache_write_cost_unit_price,
             actual.cache_read_mode,actual.cache_write_mode,
             actual.component_schema_version,actual.price_completeness_status,
             actual.output_unit_price output_cost_unit_price,
             internal.id internal_price_id,
             COALESCE(internal.billing_basis,actual.billing_basis) price_billing_basis,
             COALESCE(internal.billing_quantity,actual.billing_quantity) price_billing_quantity,
             COALESCE(internal.input_unit_price,actual.input_unit_price) input_price_unit_price,
             COALESCE(internal.output_unit_price,actual.output_unit_price) output_price_unit_price,
             COALESCE(actual.source_ref,'price_version:'||actual.id) source_ref,
             actual.price_components,actual.evidence_hash,actual.region,actual.request_mode,
             actual.service_tier,actual.context_tier
      FROM channel_model_deployment d
      JOIN LATERAL (
        SELECT * FROM price_version p WHERE p.deployment_id=d.id
          AND p.price_layer IN ('CONTRACT_PRICE','CHANNEL_ACTUAL','PROVIDER_OFFICIAL')
          AND p.status='ACTIVE' AND p.effective_from<=now() AND (p.effective_to IS NULL OR p.effective_to>now())
        ORDER BY CASE p.price_layer WHEN 'CONTRACT_PRICE' THEN 0 WHEN 'CHANNEL_ACTUAL' THEN 1 ELSE 2 END,
                 p.effective_from DESC,p.version DESC LIMIT 1
      ) actual ON true
      LEFT JOIN LATERAL (
        SELECT * FROM price_version p WHERE p.platform_model_id=$1 AND p.price_layer='INTERNAL_ACCOUNTING'
          AND p.status='ACTIVE' AND p.currency=actual.currency AND p.effective_from<=now()
          AND (p.effective_to IS NULL OR p.effective_to>now()) ORDER BY p.effective_from DESC,p.version DESC LIMIT 1
      ) internal ON true
      WHERE d.provider_instance_id=$2 AND d.provider_model_name=$3
        AND d.production_status='APPROVED' AND d.health_status='HEALTHY'
        AND d.discovery_status<>'MISSING_CONFIRMED' AND d.routing_status='ELIGIBLE'
        AND (SELECT v.status FROM capability_validation v WHERE v.deployment_id=d.id
          AND v.test_type='LIVE_PROBE' ORDER BY v.validated_at DESC LIMIT 1)='PASSED'
    """, platform_model_id, provider_instance_id, actual_model)
    if not row:
        row = await pool.fetchrow("""
          SELECT NULL::varchar id,'PUBLIC_REFERENCE' price_layer,r.currency,d.id channel_deployment_id,
                 r.billing_basis cost_billing_basis,r.billing_quantity cost_billing_quantity,
                 r.input_unit_price input_cost_unit_price,r.cache_read_unit_price cache_read_cost_unit_price,
                 r.cache_write_unit_price cache_write_cost_unit_price,
                 CASE WHEN r.cache_read_unit_price IS NULL THEN 'UNKNOWN' ELSE 'EXPLICIT' END cache_read_mode,
                 CASE WHEN r.cache_write_unit_price IS NULL THEN 'UNKNOWN' ELSE 'EXPLICIT' END cache_write_mode,
                 2 component_schema_version,'COMPLETE' price_completeness_status,
                 r.output_unit_price output_cost_unit_price,NULL::varchar internal_price_id,
                 r.billing_basis price_billing_basis,r.billing_quantity price_billing_quantity,
                 r.input_unit_price input_price_unit_price,r.output_unit_price output_price_unit_price,
                 r.source_ref,r.price_components,r.evidence_hash,r.region,'STANDARD' request_mode,
                 'DEFAULT' service_tier,'DEFAULT' context_tier
          FROM channel_model_deployment d
          JOIN v_effective_deployment_reference_price r ON r.deployment_id=d.id
          WHERE d.provider_instance_id=$1 AND d.provider_model_name=$2
            AND d.production_status='APPROVED' AND d.health_status='HEALTHY'
            AND d.discovery_status<>'MISSING_CONFIRMED' AND d.routing_status='ELIGIBLE'
            AND (SELECT v.status FROM capability_validation v WHERE v.deployment_id=d.id
              AND v.test_type='LIVE_PROBE' ORDER BY v.validated_at DESC LIMIT 1)='PASSED'
        """, provider_instance_id, actual_model)
    if not row:
        raise gateway_error(503, "TOKENSEA_PRICE_NOT_CONFIGURED", "模型未匹配当前有效成本价或自动参考价")
    price = dict(row)
    price["price_components"] = parse_json_array(price.get("price_components"))
    for component in price["price_components"]:
        if "unitBasis" not in component and component.get("billingBasis") is not None:
            component["unitBasis"] = component["billingBasis"]
        if "unitQuantity" not in component and component.get("billingQuantity") is not None:
            component["unitQuantity"] = component["billingQuantity"]
    amount_fields = ("input_cost_unit_price", "output_cost_unit_price",
                     "input_price_unit_price", "output_price_unit_price")
    quantity_fields = ("cost_billing_quantity", "price_billing_quantity")
    bases = {"TOKEN", "REQUEST", "IMAGE", "SECOND", "MINUTE", "CHARACTER", "AUDIO_MINUTE", "TOKEN_SECOND"}
    if (any(price[name] is None or Decimal(str(price[name])) < 0 for name in amount_fields) \
            or any(price[name] is None or int(price[name]) <= 0 for name in quantity_fields) \
            or price.get("cost_billing_basis") not in bases \
            or price.get("price_billing_basis") not in bases):
        raise gateway_error(503, "TOKENSEA_PRICE_INVALID", "价格版本的计费对象、计费基数或金额无效")
    if int(price.get("component_schema_version") or 0) < 2:
        raise gateway_error(503, "TOKENSEA_PRICE_INVALID", "价格版本组件结构不是 V2，请重新同步并激活价格")
    completeness = str(price.get("price_completeness_status") or "PARTIAL")
    if completeness in {"PARTIAL", "UNKNOWN_CACHE_PRICE"}:
        raise gateway_error(503, "TOKENSEA_CACHE_PRICE_MISSING", "模型缓存价格尚未完整确认，不能用于生产计费")
    if not price["price_components"]:
        raise gateway_error(503, "TOKENSEA_PRICE_INVALID", "价格版本缺少价格组件")
    if price["currency"] == BUDGET_CURRENCY:
        price["budget_fx_rate"] = Decimal("1")
        price["budget_currency"] = BUDGET_CURRENCY
        price["budget_fx_rate_id"] = None
        price["budget_fx_source_date"] = None
    else:
        fx = await pool.fetchrow("""
          SELECT id,rate,source_date,source_type,source_ref
          FROM fx_rate
          WHERE rate_month=date_trunc('month',now() AT TIME ZONE 'Asia/Shanghai')::date
            AND from_currency=$1 AND to_currency=$2 AND status='ACTIVE'
          ORDER BY version DESC LIMIT 1
        """, price["currency"], BUDGET_CURRENCY)
        if not fx:
            raise gateway_error(503, "TOKENSEA_FX_RATE_MISSING",
                                f"缺少当前月份 {price['currency']} 到 {BUDGET_CURRENCY} 的汇率")
        price["budget_fx_rate"] = Decimal(str(fx["rate"]))
        price["budget_currency"] = BUDGET_CURRENCY
        price["budget_fx_rate_id"] = fx["id"]
        price["budget_fx_source_date"] = fx["source_date"]
        price["budget_fx_source_type"] = fx["source_type"]
        price["budget_fx_source_ref"] = fx["source_ref"]
    return price


async def reserve_rate_limits(kind: str, subject_id: str, limits: Dict[str, Any], token_amount: int):
    if cache is None:
        raise gateway_error(503, "TOKENSEA_RATE_LIMIT_UNAVAILABLE", "限流服务不可用")
    now = int(time.time())
    keys = [f"ts:rate:{kind}:{subject_id}:rpm:{now//60}", f"ts:rate:{kind}:{subject_id}:qps:{now}",
            f"ts:rate:{kind}:{subject_id}:tpm:{now//60}"]
    values = [limits.get("rpm_limit") or limits.get("provider_rpm") or 0, 1, 120,
              limits.get("qps_limit") or 0, 1, 2,
              limits.get("tpm_limit") or limits.get("provider_tpm") or 0, token_amount, 120]
    try:
        rejected = int(await cache.eval(RATE_LIMIT_LUA, len(keys), *keys, *values))
    except Exception:
        raise gateway_error(503, "TOKENSEA_RATE_LIMIT_UNAVAILABLE", "限流服务不可用")
    if rejected:
        codes = {1: "TOKENSEA_RPM_LIMIT", 2: "TOKENSEA_QPS_LIMIT", 3: "TOKENSEA_TPM_LIMIT"}
        raise gateway_error(429, codes[rejected], "请求超过配额限制")


async def reserve_provider_limits(route: Dict[str, Any], tokens: int):
    await reserve_rate_limits("provider", route["provider_id"], route, tokens)


def estimate_price_reservation(price: Dict[str, Any], reserved_tokens: int) -> Decimal:
    token_components = [component for component in parse_json_array(price.get("price_components"))
                        if str(component.get("unitBasis") or "TOKEN").upper() == "TOKEN"
                        and str(component.get("mode") or "EXPLICIT").upper()
                        not in {"UNKNOWN", "NOT_APPLICABLE"}
                        and component.get("unitPrice") is not None]
    if token_components:
        estimates = [Decimal(reserved_tokens) * Decimal(str(component["unitPrice"]))
                     / Decimal(int(component.get("unitQuantity") or 1))
                     for component in token_components]
        original_amount = max(estimates)
    else:
        basis = str(price.get("cost_billing_basis") or "TOKEN")
        quantity = Decimal(int(price.get("cost_billing_quantity") or 1))
        count = Decimal(reserved_tokens if basis == "TOKEN" else 1)
        unit_price = max(Decimal(str(price.get("input_cost_unit_price") or "0")),
                         Decimal(str(price.get("output_cost_unit_price") or "0")))
        original_amount = count * unit_price / quantity
    return original_amount * Decimal(str(price.get("budget_fx_rate") or "1"))


async def reserve_budget(key_ctx: Dict[str, Any], routes: List[Dict[str, Any]], reserved_tokens: int,
                         request_id: str, reservation_token: Optional[str] = None,
                         allow_degrade: bool = True) -> Dict[str, Any]:
    # Budgets use actual provider cost in BUDGET_CURRENCY. Reserving every estimated
    # token at the route's higher input/output cost rate is conservative, so the
    # later settlement can only release capacity under normal provider accounting.
    amount = max(estimate_price_reservation(route["price"], reserved_tokens) for route in routes)
    amount_micro = money_micro(amount)
    scopes = [("API_KEY", "key", key_ctx["id"], key_ctx.get("budget_amount")),
              ("APP", "app", key_ctx.get("app_id"), None),
              ("PROJECT", "project", key_ctx.get("project_id"), key_ctx.get("project_budget")),
              ("TENANT", "tenant", key_ctx["tenant_id"], key_ctx.get("tenant_budget"))]
    token = reservation_token or uuid.uuid4().hex
    state_key = f"ts:budget:reservation:{request_id}"
    if not any(scope[2] and scope[3] is not None for scope in scopes) and not hasattr(pool, "fetchrow"):
        return {"state_key": state_key, "keys": [], "amount_micro": 0, "amount": Decimal("0"),
                "limits": [], "token": token, "settled": True}
    if cache is None or pool is None:
        raise gateway_error(503, "TOKENSEA_BUDGET_UNAVAILABLE", "预算服务不可用")
    month = datetime.now(timezone.utc).strftime("%Y%m")
    ttl = 35 * 24 * 3600
    keys, pairs, limits = [], [], []
    for scope_type, kind, subject_id, legacy_limit in scopes:
        if not subject_id:
            continue
        rule = None
        if hasattr(pool, "fetchrow"):
            rule = await pool.fetchrow("""
              SELECT id,currency,amount_limit,warning_threshold_percent,over_limit_action,degrade_model_alias
              FROM budget_rule WHERE scope_type=$1 AND scope_id=$2 AND status='ACTIVE' AND approval_status='APPROVED'
                AND effective_from<=now() AND (effective_to IS NULL OR effective_to>now())
              ORDER BY version DESC LIMIT 1
            """, scope_type, subject_id)
        limit = rule["amount_limit"] if rule else legacy_limit
        if limit is None:
            continue
        if rule and str(rule["currency"]).upper() != BUDGET_CURRENCY:
            raise gateway_error(503, "TOKENSEA_BUDGET_CURRENCY_INVALID", "预算规则币种必须统一为 CNY")
        limit_micro = money_micro(Decimal(str(limit)))
        if limit_micro <= 0:
            raise gateway_error(402, "TOKENSEA_BUDGET_EXCEEDED", "预算额度不可用")
        column = {"key": "api_key_id", "app": "app_id", "project": "project_id", "tenant": "tenant_id"}[kind]
        missing_fx = await pool.fetchval(f"""
          SELECT count(*) FROM usage_record
          WHERE {column}=$1 AND status='SUCCESS'
            AND created_at>=date_trunc('month',now() AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai'
            AND currency<>$2 AND tokensea_fx_rate(created_at,currency,$2) IS NULL
        """, subject_id, BUDGET_CURRENCY)
        if int(missing_fx or 0) > 0:
            raise gateway_error(503, "TOKENSEA_FX_RATE_MISSING", "本月历史用量存在缺失汇率，无法执行预算核算")
        committed = await pool.fetchval(f"""
          SELECT COALESCE(SUM(COALESCE(tokensea_fx_amount(cost_amount,currency,created_at,$2),0)),0)
          FROM usage_record
          WHERE {column}=$1 AND status='SUCCESS'
            AND created_at>=date_trunc('month',now() AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai'
        """, subject_id, BUDGET_CURRENCY)
        committed_amount = Decimal(str(committed))
        threshold_percent = Decimal(str(rule["warning_threshold_percent"] if rule else 100))
        threshold = Decimal(str(limit)) * threshold_percent / Decimal(100)
        action = str(rule["over_limit_action"] if rule else "BLOCK")
        projected = committed_amount + amount
        if rule and projected >= threshold:
            await record_budget_rule_event(rule["id"], request_id, committed_amount, amount, threshold, action,
                                           {"scopeType": scope_type, "scopeId": subject_id})
        if projected > Decimal(str(limit)):
            if action == "ALERT_ONLY":
                continue
            if action == "DEGRADE" and allow_degrade and rule["degrade_model_alias"]:
                return {"state_key": state_key, "keys": [], "amount_micro": 0, "amount": Decimal("0"),
                        "limits": [], "token": token, "settled": True,
                        "degrade_model_alias": rule["degrade_model_alias"], "rule_id": rule["id"]}
            raise gateway_error(402, "TOKENSEA_BUDGET_EXCEEDED", "月度实际成本预算不足")
        keys.append(f"ts:budget:{kind}:{subject_id}:{month}")
        pairs.extend([money_micro(Decimal(str(committed))), limit_micro])
        limits.append(limit_micro)
    if not keys:
        return {"state_key": state_key, "keys": [], "amount_micro": 0, "amount": Decimal("0"),
                "limits": [], "token": token, "settled": True}
    try:
        rejected = int(await cache.eval(BUDGET_RESERVE_LUA, len(keys) + 1, state_key, *keys,
                                        amount_micro, ttl, token, *pairs, utc_timestamp()))
    except Exception:
        raise gateway_error(503, "TOKENSEA_BUDGET_UNAVAILABLE", "预算服务不可用")
    if rejected > 0:
        raise gateway_error(402, "TOKENSEA_BUDGET_EXCEEDED", "月度实际成本预算不足")
    if rejected == -2:
        raise gateway_error(409, "TOKENSEA_BUDGET_RESERVATION_CONFLICT", "预算预占标识冲突")
    return {"state_key": state_key, "keys": keys, "amount_micro": amount_micro, "amount": amount,
            "limits": limits, "token": token, "settled": False}


async def record_budget_rule_event(rule_id: str, request_id: str, current: Decimal, estimated: Decimal,
                                   threshold: Decimal, action: str, detail: Dict[str, Any]):
    if pool is None or not hasattr(pool, "execute"):
        return
    event_id = hashlib.sha256(f"budget-rule:{rule_id}:{request_id}".encode()).hexdigest()[:32]
    await pool.execute("""
      INSERT INTO budget_rule_event(id,rule_id,request_id,current_cost,estimated_cost,threshold_cost,action,detail)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8::jsonb) ON CONFLICT(id) DO NOTHING
    """, event_id, rule_id, request_id, current, estimated, threshold, action,
       json.dumps(detail, ensure_ascii=False))
    await pool.execute("""
      INSERT INTO alert_event(id,alert_type,severity,resource_type,resource_id,title,detail)
      VALUES($1,'BUDGET_THRESHOLD','WARNING','BUDGET_RULE',$2,'预算成本达到阈值',$3::jsonb)
      ON CONFLICT(id) DO NOTHING
    """, "alert"+event_id[5:], rule_id, json.dumps(detail, ensure_ascii=False))


async def settle_budget(reservation: Dict[str, Any], actual: Decimal) -> str:
    keys = reservation.get("keys") or []
    if not keys:
        reservation["settled"] = True
        return "SETTLED"
    if cache is None:
        raise gateway_error(503, "TOKENSEA_BUDGET_UNAVAILABLE", "预算服务不可用")
    result = int(await cache.eval(BUDGET_SETTLE_LUA, len(keys) + 1, reservation["state_key"], *keys,
                                  money_micro(actual), *reservation["limits"], utc_timestamp()))
    if result > 0:
        reservation["settled"] = True
        reservation["overrun"] = True
        return "OVERRUN"
    if result < 0:
        raise gateway_error(503, "TOKENSEA_BUDGET_STATE_INVALID", "预算预占状态无效")
    reservation["settled"] = True
    return "SETTLED"


async def release_budget(reservation: Dict[str, Any]):
    keys = reservation.get("keys") or []
    if not keys or reservation.get("settled") or reservation.get("deferred_accounting"):
        return
    if cache is None:
        raise RuntimeError("Redis is unavailable")
    await cache.eval(BUDGET_RELEASE_LUA, len(keys) + 1, reservation["state_key"], *keys, utc_timestamp())
    reservation["released"] = True


async def release_budget_safely(reservation: Dict[str, Any]):
    try:
        await release_budget(reservation)
        return True
    except Exception:
        try:
            await enqueue_outbox("budget_release", serializable_reservation(reservation))
            return True
        except Exception:
            return False


CACHE_PRICING_ALERT_CODES = {
    "TOKENSEA_CACHE_PRICE_MISSING": ("CACHE_PRICE_MISSING", "模型缓存价格缺失或尚未确认"),
    "TOKENSEA_CACHE_USAGE_UNRECOGNIZED": ("CACHE_USAGE_UNRECOGNIZED", "上游缓存 Usage 无法可靠识别"),
    "TOKENSEA_CACHE_USAGE_INCONSISTENT": ("CACHE_USAGE_INCONSISTENT", "上游缓存 Usage 互斥关系异常"),
    "TOKENSEA_CACHE_COMPONENT_AMBIGUOUS": ("CACHE_COMPONENT_AMBIGUOUS", "缓存价格组件作用域匹配歧义"),
}


async def record_runtime_pricing_alert(request_id: str, route: Dict[str, Any], error: HTTPException):
    if pool is None or not isinstance(error.detail, dict):
        return
    error_code = str(error.detail.get("error_code") or "")
    definition = CACHE_PRICING_ALERT_CODES.get(error_code)
    if definition is None:
        return
    alert_type, title = definition
    alert_id = hashlib.sha256(f"cache-alert:{request_id}:{error_code}".encode()).hexdigest()[:32]
    detail = {
        "requestId": request_id,
        "errorCode": error_code,
        "message": error.detail.get("message"),
        "providerInstanceId": route.get("provider_id"),
        "providerType": route.get("provider_name"),
        "runtimeModelName": route.get("runtime_model_name"),
        "priceVersionId": (route.get("price") or {}).get("id"),
    }
    try:
        await pool.execute("""
          INSERT INTO alert_event(id,alert_type,severity,resource_type,resource_id,title,detail)
          VALUES($1,$2,'HIGH','REQUEST',$3,$4,$5::jsonb)
          ON CONFLICT(id) DO UPDATE SET detail=EXCLUDED.detail,status='OPEN',updated_at=now()
        """, alert_id, alert_type, request_id, title, json.dumps(detail, ensure_ascii=False))
    except Exception:
        return


async def persist_attempt(payload: Dict[str, Any]):
    assert pool is not None
    await pool.execute("""
      INSERT INTO request_attempt(id,request_id,attempt_no,provider_instance_id,runtime_model_name,price_version_id,
        status,http_status,error_code,prompt_tokens,completion_tokens,total_tokens,latency_ms,started_at,completed_at,cost_snapshot,actual_cost_amount)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16::jsonb,$17)
      ON CONFLICT(request_id,attempt_no) DO UPDATE SET status=EXCLUDED.status,http_status=EXCLUDED.http_status,
        error_code=EXCLUDED.error_code,prompt_tokens=EXCLUDED.prompt_tokens,completion_tokens=EXCLUDED.completion_tokens,
        total_tokens=EXCLUDED.total_tokens,latency_ms=EXCLUDED.latency_ms,completed_at=EXCLUDED.completed_at,
        cost_snapshot=EXCLUDED.cost_snapshot,actual_cost_amount=EXCLUDED.actual_cost_amount
    """, payload["id"], payload["request_id"], payload["attempt_no"], payload.get("provider_id"),
       payload.get("runtime_model_name"), payload.get("price_version_id"), payload["status"],
       payload.get("http_status"), payload.get("error_code"), payload["prompt_tokens"],
       payload["completion_tokens"], payload["total_tokens"], payload["latency_ms"],
       datetime.fromisoformat(payload["started_at"]), datetime.fromisoformat(payload["completed_at"]),
       json.dumps(payload.get("cost_snapshot") or {}, ensure_ascii=False), Decimal(payload.get("actual_cost_amount") or "0"))


async def safe_record_attempt(request_id, attempt_no, route, status, http_status, error_code, usage, started):
    usage = normalize_usage(usage, route.get("provider_name"))
    latency_ms = int((time.monotonic() - started) * 1000)
    completed_at = datetime.now(timezone.utc)
    if status == "SUCCESS" or usage.get("total_tokens", 0) > 0:
        try:
            attempt_cost, _, cost_components, cache_metrics = calculate_amounts(route.get("price") or {}, usage)
        except HTTPException as exc:
            await record_runtime_pricing_alert(request_id, route, exc)
            raise
    else:
        attempt_cost, cost_components = Decimal("0"), []
        cache_metrics = {"cacheGrossSavings": "0", "cacheWritePremium": "0",
                         "cacheStorageCost": "0", "cacheNetSavings": "0",
                         "cacheHitRate": "0", "costStatus": "COMPLETE"}
    if status != "SUCCESS":
        attempt_cost = Decimal("0")
    price = route.get("price") or {}
    payload = {"id": hashlib.sha256(f"attempt:{request_id}:{attempt_no}".encode()).hexdigest()[:32],
               "request_id": request_id, "attempt_no": attempt_no, "provider_id": route.get("provider_id"),
               "runtime_model_name": route.get("runtime_model_name"), "price_version_id": route.get("price", {}).get("id"),
               "status": status, "http_status": http_status, "error_code": error_code,
               **usage, "latency_ms": latency_ms, "actual_cost_amount": str(attempt_cost),
               "cost_snapshot": {"priceVersionId": price.get("id"), "priceLayer": price.get("price_layer"),
                                 "currency": price.get("currency"), "sourceRef": price.get("source_ref"),
                                 "evidenceHash": price.get("evidence_hash"),
                                 "billingBasis": price.get("cost_billing_basis", "TOKEN"),
                                 "billingQuantity": int(price.get("cost_billing_quantity") or 1_000_000),
                                 "inputUnitPrice": str(price.get("input_cost_unit_price", "0")),
                                 "cacheReadUnitPrice": None if price.get("cache_read_cost_unit_price") is None else str(price.get("cache_read_cost_unit_price")),
                                 "cacheReadMode": price.get("cache_read_mode"),
                                 "cacheWriteUnitPrice": None if price.get("cache_write_cost_unit_price") is None else str(price.get("cache_write_cost_unit_price")),
                                 "cacheWriteMode": price.get("cache_write_mode"),
                                 "outputUnitPrice": str(price.get("output_cost_unit_price", "0")),
                                 "priceComponents": price.get("price_components") or [],
                                 "costComponents": cost_components,
                                 "inputUncachedTokens": usage.get("input_uncached_tokens", 0),
                                 "inputTokensTotal": usage.get("input_tokens_total", 0),
                                 "cacheReadTokens": usage.get("cache_read_tokens", 0),
                                 "cacheWriteTokens": usage.get("cache_write_tokens", 0),
                                 "outputTokens": usage.get("output_tokens", 0),
                                 "reasoningTokens": usage.get("reasoning_tokens", 0),
                                 "usageSource": usage.get("usage_source"),
                                 "usageEvidence": usage.get("usage_evidence") or {},
                                 **cache_metrics},
               "started_at": (completed_at - timedelta(milliseconds=latency_ms)).isoformat(),
               "completed_at": completed_at.isoformat()}
    try:
        await persist_attempt(payload)
        return True
    except Exception:
        try:
            await enqueue_outbox("attempt", payload)
            return True
        except Exception:
            return False


async def persist_usage(payload: Dict[str, Any]):
    assert pool is not None
    await pool.execute("""
      INSERT INTO usage_record(id,request_id,tenant_id,project_id,app_id,api_key_id,model_alias,runtime_model_name,
        provider_id,prompt_tokens,completion_tokens,total_tokens,cost_amount,sales_amount,currency,status,error_code,
        latency_ms,fallback_chain,price_version_id,budget_reserved_amount,budget_currency,budget_status,accounting_status)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24)
      ON CONFLICT(id) DO UPDATE SET status=EXCLUDED.status,error_code=EXCLUDED.error_code,
        prompt_tokens=EXCLUDED.prompt_tokens,completion_tokens=EXCLUDED.completion_tokens,total_tokens=EXCLUDED.total_tokens,
        cost_amount=EXCLUDED.cost_amount,sales_amount=EXCLUDED.sales_amount,latency_ms=EXCLUDED.latency_ms,
        fallback_chain=EXCLUDED.fallback_chain,budget_currency=EXCLUDED.budget_currency,
        budget_status=EXCLUDED.budget_status,accounting_status=EXCLUDED.accounting_status
    """, payload["id"], payload["request_id"], payload.get("tenant_id"), payload.get("project_id"), payload.get("app_id"),
       payload.get("api_key_id"), payload["model_alias"], payload.get("runtime_model_name"), payload.get("provider_id"),
       payload["prompt_tokens"], payload["completion_tokens"], payload["total_tokens"], Decimal(payload["cost_amount"]),
       Decimal(payload["sales_amount"]), payload["currency"], payload["status"], payload.get("error_code"),
       payload["latency_ms"], payload["fallback_chain"], payload["price_version_id"], Decimal(payload["budget_reserved_amount"]),
       payload.get("budget_currency", BUDGET_CURRENCY), payload.get("budget_status", "NOT_APPLICABLE"),
       payload.get("accounting_status", "COMMITTED"))
    await pool.execute("""
      INSERT INTO usage_cost_snapshot(id,request_id,usage_record_id,price_version_id,price_layer,currency,
        billing_basis,billing_quantity,input_unit_price,cache_read_unit_price,cache_read_mode,
        cache_write_unit_price,cache_write_mode,output_unit_price,prompt_tokens,completion_tokens,
        actual_cost_amount,source_ref,cache_read_tokens,cache_write_tokens,reasoning_tokens,price_components,
        cost_components,pricing_model,response_model,provider_instance_id,model_deployment_id,calculator_version,evidence_hash,
        input_uncached_tokens,input_tokens_total,output_tokens,cache_storage_token_seconds,usage_schema_version,
        usage_source,usage_evidence,cache_gross_savings,cache_write_premium,cache_storage_cost,cache_net_savings,
        cache_hit_rate,cost_status)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22::jsonb,$23::jsonb,
        $24,$25,$26,$27,$28,$29,$30,$31,$32,$33,$34,$35,$36::jsonb,$37,$38,$39,$40,$41,$42)
      ON CONFLICT(request_id) DO NOTHING
    """, hashlib.sha256(f"cost:{payload['request_id']}".encode()).hexdigest()[:32], payload["request_id"], payload["id"],
       payload["price_version_id"], payload.get("price_layer") or "PROVIDER_OFFICIAL", payload["currency"],
       payload.get("cost_billing_basis") or "TOKEN", int(payload.get("cost_billing_quantity") or 1_000_000),
       Decimal(payload.get("input_cost_unit_price") or "0"),
       None if payload.get("cache_read_cost_unit_price") is None else Decimal(payload["cache_read_cost_unit_price"]),
       payload.get("cache_read_mode") or "UNKNOWN",
       None if payload.get("cache_write_cost_unit_price") is None else Decimal(payload["cache_write_cost_unit_price"]),
       payload.get("cache_write_mode") or "UNKNOWN",
       Decimal(payload.get("output_cost_unit_price") or "0"), payload["prompt_tokens"], payload["completion_tokens"],
       Decimal(payload["cost_amount"]), payload.get("price_source_ref"), payload.get("cache_read_tokens", 0),
       payload.get("cache_write_tokens", 0), payload.get("reasoning_tokens", 0),
       json.dumps(parse_json_array(payload.get("price_components")), ensure_ascii=False),
       json.dumps(parse_json_array(payload.get("cost_components")), ensure_ascii=False), payload.get("pricing_model"),
       payload.get("response_model"), payload.get("provider_instance_id"), payload.get("model_deployment_id"),
       "3.0.0", payload.get("evidence_hash"), payload.get("input_uncached_tokens", 0),
       payload.get("input_tokens_total", 0), payload.get("output_tokens", 0),
       Decimal(str(payload.get("cache_storage_token_seconds", 0) or 0)),
       int(payload.get("usage_schema_version") or 2), payload.get("usage_source") or "UNKNOWN",
       json.dumps(payload.get("usage_evidence") or {}, ensure_ascii=False),
       Decimal(payload.get("cache_gross_savings") or "0"), Decimal(payload.get("cache_write_premium") or "0"),
       Decimal(payload.get("cache_storage_cost") or "0"), Decimal(payload.get("cache_net_savings") or "0"),
       Decimal(payload.get("cache_hit_rate") or "0"), payload.get("cost_status") or "COMPLETE")


async def finalize_request(request_id, key_ctx, route, model_alias, usage, status, error_code,
                           started, fallback_chain, budget):
    usage = normalize_usage(usage, route.get("provider_name"))
    if status == "SUCCESS" or usage.get("total_tokens", 0) > 0:
        try:
            cost, sales, cost_components, cache_metrics = calculate_amounts(route["price"], usage)
        except HTTPException as exc:
            await record_runtime_pricing_alert(request_id, route, exc)
            raise
    else:
        cost = sales = Decimal("0")
        cost_components = []
        cache_metrics = {"cacheGrossSavings": "0", "cacheWritePremium": "0",
                         "cacheStorageCost": "0", "cacheNetSavings": "0",
                         "cacheHitRate": "0", "costStatus": "COMPLETE"}
    if status != "SUCCESS":
        cost = sales = Decimal("0")
    budget_cost = cost * Decimal(str(route["price"].get("budget_fx_rate") or "1"))
    budget_status = "SETTLED"
    budget_durable = True
    try:
        budget_status = await settle_budget(budget, budget_cost)
        if budget_status == "OVERRUN" and status == "SUCCESS":
            error_code = "TOKENSEA_BUDGET_OVERRUN"
    except Exception:
        budget_status = "PENDING"
        if status == "SUCCESS":
            error_code = "TOKENSEA_BUDGET_SETTLEMENT_PENDING"
        budget["deferred_accounting"] = True
        try:
            await enqueue_outbox("budget_settle", {"reservation": serializable_reservation(budget), "actual": str(budget_cost)})
        except Exception:
            budget_durable = False
    payload = {"id": hashlib.sha256(f"usage:{request_id}".encode()).hexdigest()[:32], "request_id": request_id,
               "tenant_id": key_ctx.get("tenant_id"), "project_id": key_ctx.get("project_id"),
               "app_id": key_ctx.get("app_id"), "api_key_id": key_ctx.get("id"), "model_alias": model_alias,
               "runtime_model_name": route.get("runtime_model_name"), "provider_id": route.get("provider_id"),
               **usage, "cost_amount": str(cost), "sales_amount": str(sales), "currency": route["price"]["currency"],
               "status": status, "error_code": error_code, "latency_ms": int((time.monotonic() - started) * 1000),
               "fallback_chain": json.dumps(fallback_chain, ensure_ascii=False),
               "price_version_id": route["price"]["id"],
               "price_layer": route["price"].get("price_layer") or "PROVIDER_OFFICIAL",
               "cost_billing_basis": route["price"]["cost_billing_basis"],
               "cost_billing_quantity": int(route["price"]["cost_billing_quantity"]),
               "input_cost_unit_price": str(route["price"]["input_cost_unit_price"]),
               "cache_read_cost_unit_price": None if route["price"].get("cache_read_cost_unit_price") is None else str(route["price"]["cache_read_cost_unit_price"]),
               "cache_read_mode": route["price"].get("cache_read_mode") or "UNKNOWN",
               "cache_write_cost_unit_price": None if route["price"].get("cache_write_cost_unit_price") is None else str(route["price"]["cache_write_cost_unit_price"]),
               "cache_write_mode": route["price"].get("cache_write_mode") or "UNKNOWN",
               "output_cost_unit_price": str(route["price"]["output_cost_unit_price"]),
               "price_source_ref": route["price"].get("source_ref") or "price_version:"+str(route["price"]["id"]),
               "price_components": route["price"].get("price_components") or [],
               "cost_components": cost_components,
               "cache_gross_savings": cache_metrics["cacheGrossSavings"],
               "cache_write_premium": cache_metrics["cacheWritePremium"],
               "cache_storage_cost": cache_metrics["cacheStorageCost"],
               "cache_net_savings": cache_metrics["cacheNetSavings"],
               "cache_hit_rate": cache_metrics["cacheHitRate"],
               "cost_status": cache_metrics["costStatus"],
               "evidence_hash": route["price"].get("evidence_hash"),
               "pricing_model": route.get("runtime_model_name"),
               "response_model": usage.get("response_model"),
               "provider_instance_id": route.get("provider_id"),
               "model_deployment_id": route["price"].get("channel_deployment_id"),
               "budget_reserved_amount": str(budget.get("amount") or Decimal("0")),
               "budget_currency": BUDGET_CURRENCY,
               "budget_status": budget_status, "accounting_status": "COMMITTED"}
    try:
        await persist_usage(payload)
        usage_durable = True
    except Exception:
        payload["accounting_status"] = "DEFERRED"
        try:
            await enqueue_outbox("usage", payload)
            usage_durable = True
        except Exception:
            usage_durable = False
    durable = usage_durable and budget_durable
    if durable:
        await complete_request_intent(budget)
    return {"durable": durable, "usage_status": status,
            "budget_status": budget_status, "accounting_status": payload["accounting_status"],
            "error_code": error_code}


def serializable_reservation(reservation: Dict[str, Any]) -> Dict[str, Any]:
    return {key: value for key, value in reservation.items()
            if key in ("state_key", "keys", "amount_micro", "limits", "token", "settled", "released",
                       "deferred_accounting", "overrun")}


async def upsert_db_outbox(event_id: str, kind: str, payload: Dict[str, Any], available_at: datetime):
    if pool is None:
        raise RuntimeError("PostgreSQL is unavailable")
    aggregate_id = str(payload.get("request_id") or payload.get("state_key") or payload.get("id") or event_id)[:100]
    await pool.execute("""
      INSERT INTO accounting_outbox(id,aggregate_type,aggregate_id,event_type,payload,status,available_at)
      VALUES($1,'GatewayAccounting',$2,$3,$4,'PENDING',$5)
      ON CONFLICT(id) DO UPDATE SET payload=EXCLUDED.payload,available_at=LEAST(accounting_outbox.available_at,EXCLUDED.available_at),
        status=CASE WHEN accounting_outbox.status='DONE' THEN 'DONE' ELSE 'PENDING' END,updated_at=now()
    """, event_id, aggregate_id, kind, json.dumps(payload, ensure_ascii=False), available_at)


async def mark_db_outbox_done(event_id: str):
    if pool is not None:
        await pool.execute("UPDATE accounting_outbox SET status='DONE',processed_at=now(),updated_at=now() WHERE id=$1", event_id)


async def fail_db_outbox(event_id: str, attempts: int, payload: Dict[str, Any], error: str):
    if pool is None:
        return
    payload = dict(payload)
    payload["last_error"] = error[:500]
    terminal = attempts >= OUTBOX_MAX_ATTEMPTS
    await pool.execute("""
      UPDATE accounting_outbox SET status=$2,payload=$3,available_at=now()+($4::text||' seconds')::interval,
        updated_at=now() WHERE id=$1
    """, event_id, "DEAD" if terminal else "PENDING", json.dumps(payload, ensure_ascii=False),
       min(300, 2 ** min(attempts, 8)))


async def claim_db_outbox():
    if pool is None:
        return None
    return await pool.fetchrow("""
      WITH candidate AS (
        SELECT id FROM accounting_outbox WHERE (status='PENDING' AND available_at<=now())
          OR (status='PROCESSING' AND updated_at<now()-interval '5 minutes')
        ORDER BY available_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1
      )
      UPDATE accounting_outbox o SET status='PROCESSING',attempts=o.attempts+1,updated_at=now()
      FROM candidate WHERE o.id=candidate.id
      RETURNING o.id,o.event_type,o.payload,o.attempts
    """)


async def enqueue_outbox(kind: str, payload: Dict[str, Any], event_id: Optional[str] = None,
                         available_at_epoch: Optional[float] = None,
                         require_db_and_wal: bool = False):
    stable = json.dumps({"kind": kind, "payload": payload}, ensure_ascii=False, sort_keys=True,
                        separators=(",", ":"))
    event_id = event_id or hashlib.sha256(stable.encode()).hexdigest()
    available_epoch = available_at_epoch or time.time()
    db_durable = False
    try:
        await upsert_db_outbox(event_id, kind, payload, datetime.fromtimestamp(available_epoch, timezone.utc))
        db_durable = True
    except Exception:
        pass
    wal_durable = False
    try:
        await asyncio.to_thread(wal_put, kind, payload, event_id, available_epoch)
        wal_durable = True
    except Exception:
        pass
    if require_db_and_wal and not (db_durable and wal_durable):
        raise RuntimeError("request intent requires PostgreSQL and WAL durability")
    if not db_durable and not wal_durable:
        raise RuntimeError("accounting outbox is not durable")
    envelope = {"id": event_id, "kind": kind, "payload": payload, "created_at": utc_timestamp()}
    try:
        if cache is not None:
            await cache.lpush("ts:outbox:gateway", json.dumps(envelope, ensure_ascii=False))
    except Exception:
        pass
    return event_id


async def activate_recovered_request_intents():
    """Make intents inherited from a previous process immediately recoverable.

    Live requests use a future available_at so the outbox worker cannot race their
    finalizer. At process startup there are no live requests yet, therefore every
    persisted request_intent is abandoned and must be released/accounted now.
    """
    now_epoch = time.time()
    with wal_lock:
        recovered = [(event_id, dict(event)) for event_id, event in wal_pending.items()
                     if event.get("kind") == "request_intent"]
    for event_id, event in recovered:
        payload = event.get("payload") or {}
        await upsert_db_outbox(event_id, "request_intent", payload,
                               datetime.fromtimestamp(now_epoch, timezone.utc))
        event["available_at"] = now_epoch
        await asyncio.to_thread(wal_replace, event_id, event)
    if pool is not None:
        await pool.execute("""
          UPDATE accounting_outbox SET status='PENDING',available_at=now(),updated_at=now()
          WHERE event_type='request_intent' AND status IN ('PENDING','PROCESSING')
        """)


async def process_outbox(envelope: Dict[str, Any]):
    kind, payload = envelope["kind"], envelope["payload"]
    if kind == "attempt":
        await persist_attempt(payload)
    elif kind == "usage":
        committed = dict(payload)
        committed["accounting_status"] = "COMMITTED"
        await persist_usage(committed)
    elif kind == "budget_release":
        await release_budget(payload)
    elif kind == "budget_settle":
        await settle_budget(payload["reservation"], Decimal(payload["actual"]))
    elif kind == "request_intent":
        assert pool is not None
        if await pool.fetchval("SELECT 1 FROM usage_record WHERE id=$1 AND accounting_status='COMMITTED'", payload["usage_id"]):
            return
        await release_budget(payload["reservation"])
        await persist_usage({"id": payload["usage_id"], "request_id": payload["request_id"],
            "tenant_id": payload.get("tenant_id"), "project_id": payload.get("project_id"),
            "app_id": payload.get("app_id"), "api_key_id": payload.get("api_key_id"),
            "model_alias": payload["model_alias"], "runtime_model_name": payload.get("runtime_model_name"),
            "provider_id": payload.get("provider_id"), "prompt_tokens": 0, "completion_tokens": 0,
            "total_tokens": 0, "cost_amount": "0", "sales_amount": "0", "currency": payload["currency"],
            "status": "FAILED", "error_code": "TOKENSEA_ACCOUNTING_RECOVERED_INCOMPLETE",
            "latency_ms": 0, "fallback_chain": "[]", "price_version_id": payload["price_version_id"],
            "budget_reserved_amount": "0", "budget_status": "RELEASED", "accounting_status": "COMMITTED"})
    else:
        raise ValueError("unknown outbox kind")


async def process_db_outbox_once() -> bool:
    claimed = await claim_db_outbox()
    if not claimed:
        return False
    event_id = claimed["id"]
    payload = json.loads(claimed["payload"])
    envelope = {"kind": claimed["event_type"], "payload": payload}
    try:
        await process_outbox(envelope)
        await mark_db_outbox_done(event_id)
        await asyncio.to_thread(wal_ack, event_id)
    except Exception as exc:
        await fail_db_outbox(event_id, claimed["attempts"], payload, str(exc))
    return True


async def process_wal_outbox_once(now: Optional[float] = None) -> bool:
    now = now or time.time()
    with wal_lock:
        pending = list(wal_pending.items())
    due = [(event_id, event) for event_id, event in pending if float(event.get("available_at", 0)) <= now]
    if not due:
        return False
    event_id, envelope = due[0]
    try:
        await process_outbox(envelope)
        await asyncio.to_thread(wal_ack, event_id)
        await mark_db_outbox_done(event_id)
    except Exception as exc:
        updated = dict(envelope)
        attempts = int(updated.get("attempts", 0)) + 1
        updated["attempts"] = attempts
        updated["last_error"] = str(exc)[:500]
        updated["available_at"] = now + min(300, 2 ** min(attempts, 8))
        if attempts >= OUTBOX_MAX_ATTEMPTS:
            await asyncio.to_thread(wal_dead_letter, event_id, updated)
            await fail_db_outbox(event_id, attempts, updated.get("payload", {}), str(exc))
        else:
            await asyncio.to_thread(wal_replace, event_id, updated)
    return True


async def outbox_worker():
    while True:
        try:
            if await process_db_outbox_once():
                continue
            if await process_wal_outbox_once():
                continue
            await asyncio.sleep(0.25)
        except asyncio.CancelledError:
            raise
        except Exception:
            await asyncio.sleep(1)


async def resolve_provider_secret(instance: Any):
    ref = instance["credential_ref"]
    if ref and ref.startswith("env:"):
        raise gateway_error(503, "TOKENSEA_SECRET_REF_UNSUPPORTED", "不允许环境变量凭据引用")
    assert pool is not None
    secret = None
    if ref:
        secret_id = ref[7:] if ref.startswith("secret:") else ref
        secret = await pool.fetchrow("""
          SELECT id,secret_cipher,updated_at FROM provider_secret
          WHERE id=$1 AND provider_instance_id=$2 AND status='ACTIVE'
        """, secret_id, instance["id"])
    else:
        secret = await pool.fetchrow("""
          SELECT id,secret_cipher,updated_at FROM provider_secret
          WHERE provider_instance_id=$1 AND status='ACTIVE' ORDER BY updated_at DESC LIMIT 1
        """, instance["id"])
    if not secret:
        return None, "none"
    return decrypt_secret(secret["secret_cipher"]), f"{secret['id']}:{secret['updated_at'].isoformat()}"


def snapshot_addresses(raw: Any) -> set[str]:
    if not isinstance(raw, str) or not raw.strip():
        return set()
    return {value.strip() for value in raw.split(",") if value.strip()}


def is_local_test_upstream(host: str) -> bool:
    return LOCAL_TEST_UPSTREAM_ENABLED and host.lower().rstrip(".") in LOCAL_TEST_UPSTREAM_HOSTS


def validate_public_addresses(addresses: set[str]):
    if not addresses:
        raise gateway_error(503, "TOKENSEA_DNS_SNAPSHOT_MISSING", "供应商 DNS 快照缺失")
    try:
        parsed = [ipaddress.ip_address(value.split("%", 1)[0]) for value in addresses]
    except ValueError:
        raise gateway_error(503, "TOKENSEA_DNS_SNAPSHOT_INVALID", "供应商 DNS 快照无效")
    if any(not address.is_global for address in parsed):
        raise gateway_error(503, "TOKENSEA_SSRF_TARGET_REJECTED", "供应商地址指向非公网、链路本地或保留地址")


async def resolve_dns_addresses(host: str) -> set[str]:
    loop = asyncio.get_running_loop()
    try:
        records = await loop.run_in_executor(None, lambda: socket.getaddrinfo(host, None, type=socket.SOCK_STREAM))
    except socket.gaierror:
        raise gateway_error(503, "TOKENSEA_DNS_RESOLUTION_FAILED", "供应商主机无法解析")
    return {record[4][0].split("%", 1)[0] for record in records}


async def validate_route_dns(route: Dict[str, Any], force: bool = False):
    parsed = urlparse(route.get("api_base") or "")
    if parsed.scheme.lower() not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise gateway_error(503, "TOKENSEA_SSRF_TARGET_REJECTED", "供应商 API Base 格式不安全")
    host = parsed.hostname.lower().rstrip(".")
    port = parsed.port or (443 if parsed.scheme.lower() == "https" else 80)
    verified_host = str(route.get("verified_host") or "").lower().rstrip(".")
    if not verified_host or host != verified_host:
        raise gateway_error(503, "TOKENSEA_DNS_HOST_CHANGED", "供应商主机与最近连接测试不一致，请重新连接测试")
    verified_port = route.get("verified_port")
    if verified_port is not None and int(verified_port) != port:
        raise gateway_error(503, "TOKENSEA_DNS_PORT_CHANGED", "供应商端口与最近连接测试不一致，请重新连接测试")
    cache_key = f"{route.get('provider_id') or host}:{host}:{port}"
    cached = dns_validation_cache.get(cache_key)
    if not force and cached and cached.get("expires_at", 0) > time.monotonic():
        return
    expected = snapshot_addresses(route.get("verified_addresses"))
    if is_local_test_upstream(host):
        if not expected:
            raise gateway_error(503, "TOKENSEA_DNS_SNAPSHOT_MISSING", "本地测试上游地址快照缺失")
        expires_at = time.monotonic() + DNS_RECHECK_TTL
        dns_validation_cache[cache_key] = {"addresses": expected, "expires_at": expires_at}
        route["dns_valid_until"] = expires_at
        return
    validate_public_addresses(expected)
    current = await resolve_dns_addresses(host)
    validate_public_addresses(current)
    if current != expected:
        LOGGER.info(
            "dns_public_rotation_accepted provider_id=%s host=%s previous=%s current=%s",
            route.get("provider_id"), host, sorted(expected), sorted(current),
        )
    expires_at = time.monotonic() + DNS_RECHECK_TTL
    dns_validation_cache[cache_key] = {"addresses": current, "expires_at": expires_at}
    route["dns_valid_until"] = expires_at


async def ensure_runtime_model(route: Dict[str, Any], force: bool = False):
    # This closes the control-plane-to-gateway rebinding window. LiteLLM still
    # resolves the hostname independently, so production needs an egress proxy
    # with destination pinning for complete second-resolution enforcement.
    await validate_route_dns(route, force=force)
    fingerprint = hashlib.sha256(json.dumps({"model": route["runtime_model_name"], "base": route["api_base"],
                                             "secret": route["secret_version"]}, sort_keys=True).encode()).hexdigest()
    runtime_alias = "ts-" + hashlib.sha256((route["deployment_id"] + fingerprint).encode()).hexdigest()[:24]
    route["runtime_alias"] = runtime_alias
    current = runtime_models.get(route["deployment_id"])
    if not force and current and current["fingerprint"] == fingerprint and current["expires_at"] > time.monotonic():
        return
    async with runtime_model_lock:
        current = runtime_models.get(route["deployment_id"])
        if not force and current and current["fingerprint"] == fingerprint and current["expires_at"] > time.monotonic():
            return
        payload = {"model_name": runtime_alias,
                   "litellm_params": {"model": route["runtime_model_name"], "api_base": route["api_base"]},
                   "model_info": {"id": runtime_alias}}
        if route.get("api_key"):
            payload["litellm_params"]["api_key"] = route["api_key"]
        async with httpx.AsyncClient(timeout=20, trust_env=False) as client:
            response = await client.post(f"{ENGINE_URL}/model/new", headers=runtime_headers(), json=payload)
            if response.status_code not in (200, 201):
                if not await runtime_model_exists(client, runtime_alias):
                    if response.status_code == 409:
                        raise gateway_error(503, "TOKENSEA_RUNTIME_CONFLICT", "运行时模型注册冲突")
                    raise gateway_error(503, "TOKENSEA_RUNTIME_CONFIG_FAILED", "运行时拒绝模型配置")
            if current and current.get("runtime_alias") != runtime_alias:
                await client.post(f"{ENGINE_URL}/model/delete", headers=runtime_headers(), json={"id": current["runtime_alias"]})
        runtime_models[route["deployment_id"]] = {"fingerprint": fingerprint, "runtime_alias": runtime_alias,
                                                   "expires_at": time.monotonic() + REGISTRATION_TTL}


async def runtime_model_exists(client: httpx.AsyncClient, alias: str) -> bool:
    def contains(value: Any) -> bool:
        if not isinstance(value, dict):
            return False
        for item in value.get("data", []):
            if not isinstance(item, dict):
                continue
            model_info = item.get("model_info") if isinstance(item.get("model_info"), dict) else {}
            if item.get("model_name") == alias or item.get("id") == alias or model_info.get("id") == alias:
                return True
        return False

    try:
        for params in ({"modelId": alias, "size": 10}, {"search": alias, "size": 100}):
            response = await client.get(f"{ENGINE_URL}/v2/model/info", headers=runtime_headers(), params=params)
            if response.status_code != 200:
                continue
            value = response.json()
            if contains(value):
                return True
            if "modelId" in params and isinstance(value, dict) and isinstance(value.get("data"), list) and value["data"]:
                return True
        page = 1
        while page <= 100:
            response = await client.get(
                f"{ENGINE_URL}/v2/model/info", headers=runtime_headers(), params={"page": page, "size": 100}
            )
            if response.status_code != 200:
                return False
            value = response.json()
            if contains(value):
                return True
            total_pages = int(value.get("total_pages") or 1) if isinstance(value, dict) else 1
            if page >= total_pages:
                return False
            page += 1
        return False
    except Exception:
        return False


def invalidate_runtime_model(route):
    runtime_models.pop(route["deployment_id"], None)


def decrypt_secret(encoded: str) -> str:
    if not CRYPTO_KEY:
        raise gateway_error(503, "TOKENSEA_SECRET_STORE_NOT_CONFIGURED", "密钥存储未配置")
    try:
        primary_key = decode_crypto_key(CRYPTO_KEY)
        parts = encoded.split(".")
        if len(parts) == 3 and parts[0] == "v2":
            return decrypt_aes_gcm(primary_key, parts[1], parts[2])
        if len(parts) != 2:
            raise ValueError("invalid ciphertext format")
        legacy_key = CRYPTO_KEY.encode("utf-8")[:32].ljust(32, b"\0")
        try:
            return decrypt_aes_gcm(legacy_key, parts[0], parts[1])
        except Exception:
            return decrypt_aes_gcm(primary_key, parts[0], parts[1])
    except Exception:
        raise gateway_error(503, "TOKENSEA_SECRET_DECRYPT_FAILED", "供应商密钥不可用")


def decode_crypto_key(value: str) -> bytes:
    if not value or not value.strip():
        raise ValueError("crypto key is required")
    padded = value + "=" * ((4 - len(value) % 4) % 4)
    try:
        decoded = base64.b64decode(padded, validate=True)
    except Exception:
        decoded = base64.urlsafe_b64decode(padded)
    if len(decoded) != 32:
        raise ValueError("crypto key must decode to 32 bytes")
    return decoded


def decrypt_aes_gcm(key: bytes, iv_text: str, cipher_text: str) -> str:
    iv = base64.b64decode(iv_text, validate=True)
    encrypted = base64.b64decode(cipher_text, validate=True)
    if len(iv) != 12:
        raise ValueError("AES-GCM IV must be 12 bytes")
    return AESGCM(key).decrypt(iv, encrypted, None).decode("utf-8")


def provider_is_routable(instance: Any) -> bool:
    if not instance or not instance["api_base"] or str(instance["status"]).upper() not in ACTIVE_VALUES:
        return False
    tested_at = instance.get("last_connection_test_at") if isinstance(instance, dict) else instance["last_connection_test_at"]
    return bool(instance["health_status"] == "健康" and instance["last_connection_test_status"] == "成功"
                and tested_at and tested_at.timestamp() > time.time() - CONNECTION_TEST_MAX_AGE_SECONDS
                and instance["last_connection_test_host"] and instance["last_connection_test_addresses"]
                and instance["key_status"] in ("已托管", "无需 Key"))


def validate_visibility(raw: Any, ctx: Dict[str, Any]):
    if raw in ("全部租户", "ALL", "*"):
        return
    if raw == "内部租户" and str(ctx.get("tenant_type")).upper() == "INTERNAL":
        return
    try:
        values = json.loads(raw) if isinstance(raw, str) and raw.strip().startswith("[") else [raw]
    except Exception:
        values = []
    if isinstance(values, list):
        if any(value in ("全部租户", "ALL", "*") for value in values):
            return
        if "内部租户" in values and str(ctx.get("tenant_type")).upper() == "INTERNAL":
            return
        if ctx["tenant_id"] in values or ctx.get("tenant_type") in values:
            return
    raise gateway_error(403, "TOKENSEA_MODEL_NOT_VISIBLE", "模型对当前租户不可见")


def validate_ip_whitelist(raw: Any, source_ip: str):
    try:
        values = json.loads(raw) if isinstance(raw, str) else raw
    except Exception:
        raise gateway_error(403, "TOKENSEA_IP_POLICY_INVALID", "IP 白名单配置无效")
    if values in (None, []):
        return
    if not isinstance(values, list) or any(not isinstance(v, str) for v in values):
        raise gateway_error(403, "TOKENSEA_IP_POLICY_INVALID", "IP 白名单配置无效")
    try:
        address = ipaddress.ip_address(source_ip)
        if any(address in ipaddress.ip_network(value, strict=False) for value in values):
            return
    except ValueError:
        raise gateway_error(403, "TOKENSEA_IP_POLICY_INVALID", "IP 白名单配置无效")
    raise gateway_error(403, "TOKENSEA_IP_FORBIDDEN", "来源 IP 不在白名单")


def request_ip(request: Request) -> str:
    direct = request.client.host if request.client else "0.0.0.0"
    if TRUSTED_PROXY_CIDRS:
        try:
            address = ipaddress.ip_address(direct)
            trusted = any(address in ipaddress.ip_network(cidr.strip(), strict=False) for cidr in TRUSTED_PROXY_CIDRS.split(",") if cidr.strip())
            if trusted and request.headers.get("x-forwarded-for"):
                return request.headers["x-forwarded-for"].split(",", 1)[0].strip()
        except ValueError:
            pass
    return direct


def parse_scope(raw: Any) -> List[str]:
    values = strict_string_list(raw)
    if not values:
        raise gateway_error(403, "TOKENSEA_SCOPE_EMPTY", "模型权限范围为空或无效")
    return list(dict.fromkeys(values))


def scope_allows(scope: List[str], alias: str) -> bool:
    return "*" in scope or alias in scope


def strict_string_list(raw: Any) -> List[str]:
    try:
        value = json.loads(raw) if isinstance(raw, str) else raw
    except Exception:
        return []
    if not isinstance(value, list) or any(not isinstance(v, str) or not v.strip() for v in value):
        return []
    return [v.strip() for v in value]


def strict_object(raw: Any, code: str) -> Dict[str, Any]:
    try:
        value = json.loads(raw) if isinstance(raw, str) else raw
    except Exception:
        raise gateway_error(503, code, "JSON 配置无效")
    if not isinstance(value, dict):
        raise gateway_error(503, code, "JSON 配置无效")
    return value


def ordered_routes(routes: List[Dict[str, Any]], strategy: Any) -> List[Dict[str, Any]]:
    groups: Dict[int, List[Dict[str, Any]]] = {}
    for route in routes:
        groups.setdefault(route["priority"], []).append(route)
    result = []
    for priority in sorted(groups):
        group = groups[priority]
        if str(strategy).lower() in ("weighted", "weight", "加权") and len(group) > 1:
            remaining = list(group)
            while remaining:
                chosen = random.choices(remaining, weights=[v["weight"] for v in remaining], k=1)[0]
                result.append(chosen)
                remaining.remove(chosen)
        else:
            result.extend(sorted(group, key=lambda value: -value["weight"]))
    return result


def runtime_model_name(instance: Any, actual_model: str) -> str:
    if "/" in actual_model:
        return actual_model
    style = (instance["api_style"] or "").lower()
    prefix = "anthropic" if "anthropic" in style else "gemini" if "gemini" in style else "azure" if "azure" in style else "openai"
    return f"{prefix}/{actual_model}"


def estimate_reserved_tokens(body: Dict[str, Any]) -> int:
    material = {key: value for key, value in body.items() if key not in ("model", "stream", "stream_options")}
    input_upper_bound = max(1, len(json.dumps(material, ensure_ascii=False).encode("utf-8")))
    requested = body.get("max_output_tokens", body.get("max_tokens", DEFAULT_OUTPUT_RESERVATION))
    try:
        output = bounded_int(requested, 1, 1_000_000)
    except HTTPException:
        output = DEFAULT_OUTPUT_RESERVATION
    return input_upper_bound + output


def component_usage_count(component_type: str, basis: str,
                          token_counts: Dict[str, int], usage: Dict[str, Any]) -> Decimal:
    if basis == "TOKEN":
        return Decimal(token_counts.get(component_type, 0))
    if basis == "TOKEN_SECOND":
        return Decimal(str(usage.get("cache_storage_token_seconds", 0) or 0))
    if basis == "REQUEST":
        return Decimal(1)
    if basis == "IMAGE":
        field = "output_images" if "OUTPUT" in component_type else "input_images"
        return Decimal(usage.get(field, usage.get("image_count", 0)) or 0)
    if basis == "SECOND":
        field = "video_seconds" if "VIDEO" in component_type else "audio_seconds" if "AUDIO" in component_type else "duration_seconds"
        return Decimal(str(usage.get(field, 0) or 0))
    if basis == "MINUTE":
        return Decimal(str(usage.get("duration_minutes", 0) or 0))
    if basis == "CHARACTER":
        field = "output_characters" if "OUTPUT" in component_type else "input_characters"
        return Decimal(usage.get(field, 0) or 0)
    if basis == "AUDIO_MINUTE":
        return Decimal(str(usage.get("audio_minutes", 0) or 0))
    raise gateway_error(503, "TOKENSEA_PRICE_INVALID", f"不支持的计费对象：{basis}")


def primary_usage_counts(basis: str, usage: Dict[str, Any]) -> tuple[Decimal, Decimal]:
    if basis == "TOKEN":
        return Decimal(usage["input_tokens_total"]), Decimal(usage["completion_tokens"])
    if basis == "REQUEST":
        return Decimal(1), Decimal(1)
    if basis == "IMAGE":
        return Decimal(usage.get("input_images", usage.get("image_count", 0)) or 0), \
               Decimal(usage.get("output_images", 0) or 0)
    if basis == "SECOND":
        return Decimal(str(usage.get("input_seconds", usage.get("duration_seconds", 0)) or 0)), \
               Decimal(str(usage.get("output_seconds", 0) or 0))
    if basis == "MINUTE":
        return Decimal(str(usage.get("input_minutes", usage.get("duration_minutes", 0)) or 0)), \
               Decimal(str(usage.get("output_minutes", 0) or 0))
    if basis == "CHARACTER":
        return Decimal(usage.get("input_characters", 0) or 0), Decimal(usage.get("output_characters", 0) or 0)
    if basis == "AUDIO_MINUTE":
        return Decimal(str(usage.get("audio_minutes", 0) or 0)), Decimal(0)
    raise gateway_error(503, "TOKENSEA_PRICE_INVALID", f"不支持的计费对象：{basis}")


def component_scope_matches(component: Dict[str, Any], price: Dict[str, Any], usage: Dict[str, Any]) -> bool:
    scope = component.get("scope") if isinstance(component.get("scope"), dict) else {}
    context_tokens = int(usage.get("input_tokens_total", 0) or 0)
    if scope.get("minContextTokens") is not None and context_tokens < int(scope["minContextTokens"]):
        return False
    if scope.get("maxContextTokens") is not None and context_tokens > int(scope["maxContextTokens"]):
        return False
    if scope.get("cacheTtlSeconds") is not None and int(usage.get("cache_ttl_seconds", 0) or 0) != int(scope["cacheTtlSeconds"]):
        return False
    for scope_key, price_key in (("serviceTier", "service_tier"), ("requestMode", "request_mode"),
                                 ("contextTier", "context_tier")):
        expected = scope.get(scope_key)
        if expected is not None and str(expected).upper() != str(price.get(price_key) or "DEFAULT").upper():
            return False
    return True


def select_price_component(component_type: str, components: List[Dict[str, Any]],
                           price: Dict[str, Any], usage: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    matches = [component for component in components
               if str(component.get("componentType") or "").upper() == component_type
               and component_scope_matches(component, price, usage)]
    if not matches:
        return None
    ranked = sorted(matches, key=lambda item: (-len(item.get("scope") or {}), int(item.get("priority") or 100),
                                                str(item.get("variant") or "DEFAULT")))
    best = ranked[0]
    if len(ranked) > 1:
        best_rank = (len(best.get("scope") or {}), int(best.get("priority") or 100))
        next_rank = (len(ranked[1].get("scope") or {}), int(ranked[1].get("priority") or 100))
        if best_rank == next_rank:
            raise gateway_error(503, "TOKENSEA_CACHE_COMPONENT_AMBIGUOUS",
                                f"价格组件 {component_type} 在当前请求作用域匹配到多个同优先级变体")
    return best


def component_price(component: Dict[str, Any], input_component: Optional[Dict[str, Any]] = None) -> Decimal:
    mode = str(component.get("mode") or component.get("componentMode") or "EXPLICIT").upper()
    if mode == "INHERIT_INPUT":
        if not input_component or input_component.get("unitPrice") is None:
            raise gateway_error(503, "TOKENSEA_PRICE_INVALID", "沿用普通输入价的组件缺少输入价格")
        return Decimal(str(input_component["unitPrice"]))
    if mode == "EXPLICIT_ZERO":
        return Decimal("0")
    if component.get("unitPrice") is None:
        raise gateway_error(503, "TOKENSEA_PRICE_INVALID", "明确价格组件缺少单价")
    return Decimal(str(component["unitPrice"]))


def calculate_amounts(price: Dict[str, Any], usage: Dict[str, Any]):
    usage = normalize_usage(usage)
    if usage.get("cost_status") in {"INVALID_USAGE", "INCOMPLETE_USAGE"}:
        code = "TOKENSEA_CACHE_USAGE_INCONSISTENT" if usage.get("cost_status") == "INVALID_USAGE" \
            else "TOKENSEA_CACHE_USAGE_UNRECOGNIZED"
        raise gateway_error(503, code, "上游缓存 Usage 无法形成可靠的互斥计费数量")
    components = parse_json_array(price.get("price_components"))
    if not components:
        raise gateway_error(503, "TOKENSEA_PRICE_INVALID", "价格版本缺少组件数组")
    token_counts = {
        "INPUT_TOKEN": usage["input_uncached_tokens"],
        "OUTPUT_TOKEN": usage["output_tokens"],
        "CACHE_READ_TOKEN": usage.get("cache_read_tokens", 0),
        "CACHE_WRITE_TOKEN": usage.get("cache_write_tokens", 0),
        "CACHE_STORAGE_TOKEN_SECOND": usage.get("cache_storage_token_seconds", 0),
        "REASONING_TOKEN": usage.get("reasoning_tokens", 0),
    }
    input_component = select_price_component("INPUT_TOKEN", components, price, usage)
    input_related_tokens = (usage["input_uncached_tokens"] + usage.get("cache_read_tokens", 0)
                            + usage.get("cache_write_tokens", 0))
    if input_component is None and input_related_tokens > 0:
        raise gateway_error(503, "TOKENSEA_PRICE_INVALID", "价格版本缺少普通输入组件")
    component_types = sorted({str(item.get("componentType") or "").upper() for item in components})
    cost_components: List[Dict[str, Any]] = []
    component_amounts: Dict[str, Decimal] = {}
    cost = Decimal("0")
    for component_type in component_types:
        component = select_price_component(component_type, components, price, usage)
        if component is None:
            continue
        basis = str(component.get("unitBasis") or "TOKEN").upper()
        quantity = int(component.get("unitQuantity") or 1)
        if quantity <= 0:
            raise gateway_error(503, "TOKENSEA_PRICE_INVALID", "计费基数必须大于零")
        count = component_usage_count(component_type, basis, token_counts, usage)
        if count <= 0:
            continue
        mode = str(component.get("mode") or component.get("componentMode") or "EXPLICIT").upper()
        if mode == "UNKNOWN":
            raise gateway_error(503, "TOKENSEA_CACHE_PRICE_MISSING", f"价格组件 {component_type} 尚未确认")
        if mode == "NOT_APPLICABLE":
            raise gateway_error(503, "TOKENSEA_CACHE_USAGE_UNRECOGNIZED",
                                f"上游返回了 {component_type} 用量，但价格版本标记为不适用")
        unit_price = component_price(component, input_component)
        amount = count * unit_price / Decimal(quantity)
        cost += amount
        component_amounts[component_type] = amount
        cost_components.append({
            "componentType": component_type,
            "variant": str(component.get("variant") or "DEFAULT"),
            "mode": mode,
            "usageQuantity": str(count),
            "unitPrice": str(unit_price),
            "unitBasis": basis,
            "unitQuantity": quantity,
            "amount": str(amount),
            "scope": component.get("scope") or {},
            "sourceRef": component.get("sourceRef") or price.get("source_ref"),
        })
    for required_type in ("CACHE_READ_TOKEN", "CACHE_WRITE_TOKEN"):
        if Decimal(token_counts.get(required_type, 0)) > 0 and required_type not in component_amounts:
            raise gateway_error(503, "TOKENSEA_CACHE_PRICE_MISSING", f"价格版本缺少 {required_type} 组件")
    input_price = component_price(input_component) if input_component else Decimal("0")
    input_quantity = Decimal(int(input_component.get("unitQuantity") or 1)) if input_component else Decimal("1")
    hypothetical_cache_read = Decimal(usage.get("cache_read_tokens", 0)) * input_price / input_quantity
    cache_read_cost = component_amounts.get("CACHE_READ_TOKEN", Decimal("0"))
    cache_write_cost = component_amounts.get("CACHE_WRITE_TOKEN", Decimal("0"))
    hypothetical_cache_write = Decimal(usage.get("cache_write_tokens", 0)) * input_price / input_quantity
    cache_gross_savings = hypothetical_cache_read - cache_read_cost
    cache_write_premium = max(Decimal("0"), cache_write_cost - hypothetical_cache_write)
    cache_storage_cost = component_amounts.get("CACHE_STORAGE_TOKEN_SECOND", Decimal("0"))
    cache_net_savings = cache_gross_savings - cache_write_premium - cache_storage_cost
    cache_denominator = usage["input_uncached_tokens"] + usage.get("cache_read_tokens", 0)
    cache_hit_rate = (Decimal(usage.get("cache_read_tokens", 0)) / Decimal(cache_denominator)) \
        if cache_denominator > 0 else Decimal("0")
    cache_metrics = {
        "cacheGrossSavings": str(cache_gross_savings),
        "cacheWritePremium": str(cache_write_premium),
        "cacheStorageCost": str(cache_storage_cost),
        "cacheNetSavings": str(cache_net_savings),
        "cacheHitRate": str(cache_hit_rate),
        "costStatus": "COMPLETE",
    }
    if not price.get("internal_price_id"):
        sales = cost
    else:
        price_basis = str(price["price_billing_basis"])
        price_quantity = Decimal(int(price["price_billing_quantity"]))
        input_count, output_count = primary_usage_counts(price_basis, usage)
        sales = (input_count * Decimal(str(price["input_price_unit_price"])) +
                 output_count * Decimal(str(price["output_price_unit_price"]))) / price_quantity
    return cost, sales, cost_components, cache_metrics


def money_micro(value: Decimal) -> int:
    return int((value * Decimal(1_000_000)).to_integral_value(rounding=ROUND_CEILING))


def extract_usage(data: Any, provider_type: Optional[str] = None) -> Dict[str, Any]:
    if isinstance(data, dict):
        response = data.get("response") if isinstance(data.get("response"), dict) else data
        raw_usage = response.get("usage") if isinstance(response.get("usage"), dict) else None
        if raw_usage is None and isinstance(response.get("usageMetadata"), dict):
            raw_usage = response["usageMetadata"]
        if raw_usage is not None:
            normalized = normalize_usage(raw_usage, provider_type)
            normalized["response_model"] = response.get("model") or data.get("model")
            return normalized
        if response is not data:
            return extract_usage(response, provider_type)
    return empty_usage()


def usage_from_sse_line(line: bytes, provider_type: Optional[str] = None) -> Dict[str, Any]:
    if not line.startswith(b"data:"):
        return empty_usage()
    raw = line[5:].strip()
    if not raw or raw == b"[DONE]":
        return empty_usage()
    try:
        return extract_usage(json.loads(raw), provider_type)
    except Exception:
        return empty_usage()


def merge_usage(target, incoming):
    if incoming.get("response_model"):
        target["response_model"] = incoming["response_model"]
    if incoming.get("total_tokens", 0) >= target.get("total_tokens", 0) and incoming.get("total_tokens", 0) > 0:
        target.update(incoming)


def usage_value(usage: Dict[str, Any], *names: str, default: Any = 0) -> Any:
    for name in names:
        if name in usage and usage[name] is not None:
            return usage[name]
    return default


def usage_has_negative(usage: Dict[str, Any], names: List[str]) -> bool:
    for name in names:
        if name not in usage or usage[name] is None:
            continue
        try:
            if Decimal(str(usage[name])) < 0:
                return True
        except Exception:
            return True
    return False


def usage_evidence(usage: Dict[str, Any]) -> Dict[str, Any]:
    try:
        return json.loads(json.dumps(usage, ensure_ascii=False, default=str))
    except Exception:
        return {"unserializable": True}


def complete_standard_usage(usage: Dict[str, Any], source: str,
                            input_uncached: int, cache_read: int, cache_write: int,
                            completion_total: int, reasoning: int,
                            input_total: Optional[int] = None,
                            raw_total: Optional[int] = None,
                            invalid: bool = False,
                            incomplete: bool = False) -> Dict[str, Any]:
    input_total = input_uncached + cache_read + cache_write if input_total is None else input_total
    output_tokens = max(0, completion_total - reasoning)
    expected_input = input_uncached + cache_read + cache_write
    invalid = invalid or input_total != expected_input or reasoning > completion_total
    computed_total = input_total + completion_total
    total_tokens = raw_total if raw_total is not None and raw_total > 0 else computed_total
    if total_tokens < computed_total:
        invalid = True
    image_count = nonnegative_int(usage_value(usage, "image_count", "images"))
    input_images = nonnegative_int(usage_value(usage, "input_images", default=image_count))
    output_images = nonnegative_int(usage_value(usage, "output_images"))
    duration_seconds = nonnegative_number(usage_value(usage, "duration_seconds"))
    audio_seconds = nonnegative_number(usage_value(usage, "audio_seconds", default=duration_seconds))
    video_seconds = nonnegative_number(usage_value(usage, "video_seconds", default=duration_seconds))
    duration_minutes = nonnegative_number(usage_value(usage, "duration_minutes", default=duration_seconds / 60))
    audio_minutes = nonnegative_number(usage_value(usage, "audio_minutes", default=audio_seconds / 60))
    cost_status = "INVALID_USAGE" if invalid else "INCOMPLETE_USAGE" if incomplete else "COMPLETE"
    return {
        "usage_schema_version": 2,
        "usage_source": source,
        "usage_evidence": usage_evidence(usage),
        "cost_status": cost_status,
        "input_uncached_tokens": input_uncached,
        "input_tokens_total": input_total,
        "prompt_tokens": input_total,
        "cache_read_tokens": cache_read,
        "cache_write_tokens": cache_write,
        "completion_tokens": completion_total,
        "output_tokens": output_tokens,
        "reasoning_tokens": reasoning,
        "total_tokens": total_tokens,
        "cache_storage_token_seconds": nonnegative_number(
            usage_value(usage, "cache_storage_token_seconds", "cacheStorageTokenSeconds")),
        "cache_ttl_seconds": nonnegative_int(usage_value(usage, "cache_ttl_seconds", "cacheTtlSeconds")),
        "cache_read_in_prompt": False,
        "cache_write_in_prompt": False,
        "reasoning_in_completion": False,
        "image_count": image_count,
        "input_images": input_images,
        "output_images": output_images,
        "duration_seconds": duration_seconds,
        "input_seconds": nonnegative_number(usage_value(usage, "input_seconds", default=duration_seconds)),
        "output_seconds": nonnegative_number(usage_value(usage, "output_seconds")),
        "audio_seconds": audio_seconds,
        "video_seconds": video_seconds,
        "duration_minutes": duration_minutes,
        "input_minutes": nonnegative_number(usage_value(usage, "input_minutes", default=duration_minutes)),
        "output_minutes": nonnegative_number(usage_value(usage, "output_minutes")),
        "audio_minutes": audio_minutes,
        "input_characters": nonnegative_int(usage_value(usage, "input_characters")),
        "output_characters": nonnegative_int(usage_value(usage, "output_characters")),
        "response_model": usage.get("response_model"),
    }


def normalize_existing_usage(usage: Dict[str, Any]) -> Dict[str, Any]:
    result = empty_usage()
    result.update(usage)
    result["usage_schema_version"] = 2
    result["input_uncached_tokens"] = nonnegative_int(result.get("input_uncached_tokens"))
    result["cache_read_tokens"] = nonnegative_int(result.get("cache_read_tokens"))
    result["cache_write_tokens"] = nonnegative_int(result.get("cache_write_tokens"))
    expected = result["input_uncached_tokens"] + result["cache_read_tokens"] + result["cache_write_tokens"]
    result["input_tokens_total"] = nonnegative_int(result.get("input_tokens_total", expected))
    result["prompt_tokens"] = result["input_tokens_total"]
    result["completion_tokens"] = nonnegative_int(result.get("completion_tokens"))
    result["reasoning_tokens"] = nonnegative_int(result.get("reasoning_tokens"))
    result["output_tokens"] = nonnegative_int(result.get("output_tokens",
                                                          result["completion_tokens"] - result["reasoning_tokens"]))
    result["total_tokens"] = nonnegative_int(result.get("total_tokens",
                                                        result["input_tokens_total"] + result["completion_tokens"]))
    if result["input_tokens_total"] != expected or result["reasoning_tokens"] > result["completion_tokens"]:
        result["cost_status"] = "INVALID_USAGE"
    result.setdefault("usage_source", "TOKENSea_NORMALIZED")
    result.setdefault("usage_evidence", {})
    result.setdefault("cost_status", "COMPLETE")
    return result


def normalize_usage(usage: Any, provider_type: Optional[str] = None) -> Dict[str, Any]:
    usage = usage if isinstance(usage, dict) else {}
    if int(usage.get("usage_schema_version") or 0) >= 2:
        return normalize_existing_usage(usage)
    if isinstance(usage.get("usageMetadata"), dict):
        usage = usage["usageMetadata"]
    provider = str(provider_type or "").lower()
    completion_details = usage.get("completion_tokens_details") if isinstance(usage.get("completion_tokens_details"), dict) else {}
    reasoning = nonnegative_int(usage_value(completion_details, "reasoning_tokens",
                                            default=usage_value(usage, "reasoning_tokens", "thoughtsTokenCount")))
    negative_fields = ["prompt_tokens", "input_tokens", "completion_tokens", "output_tokens",
                       "prompt_cache_hit_tokens", "prompt_cache_miss_tokens", "cache_read_input_tokens",
                       "cache_creation_input_tokens", "cachedContentTokenCount", "cached_content_token_count"]
    invalid = usage_has_negative(usage, negative_fields)

    if "prompt_cache_hit_tokens" in usage or "prompt_cache_miss_tokens" in usage:
        cache_read = nonnegative_int(usage_value(usage, "prompt_cache_hit_tokens"))
        input_uncached = nonnegative_int(usage_value(usage, "prompt_cache_miss_tokens"))
        prompt = nonnegative_int(usage_value(usage, "prompt_tokens", "input_tokens",
                                             default=input_uncached + cache_read))
        invalid = invalid or (prompt > 0 and prompt != input_uncached + cache_read)
        completion = nonnegative_int(usage_value(usage, "completion_tokens", "output_tokens"))
        total = nonnegative_int(usage_value(usage, "total_tokens", default=prompt + completion))
        return complete_standard_usage(usage, "DEEPSEEK", input_uncached, cache_read, 0,
                                       completion, reasoning, prompt, total, invalid)

    if "cache_read_input_tokens" in usage or "cache_creation_input_tokens" in usage:
        input_uncached = nonnegative_int(usage_value(usage, "input_tokens", "prompt_tokens"))
        cache_read = nonnegative_int(usage_value(usage, "cache_read_input_tokens"))
        cache_write = nonnegative_int(usage_value(usage, "cache_creation_input_tokens"))
        completion = nonnegative_int(usage_value(usage, "output_tokens", "completion_tokens"))
        input_total = input_uncached + cache_read + cache_write
        total = nonnegative_int(usage_value(usage, "total_tokens", default=input_total + completion))
        return complete_standard_usage(usage, "ANTHROPIC", input_uncached, cache_read, cache_write,
                                       completion, reasoning, input_total, total, invalid)

    gemini_fields = {"promptTokenCount", "candidatesTokenCount", "cachedContentTokenCount",
                     "prompt_token_count", "candidates_token_count", "cached_content_token_count"}
    if provider in {"gemini", "google", "vertex_ai", "vertex"} or gemini_fields.intersection(usage.keys()):
        prompt = nonnegative_int(usage_value(usage, "promptTokenCount", "prompt_token_count", "prompt_tokens"))
        cache_read = nonnegative_int(usage_value(usage, "cachedContentTokenCount", "cached_content_token_count"))
        input_uncached = max(0, prompt - cache_read)
        invalid = invalid or cache_read > prompt
        completion = nonnegative_int(usage_value(usage, "candidatesTokenCount", "candidates_token_count",
                                                 "completion_tokens", "output_tokens"))
        total = nonnegative_int(usage_value(usage, "totalTokenCount", "total_token_count", "total_tokens",
                                            default=prompt + completion))
        return complete_standard_usage(usage, "GEMINI", input_uncached, cache_read, 0,
                                       completion, reasoning, prompt, total, invalid)

    prompt_details = usage.get("prompt_tokens_details") if isinstance(usage.get("prompt_tokens_details"), dict) else {}
    if "cached_tokens" in prompt_details:
        prompt = nonnegative_int(usage_value(usage, "prompt_tokens", "input_tokens"))
        cache_read = nonnegative_int(prompt_details.get("cached_tokens"))
        input_uncached = max(0, prompt - cache_read)
        invalid = invalid or cache_read > prompt
        completion = nonnegative_int(usage_value(usage, "completion_tokens", "output_tokens"))
        total = nonnegative_int(usage_value(usage, "total_tokens", default=prompt + completion))
        return complete_standard_usage(usage, "OPENAI", input_uncached, cache_read, 0,
                                       completion, reasoning, prompt, total, invalid)

    if "input_uncached_tokens" in usage:
        input_uncached = nonnegative_int(usage.get("input_uncached_tokens"))
        cache_read = nonnegative_int(usage_value(usage, "cache_read_tokens", "cached_tokens"))
        cache_write = nonnegative_int(usage_value(usage, "cache_write_tokens"))
        input_total = nonnegative_int(usage_value(usage, "input_tokens_total",
                                                  default=input_uncached + cache_read + cache_write))
        completion = nonnegative_int(usage_value(usage, "completion_tokens", "output_tokens"))
        total = nonnegative_int(usage_value(usage, "total_tokens", default=input_total + completion))
        return complete_standard_usage(usage, "LITELLM_NORMALIZED", input_uncached, cache_read, cache_write,
                                       completion, reasoning, input_total, total, invalid)

    prompt = nonnegative_int(usage_value(usage, "prompt_tokens", "input_tokens"))
    completion = nonnegative_int(usage_value(usage, "completion_tokens", "output_tokens"))
    cache_keys = [key for key, value in usage.items()
                  if "cache" in str(key).lower() and key not in {"cache_ttl_seconds", "cacheTtlSeconds"}
                  and value not in {None, 0, "0", False}]
    incomplete = bool(cache_keys)
    total = nonnegative_int(usage_value(usage, "total_tokens", default=prompt + completion))
    source = "OPENAI" if provider in {"openai", "azure", "azure_openai"} else "LITELLM_GENERIC"
    return complete_standard_usage(usage, source, prompt, 0, 0, completion, reasoning,
                                   prompt, total, invalid, incomplete)


def empty_usage():
    return {
        "usage_schema_version": 2,
        "usage_source": "EMPTY",
        "usage_evidence": {},
        "cost_status": "COMPLETE",
        "input_uncached_tokens": 0,
        "input_tokens_total": 0,
        "prompt_tokens": 0,
        "cache_read_tokens": 0,
        "cache_write_tokens": 0,
        "completion_tokens": 0,
        "output_tokens": 0,
        "reasoning_tokens": 0,
        "total_tokens": 0,
        "cache_storage_token_seconds": 0,
        "cache_ttl_seconds": 0,
        "cache_read_in_prompt": False,
        "cache_write_in_prompt": False,
        "reasoning_in_completion": False,
        "image_count": 0,
        "input_images": 0,
        "output_images": 0,
        "duration_seconds": 0,
        "input_seconds": 0,
        "output_seconds": 0,
        "audio_seconds": 0,
        "video_seconds": 0,
        "duration_minutes": 0,
        "input_minutes": 0,
        "output_minutes": 0,
        "audio_minutes": 0,
        "input_characters": 0,
        "output_characters": 0,
        "response_model": None,
    }


def parse_json_object(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    if not value:
        return {}
    try:
        parsed = json.loads(str(value))
        return parsed if isinstance(parsed, dict) else {}
    except (TypeError, ValueError, json.JSONDecodeError):
        return {}


def parse_json_array(value: Any) -> List[Dict[str, Any]]:
    parsed: Any = value
    if not value:
        return []
    if not isinstance(value, (list, dict)):
        try:
            parsed = json.loads(str(value))
        except (TypeError, ValueError, json.JSONDecodeError):
            return []
    if isinstance(parsed, list):
        return [dict(item) for item in parsed if isinstance(item, dict)]
    if isinstance(parsed, dict):
        result: List[Dict[str, Any]] = []
        for component_type, raw in parsed.items():
            variants = raw if isinstance(raw, list) else [raw]
            for spec in variants:
                if not isinstance(spec, dict):
                    continue
                item = dict(spec)
                item.setdefault("componentType", str(component_type).upper())
                item.setdefault("variant", "DEFAULT")
                item.setdefault("mode", item.pop("componentMode", "EXPLICIT"))
                item.setdefault("scope", {})
                item.setdefault("priority", 100)
                item.setdefault("metadata", {})
                result.append(item)
        return result
    return []


def bounded_int(value: Any, minimum: int, maximum: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        raise gateway_error(503, "TOKENSEA_CONFIG_INVALID", "数值配置无效")
    if number < minimum or number > maximum:
        raise gateway_error(503, "TOKENSEA_CONFIG_INVALID", "数值配置超出范围")
    return number


def nonnegative_int(value: Any) -> int:
    try:
        return max(0, int(value or 0))
    except (TypeError, ValueError):
        return 0


def nonnegative_number(value: Any) -> float:
    try:
        return max(0.0, float(value or 0))
    except (TypeError, ValueError):
        return 0.0


def extract_bearer(request: Request) -> str:
    auth = request.headers.get("authorization")
    if not auth or not auth.lower().startswith("bearer ") or not auth.split(" ", 1)[1].strip():
        raise gateway_error(401, "TOKENSEA_AUTH_REQUIRED", "需要 Bearer API Key")
    return auth.split(" ", 1)[1].strip()


def validate_client_request_id(value: Optional[str]):
    if value is not None and not REQUEST_ID_PATTERN.fullmatch(value):
        raise gateway_error(400, "TOKENSEA_REQUEST_ID_INVALID", "x-request-id 格式无效")


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat()


def require_runtime_settings():
    try:
        database_pool_kwargs()
        decode_crypto_key(CRYPTO_KEY or "")
    except (RuntimeError, ValueError):
        raise gateway_error(503, "TOKENSEA_RUNTIME_NOT_CONFIGURED", "运行时配置不完整")
    if not ENGINE_KEY or not REDIS_PASSWORD:
        raise gateway_error(503, "TOKENSEA_RUNTIME_NOT_CONFIGURED", "运行时配置不完整")


def request_timeout(route):
    seconds = route["timeout_seconds"]
    return httpx.Timeout(connect=min(10, seconds), read=seconds, write=seconds, pool=min(10, seconds))


def runtime_headers(request_id: Optional[str] = None):
    headers = {"Authorization": f"Bearer {ENGINE_KEY}", "Content-Type": "application/json"}
    if request_id:
        headers["x-request-id"] = request_id
    return headers


def safe_json(response: httpx.Response) -> Dict[str, Any]:
    try:
        value = response.json()
        return value if isinstance(value, dict) else {"data": value}
    except Exception:
        return error_body(normalize_error(response.status_code), "上游返回了无效响应")


def safe_json_bytes(raw: bytes) -> Dict[str, Any]:
    try:
        value = json.loads(raw)
        return value if isinstance(value, dict) else {"data": value}
    except Exception:
        return {}


def is_runtime_model_missing(response: httpx.Response, data: Dict[str, Any]) -> bool:
    if response.status_code != 404:
        return False
    text = json.dumps(data, ensure_ascii=False).lower()
    return "model" in text and ("not found" in text or "does not exist" in text)


def normalize_error(status_code: int) -> str:
    if status_code in (401, 403):
        return "TOKENSEA_UPSTREAM_AUTH_ERROR"
    if status_code == 429:
        return "TOKENSEA_UPSTREAM_RATE_LIMIT"
    if status_code >= 500:
        return "TOKENSEA_UPSTREAM_UNAVAILABLE"
    return f"TOKENSEA_UPSTREAM_{status_code}"


def stable_gateway_status(upstream_status: int) -> int:
    return upstream_status if upstream_status in (400, 401, 403, 404, 408, 409, 422, 429) else 502


def attempt_summary(route, attempt_no, http_status, error_code):
    return {"attempt": attempt_no, "providerInstanceId": route.get("provider_id"),
            "runtimeModel": route.get("runtime_model_name"), "httpStatus": http_status, "errorCode": error_code}


def gateway_error(status: int, code: str, message: str) -> HTTPException:
    return HTTPException(status_code=status, detail={"error_code": code, "message": message})


def error_body(code: str, message: str):
    return {"error": {"code": code, "message": message}}
