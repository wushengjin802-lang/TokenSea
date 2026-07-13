#!/usr/bin/env python3
import os
from openai import OpenAI
base_url = os.getenv("TOKENSEA_GATEWAY_BASE", "http://localhost:39212/v1")
api_key = os.getenv("TOKENSEA_API_KEY")
model = os.getenv("TOKENSEA_EMBEDDING_MODEL")
if not api_key:
    raise SystemExit("请设置 TOKENSEA_API_KEY，脚本不内置任何预设 Key")
if not model:
    raise SystemExit("请设置 TOKENSEA_EMBEDDING_MODEL，模型必须来自当前 Key 可访问的 /v1/models")
client = OpenAI(base_url=base_url, api_key=api_key)
print(client.embeddings.create(model=model, input="hello"))
