import os
import time
import uuid
import json
import hashlib
import asyncio
from decimal import Decimal
from typing import Any, Dict, Optional, List

import asyncpg
import httpx
import redis.asyncio as redis
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse, PlainTextResponse
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST

DB_DSN = os.getenv("TOKENSEA_DB_DSN", "postgresql://tokensea:tokensea_change_me@localhost:39213/tokensea")
REDIS_URL = os.getenv("TOKENSEA_REDIS_URL", "redis://localhost:39214/0")
ENGINE_URL = os.getenv("TOKENSEA_RUNTIME_ENGINE_URL", "http://localhost:39218").rstrip("/")
ENGINE_KEY = os.getenv("TOKENSEA_RUNTIME_ENGINE_KEY", "change-me-runtime-master-key")
LOG_PROMPT_RESPONSE = os.getenv("TOKENSEA_LOG_PROMPT_RESPONSE", "false").lower() == "true"

app = FastAPI(title="TokenSea Gateway Runtime", version="0.1.0")
REQUESTS = Counter("tokensea_gateway_requests_total", "Gateway requests", ["endpoint", "status"])
LATENCY = Histogram("tokensea_gateway_latency_seconds", "Gateway latency", ["endpoint"])

pool: Optional[asyncpg.Pool] = None
cache: Optional[redis.Redis] = None

@app.on_event("startup")
async def startup():
    global pool, cache
    pool = await asyncpg.create_pool(DB_DSN, min_size=1, max_size=10)
    cache = redis.from_url(REDIS_URL, decode_responses=True)

@app.on_event("shutdown")
async def shutdown():
    if pool: await pool.close()
    if cache: await cache.close()

@app.get("/health")
async def health():
    return {"status": "ok", "service": "tokensea-gateway-runtime"}

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

async def proxy_openai_compatible(request: Request, endpoint: str):
    started = time.time()
    request_id = request.headers.get("x-request-id") or str(uuid.uuid4())
    status = "success"
    error_code = None
    usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
    fallback_chain: List[Dict[str, Any]] = []
    key_ctx = None
    route_ctx = None
    body = await request.json()
    model_alias = body.get("model")
    if not model_alias:
        raise HTTPException(status_code=400, detail={"error_code": "TOKENSEA_MODEL_REQUIRED", "message": "model is required"})
    stream = bool(body.get("stream"))
    try:
        token = extract_bearer(request)
        key_ctx = await validate_key(token, model_alias)
        await enforce_budget(key_ctx)
        route_ctx = await select_route(model_alias)
        if not route_ctx:
            raise HTTPException(status_code=404, detail={"error_code": "TOKENSEA_MODEL_NOT_FOUND", "message": "model alias not configured"})
        body = dict(body)
        body["model"] = route_ctx["runtime_model_name"]
        upstream_headers = {
            "Authorization": f"Bearer {ENGINE_KEY}",
            "Content-Type": "application/json",
            "x-request-id": request_id,
        }
        fallback_chain.append({"model_alias": model_alias, "runtime_model_name": route_ctx["runtime_model_name"], "provider_id": route_ctx.get("provider_id")})
        if stream:
            return await stream_proxy(endpoint, body, upstream_headers, started, request_id, key_ctx, route_ctx, model_alias, fallback_chain)
        async with httpx.AsyncClient(timeout=route_ctx.get("timeout_seconds", 120)) as client:
            resp = await client.post(f"{ENGINE_URL}{endpoint}", headers=upstream_headers, json=body)
            status_code = resp.status_code
            data = safe_json(resp)
            if status_code >= 400:
                status = "failed"
                error_code = normalize_error(status_code, data)
            else:
                usage = data.get("usage") or usage
            await write_usage(request_id, key_ctx, route_ctx, model_alias, usage, status, error_code, int((time.time()-started)*1000), fallback_chain)
            REQUESTS.labels(endpoint=endpoint, status=status).inc()
            LATENCY.labels(endpoint=endpoint).observe(time.time()-started)
            return JSONResponse(content=data, status_code=status_code, headers={"x-request-id": request_id})
    except HTTPException as e:
        status = "failed"
        error_code = e.detail.get("error_code") if isinstance(e.detail, dict) else "TOKENSEA_HTTP_ERROR"
        if key_ctx:
            await write_usage(request_id, key_ctx, route_ctx, model_alias, usage, status, error_code, int((time.time()-started)*1000), fallback_chain)
        REQUESTS.labels(endpoint=endpoint, status=status).inc()
        raise e
    except Exception as e:
        status = "failed"
        error_code = "TOKENSEA_GATEWAY_ERROR"
        if key_ctx:
            await write_usage(request_id, key_ctx, route_ctx, model_alias, usage, status, error_code, int((time.time()-started)*1000), fallback_chain)
        REQUESTS.labels(endpoint=endpoint, status=status).inc()
        raise HTTPException(status_code=502, detail={"error_code": error_code, "message": str(e)})

