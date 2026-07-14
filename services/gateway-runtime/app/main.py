import asyncio
import base64
import hashlib
import ipaddress
import json
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
BUDGET_CURRENCY = os.getenv("TOKENSEA_BUDGET_CURRENCY", "CNY")
REGISTRATION_TTL = max(5, int(os.getenv("TOKENSEA_REGISTRATION_TTL_SECONDS", "60")))
DEFAULT_OUTPUT_RESERVATION = max(1, int(os.getenv("TOKENSEA_DEFAULT_OUTPUT_RESERVATION_TOKENS", "1024")))
TRUSTED_PROXY_CIDRS = os.getenv("TOKENSEA_TRUSTED_PROXY_CIDRS", "")
RETRYABLE_STATUS = {408, 425, 429, 500, 502, 503, 504}
ACTIVE_VALUES = {"ACTIVE", "启用", "已启用"}
REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
DNS_RECHECK_TTL = max(1, int(os.getenv("TOKENSEA_DNS_RECHECK_TTL_SECONDS", "10")))
WAL_DIR = Path(os.getenv("TOKENSEA_OUTBOX_DIR", "/var/lib/tokensea-gateway/outbox"))
WAL_MAX_BYTES = max(1_048_576, min(int(os.getenv("TOKENSEA_OUTBOX_MAX_BYTES", "67108864")), 1_073_741_824))
WAL_FILE_NAME = "gateway-outbox.wal"
WAL_DEAD_FILE_NAME = "gateway-outbox.dead.wal"
OUTBOX_MAX_ATTEMPTS = max(1, int(os.getenv("TOKENSEA_OUTBOX_MAX_ATTEMPTS", "12")))
INTENT_RECOVERY_SECONDS = max(300, int(os.getenv("TOKENSEA_INTENT_RECOVERY_SECONDS", "86400")))

REQUESTS = Counter("tokensea_gateway_requests_total", "Gateway requests", ["endpoint", "status"])
LATENCY = Histogram("tokensea_gateway_latency_seconds", "Gateway latency", ["endpoint"])

pool: Optional[asyncpg.Pool] = None
cache: Optional[redis.Redis] = None
runtime_models: Dict[str, Dict[str, Any]] = {}
runtime_model_lock = asyncio.Lock()
outbox_task: Optional[asyncio.Task] = None
wal_lock = threading.Lock()
wal_pending: Dict[str, Dict[str, Any]] = {}

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
    if missing or pool is None or cache is None:
        return JSONResponse({"status": "not_ready", "missing": missing}, status_code=503)
    try:
        await pool.fetchval("SELECT 1")
        if not await cache.ping():
            raise RuntimeError("redis unavailable")
        async with httpx.AsyncClient(timeout=5) as client:
            response = await client.get(f"{ENGINE_URL}/health/liveliness")
        if response.status_code >= 300:
            raise RuntimeError("runtime unavailable")
    except Exception:
        return JSONResponse({"status": "not_ready"}, status_code=503)
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
    rows = await pool.fetch("SELECT platform_model_name FROM platform_model WHERE status='已发布' ORDER BY platform_model_name")
    data = []
    for row in rows:
        alias = row["platform_model_name"]
        try:
            validate_model_scope(key_ctx, alias)
            routes = await select_routes(alias, key_ctx)
            if routes:
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
        raise exc
    except Exception:
        REQUESTS.labels(endpoint=endpoint, status="FAILED").inc()
        raise gateway_error(502, "TOKENSEA_GATEWAY_ERROR", "网关暂时无法处理请求")
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
                await ensure_runtime_model(route)
                payload = dict(body)
                payload["model"] = route["runtime_alias"]
                async with httpx.AsyncClient(timeout=request_timeout(route)) as client:
                    response = await client.post(f"{ENGINE_URL}{endpoint}", headers=runtime_headers(request_id), json=payload)
                data = safe_json(response)
                if is_runtime_model_missing(response, data):
                    invalidate_runtime_model(route)
                    await ensure_runtime_model(route, force=True)
                    async with httpx.AsyncClient(timeout=request_timeout(route)) as client:
                        payload["model"] = route["runtime_alias"]
                        response = await client.post(f"{ENGINE_URL}{endpoint}", headers=runtime_headers(request_id), json=payload)
                    data = safe_json(response)
                usage = extract_usage(data)
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
                await ensure_runtime_model(route)
                payload = dict(body)
                payload["model"] = route["runtime_alias"]
                client = httpx.AsyncClient(timeout=request_timeout(route))
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
                        client = httpx.AsyncClient(timeout=request_timeout(route))
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
                    merge_usage(usage, usage_from_sse_line(line))
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
    if not instance_ids or not actual_models or len(instance_ids) not in (1, len(actual_models)):
        raise gateway_error(503, "TOKENSEA_ROUTE_MAPPING_INVALID", "模型路由映射无效")
    mappings = []
    for index, actual in enumerate(actual_models):
        mappings.append({"provider_instance_id": instance_ids[0] if len(instance_ids) == 1 else instance_ids[index],
                         "actual_model": actual, "priority": index + 1, "weight": 100,
                         "timeout_seconds": 120, "max_retries": 0,
                         "price_version_id": model["price_policy_id"]})
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
             last_connection_test_addresses,rate_limit_rpm,rate_limit_tpm,updated_at
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
                      "secret_version": secret_version, "price": price,
                      "fallback_enabled": bool(model["fallback_enabled"]),
                      "retry_backoff_ms": bounded_int(config.get("retryBackoffMs", 100), 0, 5000)})
        route["deployment_id"] = f"{model['id']}:{instance['id']}:{mapping['actual_model']}"
        routes.append(route)
    return ordered_routes(routes, model["strategy"])


