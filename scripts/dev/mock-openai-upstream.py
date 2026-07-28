#!/usr/bin/env python3
"""Deterministic OpenAI-compatible upstream used only for TokenSea local E2E tests."""

from __future__ import annotations

import argparse
import json
import time
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

MODEL = "deepseek-v4-flash"


class Handler(BaseHTTPRequestHandler):
    server_version = "TokenSeaMockUpstream/1.0"

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"[{self.log_date_time_string()}] {self.address_string()} {fmt % args}")

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b"{}"
        value = json.loads(raw.decode("utf-8"))
        return value if isinstance(value, dict) else {}

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path in {"/health", "/v1/health"}:
            self._json(HTTPStatus.OK, {"status": "ok", "service": "tokensea-mock-upstream"})
            return
        if self.path.rstrip("/") == "/v1/models":
            self._json(
                HTTPStatus.OK,
                {
                    "object": "list",
                    "data": [
                        {
                            "id": MODEL,
                            "object": "model",
                            "created": int(time.time()),
                            "owned_by": "deepseek-mock",
                        }
                    ],
                },
            )
            return
        self._json(HTTPStatus.NOT_FOUND, {"error": {"message": "not found", "code": "mock_not_found"}})

    def do_POST(self) -> None:  # noqa: N802
        if self.path.rstrip("/") == "/v1/chat/completions":
            request = self._read_json()
            if request.get("stream"):
                self._stream_chat(request)
            else:
                self._chat(request)
            return
        if self.path.rstrip("/") == "/v1/embeddings":
            request = self._read_json()
            self._json(
                HTTPStatus.OK,
                {
                    "object": "list",
                    "model": request.get("model") or MODEL,
                    "data": [{"object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3]}],
                    "usage": {"prompt_tokens": 3, "total_tokens": 3},
                },
            )
            return
        self._json(HTTPStatus.NOT_FOUND, {"error": {"message": "not found", "code": "mock_not_found"}})

    def _chat(self, request: dict[str, Any]) -> None:
        model = str(request.get("model") or MODEL)
        content = "TokenSea deepseek-v4-flash E2E mock call succeeded"
        self._json(
            HTTPStatus.OK,
            {
                "id": f"chatcmpl-{uuid.uuid4().hex}",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": model,
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": content},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20},
            },
        )

    def _stream_chat(self, request: dict[str, Any]) -> None:
        model = str(request.get("model") or MODEL)
        completion_id = f"chatcmpl-{uuid.uuid4().hex}"
        chunks = [
            {
                "id": completion_id,
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": model,
                "choices": [{"index": 0, "delta": {"role": "assistant", "content": "OK"}, "finish_reason": None}],
            },
            {
                "id": completion_id,
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": model,
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 6, "completion_tokens": 1, "total_tokens": 7},
            },
        ]
        body = b"".join(f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n".encode("utf-8") for chunk in chunks)
        body += b"data: [DONE]\n\n"
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    parser = argparse.ArgumentParser(description="TokenSea local OpenAI-compatible mock upstream")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=39301)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"TokenSea mock upstream listening on http://{args.host}:{args.port}/v1")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
