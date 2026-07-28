#!/usr/bin/env python3
"""验证 TokenSea Virtual Key、模型权限和真实对话调用。"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any


class VerificationError(RuntimeError):
    pass


def api_urls(gateway_base: str) -> tuple[str, str]:
    value = gateway_base.strip().rstrip("/")
    if not value:
        raise VerificationError("Gateway 地址不能为空")
    if value.endswith("/v1"):
        return value[:-3], value
    return value, f"{value}/v1"


def error_detail(status: int, body: bytes, request_id: str | None = None) -> str:
    text = body.decode("utf-8", errors="replace").strip()
    result = f"HTTP {status}"
    if text:
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            result = f"HTTP {status}：{text[:500]}"
        else:
            detail = payload.get("detail") if isinstance(payload, dict) else None
            if isinstance(detail, dict):
                code = detail.get("error_code") or detail.get("code")
                message = detail.get("message") or detail.get("detail")
                if code and message:
                    result = f"HTTP {status} {code}：{message}"
                elif message:
                    result = f"HTTP {status}：{message}"
            elif isinstance(detail, str):
                result = f"HTTP {status}：{detail}"
            elif isinstance(payload, dict):
                error = payload.get("error")
                if isinstance(error, dict):
                    code = error.get("code")
                    message = error.get("message")
                    if code and message:
                        result = f"HTTP {status} {code}：{message}"
                    elif message:
                        result = f"HTTP {status}：{message}"
                elif payload.get("message"):
                    result = f"HTTP {status}：{payload['message']}"
                else:
                    result = f"HTTP {status}：{text[:500]}"
    return f"{result}；请求 ID：{request_id}" if request_id else result


def request_json(
    opener: urllib.request.OpenerDirector,
    method: str,
    url: str,
    timeout: float,
    api_key: str | None = None,
    payload: dict[str, Any] | None = None,
) -> dict[str, Any]:
    headers = {"Accept": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    body = None
    if payload is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with opener.open(request, timeout=timeout) as response:
            raw = response.read()
    except urllib.error.HTTPError as exc:
        request_id = exc.headers.get("x-request-id") if exc.headers else None
        raise VerificationError(error_detail(exc.code, exc.read(), request_id)) from None
    except urllib.error.URLError as exc:
        raise VerificationError(f"无法连接 {url}：{exc.reason}") from None
    except TimeoutError:
        raise VerificationError(f"请求超时：{url}") from None
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise VerificationError(f"接口未返回有效 JSON：{url}") from None
    if not isinstance(value, dict):
        raise VerificationError(f"接口返回格式不正确：{url}")
    return value


def model_ids(payload: dict[str, Any]) -> list[str]:
    rows = payload.get("data", [])
    if not isinstance(rows, list):
        return []
    values: list[str] = []
    for row in rows:
        if isinstance(row, str):
            model = row
        elif isinstance(row, dict):
            model = row.get("id") or row.get("model")
        else:
            model = None
        if isinstance(model, str) and model.strip() and model not in values:
            values.append(model)
    return values


def main() -> int:
    parser = argparse.ArgumentParser(description="验证 TokenSea Virtual Key 和真实模型调用")
    parser.add_argument(
        "--gateway-base",
        default=os.getenv("TOKENSEA_GATEWAY_BASE", "http://localhost:39212"),
        help="Gateway 根地址或 /v1 地址",
    )
    parser.add_argument("--api-key", default=os.getenv("TOKENSEA_API_KEY"), help="Virtual Key；省略时安全输入")
    parser.add_argument("--model", default=os.getenv("TOKENSEA_MODEL"), help="企业服务模型别名")
    parser.add_argument("--prompt", default="请只回复：TokenSea Key 验证成功", help="真实调用测试消息")
    parser.add_argument("--timeout", type=float, default=60, help="单次请求超时秒数")
    parser.add_argument("--skip-chat", action="store_true", help="只验证 Key 和模型权限，不调用上游模型")
    args = parser.parse_args()

    api_key = args.api_key or getpass.getpass("请输入 TokenSea Virtual Key（不会回显）：")
    if not api_key.strip():
        raise VerificationError("Virtual Key 不能为空")
    if not args.model:
        raise VerificationError("请通过 --model 或 TOKENSEA_MODEL 指定企业服务模型别名")

    gateway_root, api_base = api_urls(args.gateway_base)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    print(f"[1/3] 检查 Gateway：{gateway_root}/health")
    health = request_json(opener, "GET", f"{gateway_root}/health", args.timeout)
    print(f"      状态：{health.get('status', '未知')}")

    print(f"[2/3] 验证 Key 并读取可访问模型：{api_base}/models")
    models = model_ids(request_json(opener, "GET", f"{api_base}/models", args.timeout, api_key))
    if not models:
        raise VerificationError(
            "Key 鉴权已通过，但没有可访问模型；请检查企业服务模型是否已发布、路由是否启用，以及租户和 Key 的模型范围"
        )
    print("      可访问模型：" + "、".join(models))
    if args.model not in models:
        raise VerificationError(f"当前 Key 无权访问模型“{args.model}”")
    if args.skip_chat:
        print(f"验证成功：Virtual Key 有效，且可访问模型“{args.model}”；已跳过真实调用。")
        return 0

    print(f"[3/3] 真实调用企业服务模型：{args.model}")
    completion = request_json(
        opener,
        "POST",
        f"{api_base}/chat/completions",
        args.timeout,
        api_key,
        {
            "model": args.model,
            "messages": [{"role": "user", "content": args.prompt}],
            "temperature": 0,
            "max_tokens": 64,
            "stream": False,
        },
    )
    choices = completion.get("choices")
    content = None
    if isinstance(choices, list) and choices and isinstance(choices[0], dict):
        message = choices[0].get("message")
        if isinstance(message, dict):
            content = message.get("content")
    if not isinstance(content, str) or not content.strip():
        raise VerificationError("模型调用返回成功，但未解析到 choices[0].message.content")
    print(f"      模型回复：{content}")
    usage = completion.get("usage")
    if isinstance(usage, dict):
        print(
            "      Token 用量："
            f"prompt={usage.get('prompt_tokens', '—')}，"
            f"completion={usage.get('completion_tokens', '—')}，"
            f"total={usage.get('total_tokens', '—')}"
        )
    if completion.get("id"):
        print(f"      请求标识：{completion['id']}")
    print(f"验证成功：Virtual Key 可用，模型“{args.model}”已完成真实调用。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationError as exc:
        print(f"验证失败：{exc}", file=sys.stderr)
        raise SystemExit(1) from None