async def load_price(price_id: Any, platform_model_id: str, provider_instance_id: str, actual_model: str) -> Dict[str, Any]:
    assert pool is not None
    governed = await pool.fetchrow("""
      SELECT actual.id,actual.price_layer,actual.currency,d.id channel_deployment_id,
             actual.input_amount_per_1k input_cost_per_1k,
             actual.output_amount_per_1k output_cost_per_1k,
             internal.id internal_price_id,
             COALESCE(internal.input_amount_per_1k,actual.input_amount_per_1k) input_price_per_1k,
             COALESCE(internal.output_amount_per_1k,actual.output_amount_per_1k) output_price_per_1k,
             COALESCE(actual.source_ref,'price_version:'||actual.id) source_ref,
             actual.price_components,actual.evidence_hash,actual.region,actual.request_mode,
             actual.service_tier,actual.context_tier
      FROM channel_model_deployment d
      JOIN LATERAL (
        SELECT * FROM price_version p WHERE p.deployment_id=d.id
          AND p.price_layer IN ('PROVIDER_OFFICIAL','CHANNEL_ACTUAL')
          AND p.status='ACTIVE' AND p.effective_from<=now() AND (p.effective_to IS NULL OR p.effective_to>now())
        ORDER BY CASE WHEN p.price_layer='PROVIDER_OFFICIAL' THEN 0 ELSE 1 END,
                 p.effective_from DESC,p.version DESC LIMIT 1
      ) actual ON true
      LEFT JOIN LATERAL (
        SELECT * FROM price_version p WHERE p.platform_model_id=$1 AND p.price_layer='INTERNAL_ACCOUNTING'
          AND p.status='ACTIVE' AND p.currency=actual.currency AND p.effective_from<=now()
          AND (p.effective_to IS NULL OR p.effective_to>now()) ORDER BY p.effective_from DESC,p.version DESC LIMIT 1
      ) internal ON true
      WHERE d.provider_instance_id=$2 AND d.provider_model_name=$3 AND d.review_status='APPROVED'
        AND d.routing_status='ELIGIBLE'
    """, platform_model_id, provider_instance_id, actual_model)
    row = governed
    if row is None and isinstance(price_id, str) and price_id:
        row = await pool.fetchrow("""
          SELECT id,'LEGACY_MODEL_PRICE' price_layer,currency,NULL::varchar channel_deployment_id,
                 input_cost_per_1k,output_cost_per_1k,NULL::varchar internal_price_id,
                 input_price_per_1k,output_price_per_1k,'model_price:'||id source_ref,'{}'::jsonb price_components,NULL::varchar evidence_hash,
                 'global'::varchar region,'STANDARD'::varchar request_mode,
                 'DEFAULT'::varchar service_tier,'DEFAULT'::varchar context_tier
          FROM model_price WHERE id=$1 AND platform_model_id=$2
            AND (provider_instance_id IS NULL OR provider_instance_id=$3)
            AND status='ACTIVE' AND effective_from<=now() AND (effective_to IS NULL OR effective_to>now())
        """, price_id, platform_model_id, provider_instance_id)
    if not row:
        raise gateway_error(503, "TOKENSEA_PRICE_NOT_CONFIGURED", "模型未匹配当前生效的供应商官方价格")
    price = dict(row)
    price["price_components"] = parse_json_object(price.get("price_components"))
    fields = ("input_cost_per_1k", "output_cost_per_1k", "input_price_per_1k", "output_price_per_1k")
    if price["currency"] != BUDGET_CURRENCY or any(price[name] is None or Decimal(str(price[name])) < 0 for name in fields):
        raise gateway_error(503, "TOKENSEA_PRICE_INVALID", "价格版本币种不一致或存在负数价格")
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