async def stream_proxy(endpoint, body, headers, started, request_id, key_ctx, route_ctx, model_alias, fallback_chain):
    async def gen():
        status = "success"
        error_code = None
        try:
            async with httpx.AsyncClient(timeout=None) as client:
                async with client.stream("POST", f"{ENGINE_URL}{endpoint}", headers=headers, json=body) as resp:
                    if resp.status_code >= 400:
                        status = "failed"
                        error_code = f"UPSTREAM_{resp.status_code}"
                    async for chunk in resp.aiter_bytes():
                        yield chunk
        except Exception as exc:
            status = "failed"
            error_code = "TOKENSEA_STREAM_ERROR"
            yield f"data: {json.dumps({'error': str(exc), 'error_code': error_code})}\n\n".encode()
        finally:
            await write_usage(request_id, key_ctx, route_ctx, model_alias, {"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}, status, error_code, int((time.time()-started)*1000), fallback_chain)
            REQUESTS.labels(endpoint=endpoint, status=status).inc()
            LATENCY.labels(endpoint=endpoint).observe(time.time()-started)
    return StreamingResponse(gen(), media_type="text/event-stream", headers={"x-request-id": request_id})


def extract_bearer(request: Request) -> str:
    auth = request.headers.get("authorization") or request.headers.get("Authorization")
    if not auth or not auth.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail={"error_code": "TOKENSEA_AUTH_REQUIRED", "message": "Bearer token required"})
    return auth.split(" ", 1)[1].strip()

async def validate_key(token: str, model_alias: str) -> Dict[str, Any]:
    h = hashlib.sha256(token.encode()).hexdigest()
    assert pool is not None
    row = await pool.fetchrow("""
        SELECT id, tenant_id, project_id, app_id, status, approval_status, model_scope, budget_amount, rpm_limit, tpm_limit, qps_limit, expires_at
        FROM api_key WHERE key_hash=$1
    """, h)
    if not row:
        raise HTTPException(status_code=401, detail={"error_code": "TOKENSEA_KEY_INVALID", "message": "invalid api key"})
    if row["status"] != "ACTIVE" or row["approval_status"] != "APPROVED":
        raise HTTPException(status_code=403, detail={"error_code": "TOKENSEA_KEY_DISABLED", "message": "api key is not active"})
    if row["expires_at"] and row["expires_at"].timestamp() < time.time():
        raise HTTPException(status_code=403, detail={"error_code": "TOKENSEA_KEY_EXPIRED", "message": "api key expired"})
    scope = row["model_scope"]
    if isinstance(scope, str):
        try: scope = json.loads(scope)
        except Exception: scope = []
    if scope and model_alias not in scope:
        raise HTTPException(status_code=403, detail={"error_code": "TOKENSEA_MODEL_FORBIDDEN", "message": "model is not allowed by key scope"})
    return dict(row)

async def enforce_budget(key_ctx: Dict[str, Any]):
    budget = key_ctx.get("budget_amount")
    if budget is None:
        return
    assert pool is not None
    used = await pool.fetchval("SELECT COALESCE(SUM(sales_amount),0) FROM usage_record WHERE api_key_id=$1 AND created_at >= date_trunc('month', now())", key_ctx["id"])
    if Decimal(str(used)) >= Decimal(str(budget)):
        raise HTTPException(status_code=402, detail={"error_code": "TOKENSEA_BUDGET_EXCEEDED", "message": "api key budget exceeded"})

async def select_route(model_alias: str) -> Optional[Dict[str, Any]]:
    assert pool is not None
    row = await pool.fetchrow("""
        SELECT md.id AS deployment_id, md.runtime_model_name, md.provider_id, md.timeout_seconds,
               m.id AS model_id, m.alias, p.name AS provider_name
        FROM model m
        JOIN model_deployment md ON md.model_id=m.id
        JOIN provider p ON p.id=md.provider_id
        WHERE m.alias=$1 AND m.status='ACTIVE' AND md.status='ACTIVE' AND p.status='ACTIVE'
        ORDER BY md.priority ASC, md.weight DESC, md.created_at ASC
        LIMIT 1
    """, model_alias)
    return dict(row) if row else None

async def write_usage(request_id, key_ctx, route_ctx, model_alias, usage, status, error_code, latency_ms, fallback_chain):
    if not key_ctx:
        return
    assert pool is not None
    route_ctx = route_ctx or {}
    prompt_tokens = int(usage.get("prompt_tokens") or usage.get("input_tokens") or 0)
    completion_tokens = int(usage.get("completion_tokens") or usage.get("output_tokens") or 0)
    total_tokens = int(usage.get("total_tokens") or (prompt_tokens + completion_tokens))
    model_id = route_ctx.get("model_id")
    price = None
    if model_id:
        price = await pool.fetchrow("""
          SELECT currency, input_cost_per_1k, output_cost_per_1k, input_price_per_1k, output_price_per_1k
          FROM model_price
          WHERE model_id=$1 AND status='ACTIVE' AND effective_from <= now() AND (effective_to IS NULL OR effective_to > now())
          ORDER BY effective_from DESC LIMIT 1
        """, model_id)
    currency = "CNY"
    cost = Decimal("0")
    sales = Decimal("0")
    if price:
        currency = price["currency"]
        cost = Decimal(prompt_tokens) * Decimal(str(price["input_cost_per_1k"])) / Decimal(1000) + Decimal(completion_tokens) * Decimal(str(price["output_cost_per_1k"])) / Decimal(1000)
        sales = Decimal(prompt_tokens) * Decimal(str(price["input_price_per_1k"])) / Decimal(1000) + Decimal(completion_tokens) * Decimal(str(price["output_price_per_1k"])) / Decimal(1000)
    await pool.execute("""
      INSERT INTO usage_record(id, request_id, tenant_id, project_id, app_id, api_key_id, model_alias, runtime_model_name,
        provider_id, prompt_tokens, completion_tokens, total_tokens, cost_amount, sales_amount, currency, status, error_code,
        latency_ms, fallback_chain)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19)
    """, str(uuid.uuid4()), request_id, key_ctx.get("tenant_id"), key_ctx.get("project_id"), key_ctx.get("app_id"), key_ctx.get("id"),
       model_alias, route_ctx.get("runtime_model_name"), route_ctx.get("provider_id"), prompt_tokens, completion_tokens, total_tokens,
       cost, sales, currency, status, error_code, latency_ms, json.dumps(fallback_chain, ensure_ascii=False))


def safe_json(resp: httpx.Response) -> Dict[str, Any]:
    try: return resp.json()
    except Exception: return {"raw": resp.text}


def normalize_error(status_code: int, data: Dict[str, Any]) -> str:
    if status_code == 401: return "TOKENSEA_UPSTREAM_AUTH_ERROR"
    if status_code == 429: return "TOKENSEA_UPSTREAM_RATE_LIMIT"
    if status_code >= 500: return "TOKENSEA_UPSTREAM_UNAVAILABLE"
    return data.get("error", {}).get("code") if isinstance(data.get("error"), dict) else f"UPSTREAM_{status_code}"
