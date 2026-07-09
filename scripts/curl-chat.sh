#!/usr/bin/env bash
set -euo pipefail
: "${TOKENSEA_API_KEY:?请设置 TOKENSEA_API_KEY}"
: "${TOKENSEA_MODEL:=deepseek-chat}"
: "${TOKENSEA_GATEWAY_BASE:=http://localhost:4000}"
curl "$TOKENSEA_GATEWAY_BASE/v1/chat/completions" \
  -H "Authorization: Bearer $TOKENSEA_API_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"$TOKENSEA_MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}"