async def reserve_budget(key_ctx: Dict[str, Any], routes: List[Dict[str, Any]], reserved_tokens: int,
                         request_id: str, reservation_token: Optional[str] = None,
                         allow_degrade: bool = True) -> Dict[str, Any]:
    # Budgets use actual provider cost in BUDGET_CURRENCY. Reserving every estimated
    # token at the route's higher input/output cost rate is conservative, so the
    # later settlement can only release capacity under normal provider accounting.
    amount = max(Decimal(reserved_tokens) * max(Decimal(str(route["price"]["input_cost_per_1k"])),
                                                 Decimal(str(route["price"]["output_cost_per_1k"]))) / Decimal(1000)
                 for route in routes)
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
              SELECT id,amount_limit,warning_threshold_percent,over_limit_action,degrade_model_alias
              FROM budget_rule WHERE scope_type=$1 AND scope_id=$2 AND status='ACTIVE' AND approval_status='APPROVED'
                AND effective_from<=now() AND (effective_to IS NULL OR effective_to>now())
              ORDER BY version DESC LIMIT 1
            """, scope_type, subject_id)
        limit = rule["amount_limit"] if rule else legacy_limit
        if limit is None:
            continue
        limit_micro = money_micro(Decimal(str(limit)))
        if limit_micro <= 0:
            raise gateway_error(402, "TOKENSEA_BUDGET_EXCEEDED", "预算额度不可用")
        column = {"key": "api_key_id", "app": "app_id", "project": "project_id", "tenant": "tenant_id"}[kind]
        committed = await pool.fetchval(f"SELECT COALESCE(SUM(cost_amount),0) FROM usage_record WHERE {column}=$1 AND status='SUCCESS' AND created_at>=date_trunc('month',now())", subject_id)
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
    usage = normalize_usage(usage)
    latency_ms = int((time.monotonic() - started) * 1000)
    completed_at = datetime.now(timezone.utc)
    attempt_cost, _, cost_components = calculate_amounts(route.get("price") or {}, usage)
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
                                 "inputCostPer1k": str(price.get("input_cost_per_1k", "0")),
                                 "outputCostPer1k": str(price.get("output_cost_per_1k", "0")),
                                 "priceComponents": price.get("price_components") or {},
                                 "costComponents": cost_components,
                                 "cacheReadTokens": usage.get("cache_read_tokens", 0),
                                 "cacheWriteTokens": usage.get("cache_write_tokens", 0),
                                 "reasoningTokens": usage.get("reasoning_tokens", 0)},
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
        latency_ms,fallback_chain,price_version_id,budget_reserved_amount,budget_status,accounting_status)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23)
      ON CONFLICT(id) DO UPDATE SET status=EXCLUDED.status,error_code=EXCLUDED.error_code,
        prompt_tokens=EXCLUDED.prompt_tokens,completion_tokens=EXCLUDED.completion_tokens,total_tokens=EXCLUDED.total_tokens,
        cost_amount=EXCLUDED.cost_amount,sales_amount=EXCLUDED.sales_amount,latency_ms=EXCLUDED.latency_ms,
        fallback_chain=EXCLUDED.fallback_chain,budget_status=EXCLUDED.budget_status,
        accounting_status=EXCLUDED.accounting_status
    """, payload["id"], payload["request_id"], payload.get("tenant_id"), payload.get("project_id"), payload.get("app_id"),
       payload.get("api_key_id"), payload["model_alias"], payload.get("runtime_model_name"), payload.get("provider_id"),
       payload["prompt_tokens"], payload["completion_tokens"], payload["total_tokens"], Decimal(payload["cost_amount"]),
       Decimal(payload["sales_amount"]), payload["currency"], payload["status"], payload.get("error_code"),
       payload["latency_ms"], payload["fallback_chain"], payload["price_version_id"], Decimal(payload["budget_reserved_amount"]),
       payload.get("budget_status", "NOT_APPLICABLE"), payload.get("accounting_status", "COMMITTED"))
    await pool.execute("""
      INSERT INTO usage_cost_snapshot(id,request_id,usage_record_id,price_version_id,price_layer,currency,
        input_amount_per_1k,output_amount_per_1k,prompt_tokens,completion_tokens,actual_cost_amount,source_ref,
        cache_read_tokens,cache_write_tokens,reasoning_tokens,price_components,cost_components,pricing_model,
        response_model,provider_instance_id,model_deployment_id,calculator_version,evidence_hash)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16::jsonb,$17::jsonb,$18,$19,$20,$21,$22,$23)
      ON CONFLICT(request_id) DO NOTHING
    """, hashlib.sha256(f"cost:{payload['request_id']}".encode()).hexdigest()[:32], payload["request_id"], payload["id"],
       payload["price_version_id"], payload.get("price_layer") or "PROVIDER_OFFICIAL", payload["currency"],
       Decimal(payload.get("input_cost_per_1k") or "0"), Decimal(payload.get("output_cost_per_1k") or "0"),
       payload["prompt_tokens"], payload["completion_tokens"], Decimal(payload["cost_amount"]),
       payload.get("price_source_ref"), payload.get("cache_read_tokens", 0), payload.get("cache_write_tokens", 0),
       payload.get("reasoning_tokens", 0), json.dumps(payload.get("price_components") or {}, ensure_ascii=False),
       json.dumps(payload.get("cost_components") or {}, ensure_ascii=False), payload.get("pricing_model"),
       payload.get("response_model"), payload.get("provider_instance_id"), payload.get("model_deployment_id"),
       "2.0.0", payload.get("evidence_hash"))


