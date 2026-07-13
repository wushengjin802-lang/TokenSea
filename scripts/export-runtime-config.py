#!/usr/bin/env python3
"""Export published TokenSea service models without embedding provider secrets."""
import json
import os
import psycopg

DSN = os.getenv("TOKENSEA_DB_DSN")
OUT = os.getenv("TOKENSEA_RUNTIME_CONFIG_OUT", "configs/gateway/runtime.generated.yaml")
if not DSN:
    raise SystemExit("请设置 TOKENSEA_DB_DSN；脚本不内置数据库口令")

sql = """
SELECT pm.platform_model_name, pm.actual_models, pm.provider_instance_ids,
       pi.id, pi.api_style, pi.api_base, pi.key_status
FROM platform_model pm
JOIN provider_instance pi ON pi.id = ANY(
  ARRAY(SELECT jsonb_array_elements_text(pm.provider_instance_ids::jsonb))
)
WHERE pm.status='已发布'
  AND pi.status IN ('启用','已启用','ACTIVE')
  AND pi.health_status='健康'
  AND pi.last_connection_test_status='成功'
ORDER BY pm.platform_model_name, pi.created_at
"""

def env_name(instance_id: str) -> str:
    return "PROVIDER_INSTANCE_" + instance_id.replace('-', '_').upper() + "_API_KEY"

def runtime_model(api_style: str, model: str) -> str:
    if "/" in model:
        return model
    style = (api_style or "").lower()
    prefix = "anthropic" if "anthropic" in style else "gemini" if "gemini" in style else "azure" if "azure" in style else "openai"
    return f"{prefix}/{model}"

with psycopg.connect(DSN) as conn, conn.cursor() as cur:
    cur.execute(sql)
    rows = cur.fetchall()

lines = ["model_list:"]
for alias, actual_json, instance_json, instance_id, api_style, api_base, key_status in rows:
    actual_models = json.loads(actual_json)
    instance_ids = json.loads(instance_json)
    for index, actual in enumerate(actual_models):
        mapped_id = instance_ids[0] if len(instance_ids) == 1 else instance_ids[index] if index < len(instance_ids) else None
        if mapped_id != instance_id:
            continue
        lines += [
            f"  - model_name: {json.dumps(alias, ensure_ascii=False)}",
            "    litellm_params:",
            f"      model: {json.dumps(runtime_model(api_style, actual), ensure_ascii=False)}",
            f"      api_base: {json.dumps(api_base, ensure_ascii=False)}",
        ]
        if key_status != "无需 Key":
            lines.append(f"      api_key: os.environ/{env_name(instance_id)}")
lines += [
    "general_settings:",
    "  master_key: os.environ/TOKENSEA_RUNTIME_ENGINE_KEY",
    "litellm_settings:",
    "  request_timeout: 120",
    "  drop_params: true",
]
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as output:
    output.write("\n".join(lines) + "\n")
print(f"written {OUT}; deployments={max(0, len(lines) - 7)}")
