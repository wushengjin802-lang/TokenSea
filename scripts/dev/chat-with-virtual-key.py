#!/usr/bin/env python3
"""使用 TokenSea Virtual Key 进行连续多轮对话。"""

from __future__ import annotations

import argparse
import getpass
import importlib.util
import json
import os
import sys
import urllib.request
from pathlib import Path
from typing import Any


class ChatError(RuntimeError):
    pass


def load_verify_module() -> Any:
    path = Path(__file__).with_name("verify-virtual-key.py")
    spec = importlib.util.spec_from_file_location("tokensea_verify_virtual_key", path)
    if spec is None or spec.loader is None:
        raise ChatError(f"无法加载验证模块：{path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def extract_content(payload: dict[str, Any]) -> str:
    choices = payload.get("choices")
    if isinstance(choices, list) and choices and isinstance(choices[0], dict):
        message = choices[0].get("message")
        if isinstance(message, dict):
            content = message.get("content")
            if isinstance(content, str) and content.strip():
                return content.strip()
    raise ChatError("模型调用返回成功，但未解析到回复内容")


def trim_history(messages: list[dict[str, str]], max_rounds: int) -> None:
    if max_rounds <= 0:
        return
    system = messages[:1] if messages and messages[0].get("role") == "system" else []
    normal = messages[len(system):]
    if len(normal) > max_rounds * 2:
        messages[:] = system + normal[-max_rounds * 2:]


def show_history(messages: list[dict[str, str]]) -> None:
    rows = [row for row in messages if row.get("role") != "system"]
    if not rows:
        print("当前还没有对话记录。")
        return
    for index, row in enumerate(rows, start=1):
        role = "你" if row.get("role") == "user" else "助手"
        print(f"[{index}] {role}：{row.get('content', '')}")


def save_history(path_value: str, model: str, messages: list[dict[str, str]]) -> Path:
    path = Path(path_value).expanduser().resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps({"model": model, "messages": messages}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return path


def print_help() -> None:
    print(
        "可用命令：\n"
        "  /help             查看帮助\n"
        "  /clear            清空会话上下文\n"
        "  /history          查看当前对话\n"
        "  /save [文件路径]  保存会话 JSON，不保存 Virtual Key\n"
        "  /exit             退出"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="使用 TokenSea Virtual Key 进行连续多轮对话")
    parser.add_argument(
        "--gateway-base",
        default=os.getenv("TOKENSEA_GATEWAY_BASE", "http://localhost:39212"),
        help="Gateway 根地址或 /v1 地址",
    )
    parser.add_argument("--api-key", default=os.getenv("TOKENSEA_API_KEY"), help="Virtual Key；省略时安全输入")
    parser.add_argument("--model", default=os.getenv("TOKENSEA_MODEL"), help="企业服务模型别名")
    parser.add_argument("--system-prompt", default="你是一个专业、准确、简洁的中文助手。")
    parser.add_argument("--temperature", type=float, default=0.7)
    parser.add_argument("--max-tokens", type=int, default=1024)
    parser.add_argument("--max-rounds", type=int, default=20, help="保留最近多少轮；0 表示不裁剪")
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--save-file", help="退出时自动保存会话 JSON")
    args = parser.parse_args()

    if not args.model:
        raise ChatError("请通过 --model 或 TOKENSEA_MODEL 指定企业服务模型别名")
    if args.max_tokens <= 0 or args.max_rounds < 0:
        raise ChatError("max-tokens 必须大于 0，max-rounds 不能小于 0")

    verify = load_verify_module()
    api_key = (args.api_key or getpass.getpass("请输入 TokenSea Virtual Key（不会回显）：")).strip()
    if not api_key:
        raise ChatError("Virtual Key 不能为空")

    gateway_root, api_base = verify.api_urls(args.gateway_base)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    health = verify.request_json(opener, "GET", f"{gateway_root}/health", args.timeout)
    if health.get("status") != "ok":
        raise ChatError(f"Gateway 状态异常：{health}")

    models = verify.model_ids(
        verify.request_json(opener, "GET", f"{api_base}/models", args.timeout, api_key)
    )
    if args.model not in models:
        visible = "、".join(models) if models else "无"
        raise ChatError(f"当前 Key 无权访问模型“{args.model}”；可访问模型：{visible}")

    messages: list[dict[str, str]] = []
    if args.system_prompt.strip():
        messages.append({"role": "system", "content": args.system_prompt.strip()})

    print(f"已连接：{gateway_root}")
    print(f"当前模型：{args.model}")
    print("输入 /help 查看命令，输入 /exit 退出。")

    try:
        while True:
            try:
                user_input = input("\n你：").strip()
            except EOFError:
                print()
                break
            except KeyboardInterrupt:
                print("\n已取消当前输入。")
                continue

            if not user_input:
                continue
            if user_input == "/exit":
                break
            if user_input == "/help":
                print_help()
                continue
            if user_input == "/clear":
                messages = messages[:1] if messages and messages[0].get("role") == "system" else []
                print("会话上下文已清空。")
                continue
            if user_input == "/history":
                show_history(messages)
                continue
            if user_input.startswith("/save"):
                target = user_input[5:].strip() or args.save_file or "tokensea-chat-session.json"
                print(f"会话已保存：{save_history(target, args.model, messages)}")
                continue
            if user_input.startswith("/"):
                print("未知命令。输入 /help 查看命令。")
                continue

            messages.append({"role": "user", "content": user_input})
            trim_history(messages, args.max_rounds)
            try:
                completion = verify.request_json(
                    opener,
                    "POST",
                    f"{api_base}/chat/completions",
                    args.timeout,
                    api_key,
                    {
                        "model": args.model,
                        "messages": messages,
                        "temperature": args.temperature,
                        "max_tokens": args.max_tokens,
                        "stream": False,
                    },
                )
                content = extract_content(completion)
                messages.append({"role": "assistant", "content": content})
                trim_history(messages, args.max_rounds)
                print(f"助手：{content}")
                usage = completion.get("usage")
                if isinstance(usage, dict):
                    print(
                        "[Token] "
                        f"prompt={usage.get('prompt_tokens', '—')}，"
                        f"completion={usage.get('completion_tokens', '—')}，"
                        f"total={usage.get('total_tokens', '—')}"
                    )
                if completion.get("id"):
                    print(f"[请求标识] {completion['id']}")
            except Exception as exc:
                if messages and messages[-1].get("role") == "user":
                    messages.pop()
                print(f"调用失败：{exc}", file=sys.stderr)
    finally:
        if args.save_file:
            print(f"会话已保存：{save_history(args.save_file, args.model, messages)}")

    print("对话已结束。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"启动失败：{exc}", file=sys.stderr)
        raise SystemExit(1) from None