async def finalize_request(request_id, key_ctx, route, model_alias, usage, status, error_code,
                           started, fallback_chain, budget):
    usage = normalize_usage(usage)
    cost, sales, cost_components = calculate_amounts(route["price"], usage)
    if status != "SUCCESS":
        cost = sales = Decimal("0")
    budget_status = "SETTLED"
    budget_durable = True
    try:
        budget_status = await settle_budget(budget, cost)
        if budget_status == "OVERRUN" and status == "SUCCESS":
            error_code = "TOKENSEA_BUDGET_OVERRUN"
    except Exception:
        budget_status = "PENDING"
        if status == "SUCCESS":
            error_code = "TOKENSEA_BUDGET_SETTLEMENT_PENDING"
        budget["deferred_accounting"] = True
        try:
            await enqueue_outbox("budget_settle", {"reservation": serializable_reservation(budget), "actual": str(cost)})
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
               "input_cost_per_1k": str(route["price"]["input_cost_per_1k"]),
               "output_cost_per_1k": str(route["price"]["output_cost_per_1k"]),
               "price_source_ref": route["price"].get("source_ref") or "price_version:"+str(route["price"]["id"]),
               "price_components": route["price"].get("price_components") or {},
               "cost_components": cost_components,
               "evidence_hash": route["price"].get("evidence_hash"),
               "pricing_model": route.get("runtime_model_name"),
               "response_model": usage.get("response_model"),
               "provider_instance_id": route.get("provider_id"),
               "model_deployment_id": route["price"].get("channel_deployment_id"),
               "budget_reserved_amount": str(budget.get("amount") or Decimal("0")),
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
    if not force and route.get("dns_valid_until", 0) > time.monotonic():
        return
    parsed = urlparse(route.get("api_base") or "")
    if parsed.scheme.lower() not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise gateway_error(503, "TOKENSEA_SSRF_TARGET_REJECTED", "供应商 API Base 格式不安全")
    host = parsed.hostname.lower().rstrip(".")
    verified_host = str(route.get("verified_host") or "").lower().rstrip(".")
    if not verified_host or host != verified_host:
        raise gateway_error(503, "TOKENSEA_DNS_HOST_CHANGED", "供应商主机与发布快照不一致")
    expected = snapshot_addresses(route.get("verified_addresses"))
    validate_public_addresses(expected)
    current = await resolve_dns_addresses(host)
    validate_public_addresses(current)
    if current != expected:
        raise gateway_error(503, "TOKENSEA_DNS_SNAPSHOT_CHANGED", "供应商 DNS 地址已变化，需重新连接测试并发布")
    route["dns_valid_until"] = time.monotonic() + DNS_RECHECK_TTL


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
        async with httpx.AsyncClient(timeout=20) as client:
            response = await client.post(f"{ENGINE_URL}/model/new", headers=runtime_headers(), json=payload)
            if response.status_code == 409 and not await runtime_model_exists(client, runtime_alias):
                raise gateway_error(503, "TOKENSEA_RUNTIME_CONFLICT", "运行时模型注册冲突")
            if response.status_code not in (200, 201, 409):
                raise gateway_error(503, "TOKENSEA_RUNTIME_CONFIG_FAILED", "运行时拒绝模型配置")
            if current and current.get("runtime_alias") != runtime_alias:
                await client.post(f"{ENGINE_URL}/model/delete", headers=runtime_headers(), json={"id": current["runtime_alias"]})
        runtime_models[route["deployment_id"]] = {"fingerprint": fingerprint, "runtime_alias": runtime_alias,
                                                   "expires_at": time.monotonic() + REGISTRATION_TTL}


