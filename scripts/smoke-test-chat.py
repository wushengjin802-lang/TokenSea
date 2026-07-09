#!/usr/bin/env python3
import os
from openai import OpenAI

base_url = os.getenv("TOKENSEA_GATEWAY_BASE", "http://localhost:4000/v1")
api_key = os.getenv("TOKENSEA_API_KEY")
model = os.getenv("TOKENSEA_MODEL", "deepseek-chat")
if not api_key:
    raise SystemExit("请设置 TOKENSEA_API_KEY，脚本不内置任何预设 Key")
client = OpenAI(base_url=base_url, api_key=api_key)
resp = client.chat.completions.create(model=model, messages=[{"role":"user","content":"ping"}])
print(resp)
