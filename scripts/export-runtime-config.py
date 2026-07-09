#!/usr/bin/env python3
"""Export TokenSea model deployments to runtime config.

This script does not contain provider keys. Generated config references environment variables
named PROVIDER_<PROVIDER_ID>_API_KEY. Operators must inject secrets securely.
"""
import os
import sys
import psycopg

DSN = os.getenv("TOKENSEA_DB_DSN", "postgresql://tokensea:tokensea_change_me@localhost:5432/tokensea")
OUT = os.getenv("TOKENSEA_RUNTIME_CONFIG_OUT", "configs/gateway/runtime.generated.yaml")

sql = """
SELECT m.alias, md.runtime_model_name, p.base_url, p.id AS provider_id
FROM model m
JOIN model_deployment md ON md.model_id=m.id
JOIN provider p ON p.id=md.provider_id
WHERE m.status='ACTIVE' AND md.status='ACTIVE' AND p.status='ACTIVE'
ORDER BY m.alias, md.priority ASC, md.weight DESC
"""

def env_name(provider_id: str) -> str:
    return "PROVIDER_" + provider_id.replace('-', '_').upper() + "_API_KEY"

with psycopg.connect(DSN) as conn, conn.cursor() as cur:
    cur.execute(sql)
    rows = cur.fetchall()
lines = ["model_list:"]
for alias, runtime_model_name, base_url, provider_id in rows:
    lines += [
        f"  - model_name: {alias}",
        "    litellm_params:",
        f"      model: {runtime_model_name}",
    ]
    if base_url:
        lines.append(f"      api_base: {base_url}")
    lines.append(f"      api_key: os.environ/{env_name(provider_id)}")
lines += [
    "general_settings:",
    "  master_key: os.environ/TOKENSEA_RUNTIME_ENGINE_KEY",
    "litellm_settings:",
    "  request_timeout: 120",
    "  drop_params: true",
]
os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, "w", encoding="utf-8").write("\n".join(lines)+"\n")
print(f"written {OUT}; models={len(rows)}")