async def runtime_model_exists(client: httpx.AsyncClient, alias: str) -> bool:
    try:
        response = await client.get(f"{ENGINE_URL}/v2/model/info", headers=runtime_headers(), params={"model": alias, "size": 10})
        data = response.json()
        return response.status_code == 200 and any(item.get("model_name") == alias for item in data.get("data", []))
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
                and tested_at and tested_at.timestamp() > time.time() - 1800
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
    if isinstance(values, list) and (ctx["tenant_id"] in values or ctx.get("tenant_type") in values):
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


def calculate_amounts(price: Dict[str, Any], usage: Dict[str, Any]):
    usage = normalize_usage(usage)
    components = parse_json_object(price.get("price_components"))
    if not components:
        components = {
            "INPUT_TOKEN": {"unitPrice": price.get("input_cost_per_1k", "0"), "unitBasis": "PER_1K_TOKENS"},
            "OUTPUT_TOKEN": {"unitPrice": price.get("output_cost_per_1k", "0"), "unitBasis": "PER_1K_TOKENS"},
        }
    prompt_tokens = usage["prompt_tokens"]
    completion_tokens = usage["completion_tokens"]
    cache_read_tokens = usage.get("cache_read_tokens", 0)
    cache_write_tokens = usage.get("cache_write_tokens", 0)
    reasoning_tokens = usage.get("reasoning_tokens", 0)
    input_tokens = prompt_tokens
    output_tokens = completion_tokens
    if "CACHE_READ_TOKEN" in components and usage.get("cache_read_in_prompt"):
        input_tokens = max(0, input_tokens - cache_read_tokens)
    if "CACHE_WRITE_TOKEN" in components and usage.get("cache_write_in_prompt"):
        input_tokens = max(0, input_tokens - cache_write_tokens)
    if "REASONING_TOKEN" in components and usage.get("reasoning_in_completion"):
        output_tokens = max(0, output_tokens - reasoning_tokens)
    token_counts = {
        "INPUT_TOKEN": input_tokens,
        "OUTPUT_TOKEN": output_tokens,
        "CACHE_READ_TOKEN": cache_read_tokens,
        "CACHE_WRITE_TOKEN": cache_write_tokens,
        "REASONING_TOKEN": reasoning_tokens,
    }
    cost_components: Dict[str, str] = {}
    cost = Decimal("0")
    for component_type, count in token_counts.items():
        spec = components.get(component_type)
        if not isinstance(spec, dict) or count <= 0:
            continue
        basis = str(spec.get("unitBasis") or "PER_1K_TOKENS")
        if basis != "PER_1K_TOKENS":
            continue
        amount = Decimal(count) * Decimal(str(spec.get("unitPrice") or "0")) / Decimal(1000)
        cost += amount
        cost_components[component_type] = str(amount)
    if not price.get("internal_price_id"):
        sales = cost
    else:
        sales = (Decimal(prompt_tokens) * Decimal(str(price["input_price_per_1k"])) +
                 Decimal(completion_tokens) * Decimal(str(price["output_price_per_1k"]))) / Decimal(1000)
    return cost, sales, cost_components


