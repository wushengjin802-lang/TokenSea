#!/usr/bin/env python3
"""Run TokenSea's live deepseek-v4-flash E2E flow against the local mock upstream.

The script uses Control Plane APIs for configuration and Gateway APIs for final
verification. It never prints the JWT secret, admin token, provider secret, or
full Virtual Key.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

MODEL = "deepseek-v4-flash"
PROVIDER_NAME = "E2E DeepSeek Flash Mock"
ROUTE_NAME = "E2E DeepSeek V4 Flash Route"
TENANT_NAME = "E2E Flash Test Tenant"
PROJECT_NAME = "E2E Flash Test Project"
APP_NAME = "E2E Flash Test App"
KEY_NAME = "E2E Flash Virtual Key"
MOCK_BASE = "http://host.docker.internal:39301/v1"
CONTROL_BASE = "http://localhost:39211"
GATEWAY_BASE = "http://localhost:39212"


class E2EError(RuntimeError):
    pass


@dataclass
class Report:
    steps: list[dict[str, Any]] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    ids: dict[str, str] = field(default_factory=dict)

    def ok(self, step: str, detail: str) -> None:
        self.steps.append({"step": step, "status": "PASS", "detail": detail})
        print(f"[PASS] {step}: {detail}")

    def warn(self, message: str) -> None:
        self.warnings.append(message)
        print(f"[WARN] {message}")


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        values[name.strip()] = value
    return values


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def admin_token(secret: str) -> str:
    now = int(time.time())
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64url(
        json.dumps(
            {
                "sub": "admin",
                "username": "admin",
                "roles": ["ADMIN"],
                "tenant_ids": [],
                "iat": now,
                "exp": now + 3600,
            },
            separators=(",", ":"),
        ).encode()
    )
    signing_input = f"{header}.{payload}".encode("ascii")
    signature = b64url(hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest())
    return f"{header}.{payload}.{signature}"


def error_text(status: int, body: bytes) -> str:
    text = body.decode("utf-8", errors="replace").strip()
    try:
        payload = json.loads(text)
        if isinstance(payload, dict):
            detail = payload.get("detail")
            if isinstance(detail, dict):
                code = detail.get("error_code") or detail.get("code") or ""
                return f"HTTP {status} {code}: {detail.get('message') or detail.get('detail')}"
            if detail:
                return f"HTTP {status}: {detail}"
            if payload.get("message"):
                return f"HTTP {status}: {payload['message']}"
            if isinstance(payload.get("error"), dict):
                error = payload["error"]
                return f"HTTP {status} {error.get('code', '')}: {error.get('message')}"
    except json.JSONDecodeError:
        pass
    return f"HTTP {status}: {text[:600]}"


def request_json(
    url: str,
    method: str = "GET",
    payload: Any | None = None,
    token: str | None = None,
    timeout: float = 60,
    unwrap: bool = True,
) -> tuple[Any, dict[str, str]]:
    body = None
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
            response_headers = {key.lower(): value for key, value in response.headers.items()}
    except urllib.error.HTTPError as exc:
        raise E2EError(error_text(exc.code, exc.read())) from None
    except urllib.error.URLError as exc:
        raise E2EError(f"无法连接 {url}: {exc.reason}") from None
    if not raw:
        return None, response_headers
    try:
        value = json.loads(raw.decode("utf-8"))
    except json.JSONDecodeError:
        raise E2EError(f"接口未返回 JSON: {url}") from None
    if unwrap and isinstance(value, dict) and {"success", "message", "data"}.issubset(value):
        if not value.get("success"):
            raise E2EError(f"{url}: {value.get('message')}")
        return value.get("data"), response_headers
    return value, response_headers


def api(path: str, token: str, method: str = "GET", payload: Any | None = None, timeout: float = 60) -> Any:
    value, _ = request_json(f"{CONTROL_BASE}{path}", method, payload, token, timeout, True)
    return value


def pick(row: dict[str, Any], *names: str, default: Any = None) -> Any:
    for name in names:
        if name in row and row[name] is not None:
            return row[name]
        snake = "".join(f"_{c.lower()}" if c.isupper() else c for c in name)
        if snake in row and row[snake] is not None:
            return row[snake]
    return default


def page_items(value: Any) -> list[dict[str, Any]]:
    if isinstance(value, list):
        return value
    if isinstance(value, dict) and isinstance(value.get("items"), list):
        return value["items"]
    raise E2EError("分页列表接口未返回 items 数组")


def find(rows: list[dict[str, Any]], field: str, value: Any) -> dict[str, Any] | None:
    return next((row for row in rows if pick(row, field) == value), None)


def wait_price_run(run_id: str, token: str, timeout: int = 90) -> dict[str, Any]:
    terminal = {"SUCCEEDED", "NO_CHANGE", "REVIEW_REQUIRED", "FAILED", "CANCELLED"}
    deadline = time.time() + timeout
    while time.time() < deadline:
        runs = api("/api/provider-price-sync-runs", token)
        run = find(runs, "id", run_id)
        if run and str(pick(run, "status")) in terminal:
            return run
        time.sleep(2)
    raise E2EError(f"价格同步任务 {run_id} 等待超时")


def ensure_mock_health(report: Report) -> None:
    value, _ = request_json("http://localhost:39301/health", unwrap=False, timeout=5)
    if not isinstance(value, dict) or value.get("status") != "ok":
        raise E2EError("本地 Mock 上游未启动")
    report.ok("Mock 上游", "39301 健康检查通过")


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    env = load_env(root / "deploy" / "compose" / ".env")
    secret = env.get("TOKENSEA_JWT_SECRET", "")
    if len(secret.encode("utf-8")) < 32:
        raise E2EError("TOKENSEA_JWT_SECRET 未配置或长度不足")
    token = admin_token(secret)
    report = Report()

    ensure_mock_health(report)
    health, _ = request_json(f"{CONTROL_BASE}/actuator/health", unwrap=False)
    if health.get("status") != "UP":
        raise E2EError("Control Plane 未就绪")
    gateway_health, _ = request_json(f"{GATEWAY_BASE}/health", unwrap=False)
    if gateway_health.get("status") != "ok":
        raise E2EError("Gateway 未就绪")
    report.ok("平台健康", "Control Plane 与 Gateway 均正常")

    templates = api("/api/provider-templates", token)
    template = next((row for row in templates if str(pick(row, "providerType", default="")).lower() == "deepseek"), None)
    if not template:
        raise E2EError("未找到 DeepSeek 供应商模板")

    providers = api("/api/provider-instances", token)
    provider = find(providers, "instanceName", PROVIDER_NAME)
    if not provider:
        provider = api(
            "/api/provider-instances",
            token,
            "POST",
            {
                "providerTemplateId": pick(template, "id"),
                "instanceName": PROVIDER_NAME,
                "providerType": "deepseek",
                "apiStyle": "openai_compatible",
                "apiBase": MOCK_BASE,
                "region": "global",
                "environment": "测试",
                "owner": "E2E",
                "rateLimitRpm": 120,
                "rateLimitTpm": 200000,
            },
        )
    provider_id = str(pick(provider, "id"))
    report.ids["providerInstanceId"] = provider_id

    if pick(provider, "keyStatus") != "已托管":
        api(
            "/api/provider-secrets",
            token,
            "POST",
            {"providerInstanceId": provider_id, "secretName": "api_key", "secretValue": "tokensea-e2e-mock-key"},
        )
    provider = api(f"/api/provider-instances/{provider_id}/test-connection", token, "POST")
    provider = api(f"/api/provider-instances/{provider_id}/status", token, "PATCH", {"status": "启用"})
    report.ok("供应商渠道", f"{PROVIDER_NAME} 已托管测试凭据、连接成功并启用")

    discovery = api(f"/api/provider-instances/{provider_id}/discover-models", token, "POST")
    deployments = api(f"/api/provider-instances/{provider_id}/deployments", token)
    deployment = find(deployments, "providerModelName", MODEL)
    if not deployment:
        raise E2EError(f"模型发现未生成 {MODEL} 部署")
    deployment_id = str(pick(deployment, "id"))
    report.ids["deploymentId"] = deployment_id
    if pick(deployment, "reviewStatus") != "APPROVED":
        deployment = api(
            f"/api/channel-model-deployments/{deployment_id}/review",
            token,
            "PATCH",
            {"decision": "APPROVE", "reason": "deepseek-v4-flash E2E"},
        )
    validation = api(
        f"/api/channel-model-deployments/{deployment_id}/probe",
        token,
        "POST",
        {"capabilityCode": "CHAT"},
    )
    if pick(validation, "status") != "PASSED":
        raise E2EError("CHAT 主动能力探测未通过")
    report.ok(
        "模型部署与能力",
        f"发现 {pick(discovery, 'discovered')} 个模型，{MODEL} 已审核且 LIVE_PROBE=PASSED",
    )

    sources = api("/api/provider-price-sources", token)
    source = next((row for row in sources if pick(row, "adapterCode") == "DEEPSEEK_OFFICIAL_PAGE"), None)
    if not source:
        raise E2EError("未找到 DeepSeek 官方价格源")
    source_id = str(pick(source, "id"))
    preview = api(f"/api/provider-price-sources/{source_id}/test", token, "POST", timeout=90)
    if int(pick(preview, "recordsNormalized", default=0)) <= 0:
        raise E2EError("DeepSeek 官方价格源未解析出价格")
    run = api(f"/api/provider-price-sources/{source_id}/sync", token, "POST")
    run = wait_price_run(str(pick(run, "id")), token)
    if pick(run, "status") == "FAILED":
        raise E2EError(f"价格同步失败: {pick(run, 'errorMessage')}")

    pending = page_items(api("/api/provider-price-diffs?status=PENDING&size=500", token))
    flash_pending = [row for row in pending if str(pick(row, "providerModelName", default="")).lower() == MODEL]
    if len(flash_pending) > 1:
        report.warn(f"{MODEL} 当前存在 {len(flash_pending)} 条重复待审核差异，预期最多为 1")
    if not flash_pending:
        catalogs = api("/api/provider-price-catalog", token)
        catalog = next((row for row in catalogs if str(pick(row, "providerModelName", default="")).lower() == MODEL and pick(row, "status") == "ACTIVE"), None)
        if not catalog:
            raise E2EError("价格同步后既没有待审核差异，也没有 ACTIVE 官方价格目录")
    else:
        diff = sorted(flash_pending, key=lambda row: str(pick(row, "createdAt", default="")), reverse=True)[0]
        approved = api(
            f"/api/provider-price-diffs/{pick(diff, 'id')}/approve",
            token,
            "POST",
            {"reason": "deepseek-v4-flash E2E 价格发布"},
        )
        report.ids["priceDiffId"] = str(pick(approved, "id"))

    prices = api("/api/price-versions?status=ACTIVE", token)
    price = next(
        (
            row
            for row in prices
            if str(pick(row, "providerModelName", default="")).lower() == MODEL
            and str(pick(row, "providerInstanceId", default="")) == provider_id
            and pick(row, "priceLayer") in {"PROVIDER_OFFICIAL", "CHANNEL_ACTUAL"}
        ),
        None,
    )
    if not price:
        raise E2EError("未生成与测试部署匹配的 ACTIVE 模型生效价格")
    price_id = str(pick(price, "id"))
    report.ids["priceVersionId"] = price_id
    report.ok(
        "价格生成",
        f"{MODEL} {pick(price, 'priceLayer')} V{pick(price, 'version')}，输入 {pick(price, 'inputUnitPrice')}，输出 {pick(price, 'outputUnitPrice')} {pick(price, 'currency')} / 每 {pick(price, 'billingQuantity', default=1000000)} {pick(price, 'billingBasis', default='TOKEN')}",
    )

    models = api("/api/platform-models", token)
    platform = find(models, "platformModelName", MODEL)
    model_payload = {
        "platformModelName": MODEL,
        "displayName": "DeepSeek V4 Flash E2E",
        "modelTemplateIds": "[]",
        "providerInstanceIds": json.dumps([provider_id]),
        "actualModels": json.dumps([MODEL]),
        "routePolicyId": pick(platform or {}, "routePolicyId"),
        "routePolicy": pick(platform or {}, "routePolicy"),
        "pricePolicyId": None,
        "pricePolicy": None,
        "visibilityScope": "全部租户",
        "approvalRequired": True,
    }
    if not platform:
        platform = api("/api/platform-models", token, "POST", model_payload)
    platform_id = str(pick(platform, "id"))
    report.ids["platformModelId"] = platform_id

    routes = api("/api/routes", token)
    route = find(routes, "name", ROUTE_NAME)
    route_payload = {
        "name": ROUTE_NAME,
        "modelAlias": MODEL,
        "strategy": "priority",
        "fallbackEnabled": False,
        "config": json.dumps(
            {
                "candidates": [
                    {
                        "providerInstanceId": provider_id,
                        "actualModel": MODEL,
                        "priceVersionId": price_id,
                        "priority": 1,
                        "weight": 100,
                        "timeoutSeconds": 30,
                        "maxRetries": 0,
                    }
                ]
            },
            ensure_ascii=False,
        ),
    }
    if not route:
        route = api("/api/routes", token, "POST", route_payload)
    elif pick(route, "status") == "DRAFT":
        route = api(f"/api/routes/{pick(route, 'id')}", token, "PUT", route_payload)
    route_id = str(pick(route, "id"))
    report.ids["routePolicyId"] = route_id
    if pick(route, "status") != "ACTIVE":
        approval = api(f"/api/routes/{route_id}/submit", token, "POST")
        api(
            f"/api/governance/approvals/{pick(approval, 'id')}/approve",
            token,
            "POST",
            {"decisionReason": "deepseek-v4-flash E2E 路由审批"},
        )
        route = api(f"/api/routes/{route_id}/activate", token, "POST")
    report.ok("路由策略", f"{ROUTE_NAME} 已生效，价格版本 {price_id}")

    model_payload["routePolicyId"] = route_id
    model_payload["routePolicy"] = ROUTE_NAME
    platform = api(f"/api/platform-models/{platform_id}", token)
    expected_providers = json.dumps([provider_id])
    expected_models = json.dumps([MODEL])
    needs_model_update = any(
        [
            pick(platform, "displayName") != model_payload["displayName"],
            pick(platform, "providerInstanceIds") != expected_providers,
            pick(platform, "actualModels") != expected_models,
            pick(platform, "routePolicyId") != route_id,
            pick(platform, "visibilityScope") != "全部租户",
        ]
    )
    if needs_model_update:
        platform = api(f"/api/platform-models/{platform_id}", token, "PUT", model_payload)
    if pick(platform, "status") != "已发布":
        approval = api(f"/api/platform-models/{platform_id}/submit", token, "POST")
        api(
            f"/api/governance/approvals/{pick(approval, 'id')}/approve",
            token,
            "POST",
            {"decisionReason": "deepseek-v4-flash E2E 模型发布审批"},
        )
        platform = api(f"/api/platform-models/{platform_id}/publish", token, "PATCH")
    report.ok("企业服务模型", f"{MODEL} 已发布并绑定 ACTIVE 路由")

    tenants = page_items(api("/api/tenants?size=500", token))
    tenant = find(tenants, "name", TENANT_NAME)
    if not tenant:
        tenant = api(
            "/api/tenants",
            token,
            "POST",
            {
                "name": TENANT_NAME,
                "type": "INTERNAL",
                "ownerName": "E2E",
                "contactEmail": None,
                "modelScope": json.dumps([MODEL]),
                "monthlyBudget": 10,
                "remark": "deepseek-v4-flash E2E",
            },
        )
    tenant_id = str(pick(tenant, "id"))
    report.ids["tenantId"] = tenant_id
    if pick(tenant, "status") != "ACTIVE":
        api(f"/api/tenants/{tenant_id}/activate", token, "POST")

    projects = api("/api/projects", token)
    project = find(projects, "name", PROJECT_NAME)
    if not project:
        project = api(
            "/api/projects",
            token,
            "POST",
            {"tenantId": tenant_id, "name": PROJECT_NAME, "ownerName": "E2E", "monthlyBudget": 5},
        )
    project_id = str(pick(project, "id"))
    if pick(project, "status") != "ACTIVE":
        api(f"/api/projects/{project_id}/status", token, "PATCH", {"status": "ACTIVE"})

    apps = api("/api/apps", token)
    app = find(apps, "name", APP_NAME)
    if not app:
        app = api(
            "/api/apps",
            token,
            "POST",
            {"tenantId": tenant_id, "projectId": project_id, "name": APP_NAME, "ownerName": "E2E", "environment": "TEST"},
        )
    app_id = str(pick(app, "id"))
    if pick(app, "status") != "ACTIVE":
        api(f"/api/apps/{app_id}/status", token, "PATCH", {"status": "ACTIVE"})
    report.ok("租户体系", f"租户、项目、应用均已启用（tenant={tenant_id[:8]}…）")

    keys = page_items(api("/api/keys?size=500", token))
    key = find(keys, "name", KEY_NAME)
    if not key:
        key = api(
            "/api/keys",
            token,
            "POST",
            {
                "tenantId": tenant_id,
                "projectId": project_id,
                "appId": app_id,
                "name": KEY_NAME,
                "modelScope": json.dumps([MODEL]),
                "budgetAmount": 2,
                "rpmLimit": 60,
                "tpmLimit": 100000,
                "qpsLimit": 5,
                "ipWhitelist": "[]",
                "expiresAt": "2027-07-16T00:00:00+08:00",
            },
        )
    key_id = str(pick(key, "id"))
    if pick(key, "approvalStatus") != "APPROVED":
        key = api(f"/api/keys/{key_id}/approve", token, "POST")
    generated = api(f"/api/keys/{key_id}/generate", token, "POST")
    plain_key = str(pick(generated, "plainTextKey"))
    if not plain_key.startswith("ts_"):
        raise E2EError("Virtual Key 生成结果格式不正确")
    report.ids["apiKeyId"] = key_id
    report.ok("Virtual Key", f"已审批并生成，前缀 {plain_key[:12]}…（明文未输出）")

    models_result, _ = request_json(
        f"{GATEWAY_BASE}/v1/models", token=plain_key, unwrap=False, timeout=30
    )
    visible = [row.get("id") for row in models_result.get("data", []) if isinstance(row, dict)]
    if MODEL not in visible:
        raise E2EError(f"/v1/models 未返回 {MODEL}: {visible}")
    report.ok("Key 模型范围", f"/v1/models 可见 {MODEL}")

    completion, headers = request_json(
        f"{GATEWAY_BASE}/v1/chat/completions",
        "POST",
        {
            "model": MODEL,
            "messages": [{"role": "user", "content": "请执行 TokenSea E2E 验证"}],
            "temperature": 0,
            "max_tokens": 32,
            "stream": False,
        },
        plain_key,
        90,
        False,
    )
    choices = completion.get("choices") if isinstance(completion, dict) else None
    content = choices[0].get("message", {}).get("content") if isinstance(choices, list) and choices else None
    if not content or "E2E" not in content:
        raise E2EError(f"Gateway 返回内容异常: {content}")
    request_id = headers.get("x-request-id")
    if not request_id:
        raise E2EError("Gateway 响应缺少 x-request-id")
    report.ids["requestId"] = request_id
    report.ok("真实 Gateway 调用", f"返回成功，requestId={request_id}")

    trace = api(f"/api/calls/{urllib.parse.quote(request_id)}", token)
    usage_rows = api("/api/usage", token)
    usage = next((row for row in usage_rows if pick(row, "requestId") == request_id), None)
    if not usage:
        raise E2EError("调用成功但 /api/usage 中没有对应记录")
    cost = pick(trace, "costSnapshot", default=[])
    if isinstance(cost, list):
        cost = cost[0] if cost else None
    if not cost:
        raise E2EError("调用成功但缺少成本快照")
    report.ok(
        "用量与成本",
        f"tokens={pick(usage, 'totalTokens')}，priceLayer={pick(cost, 'priceLayer')}，currency={pick(cost, 'currency')}",
    )

    output = root / "docs" / "testing" / "deepseek-v4-flash-e2e-result.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"steps": report.steps, "warnings": report.warnings, "ids": report.ids}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"E2E_RESULT={output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except E2EError as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        raise SystemExit(1) from None