def money_micro(value: Decimal) -> int:
    return int((value * Decimal(1_000_000)).to_integral_value(rounding=ROUND_CEILING))


def extract_usage(data: Any) -> Dict[str, Any]:
    if isinstance(data, dict):
        response = data.get("response") if isinstance(data.get("response"), dict) else data
        if isinstance(response.get("usage"), dict):
            normalized = normalize_usage(response["usage"])
            normalized["response_model"] = response.get("model") or data.get("model")
            return normalized
        if response is not data:
            return extract_usage(response)
    return empty_usage()


def usage_from_sse_line(line: bytes) -> Dict[str, Any]:
    if not line.startswith(b"data:"):
        return empty_usage()
    raw = line[5:].strip()
    if not raw or raw == b"[DONE]":
        return empty_usage()
    try:
        return extract_usage(json.loads(raw))
    except Exception:
        return empty_usage()


def merge_usage(target, incoming):
    if incoming.get("response_model"):
        target["response_model"] = incoming["response_model"]
    if incoming.get("total_tokens", 0) >= target.get("total_tokens", 0) and incoming.get("total_tokens", 0) > 0:
        target.update(incoming)


def normalize_usage(usage: Any) -> Dict[str, Any]:
    usage = usage if isinstance(usage, dict) else {}
    prompt_details = usage.get("prompt_tokens_details") if isinstance(usage.get("prompt_tokens_details"), dict) else {}
    completion_details = usage.get("completion_tokens_details") if isinstance(usage.get("completion_tokens_details"), dict) else {}
    prompt = nonnegative_int(usage.get("prompt_tokens", usage.get("input_tokens", 0)))
    completion = nonnegative_int(usage.get("completion_tokens", usage.get("output_tokens", 0)))
    cache_read_nested = prompt_details.get("cached_tokens")
    cache_read = nonnegative_int(cache_read_nested if cache_read_nested is not None else
                                 usage.get("cache_read_input_tokens", usage.get("cache_read_tokens", usage.get("cached_tokens", 0))))
    cache_write = nonnegative_int(usage.get("cache_creation_input_tokens",
                                           usage.get("cache_write_input_tokens", usage.get("cache_write_tokens", 0))))
    reasoning_nested = completion_details.get("reasoning_tokens")
    reasoning = nonnegative_int(reasoning_nested if reasoning_nested is not None else usage.get("reasoning_tokens", 0))
    total = nonnegative_int(usage.get("total_tokens", prompt + completion +
                                      (0 if cache_read_nested is not None else cache_read) + cache_write))
    return {
        "prompt_tokens": prompt,
        "completion_tokens": completion,
        "total_tokens": total or prompt + completion,
        "cache_read_tokens": cache_read,
        "cache_write_tokens": cache_write,
        "reasoning_tokens": reasoning,
        "cache_read_in_prompt": cache_read_nested is not None or bool(usage.get("cache_read_in_prompt")),
        "cache_write_in_prompt": bool(usage.get("cache_write_in_prompt")),
        "reasoning_in_completion": reasoning_nested is not None or bool(usage.get("reasoning_in_completion", reasoning > 0)),
        "response_model": usage.get("response_model"),
    }


def empty_usage():
    return {
        "prompt_tokens": 0,
        "completion_tokens": 0,
        "total_tokens": 0,
        "cache_read_tokens": 0,
        "cache_write_tokens": 0,
        "reasoning_tokens": 0,
        "cache_read_in_prompt": False,
        "cache_write_in_prompt": False,
        "reasoning_in_completion": False,
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
