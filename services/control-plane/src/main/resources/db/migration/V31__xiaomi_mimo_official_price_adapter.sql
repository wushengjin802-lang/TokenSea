-- Xiaomi MiMo official price adapter, built-in provider metadata and paused official source.
-- Forward-only migration. Existing price sources, prices, deployments and usage snapshots are preserved.

ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_adapter;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_adapter CHECK(
  adapter_code IN (
    'LITELLM_COST_MAP','MODELS_DEV','DEEPSEEK_OFFICIAL_PAGE','QWEN_OFFICIAL_PAGE',
    'KIMI_OFFICIAL_PAGE','XIAOMI_MIMO_OFFICIAL_PAGE','OFFICIAL_JSON','OFFICIAL_CSV'
  )
);

INSERT INTO provider_template (
  id, provider_name, provider_type, protocol, default_api_base, auth_type,
  supported_endpoints, error_mapping, health_check_path,
  default_rate_limit_rpm, default_rate_limit_tpm, model_template_count,
  built_in, status, description)
VALUES (
  'tpl_xiaomi_mimo', 'Xiaomi MiMo', 'xiaomi_mimo', 'OpenAI-compatible',
  'https://api.xiaomimimo.com/v1', 'Bearer Token',
  '["Chat", "Responses", "Audio"]', '{}', '/models',
  600, 300000, 2, '是', '未启用',
  'Xiaomi MiMo 开放平台；支持 OpenAI-compatible 对话、Responses、ASR 与 TTS。')
ON CONFLICT (id) DO UPDATE SET
  provider_name = EXCLUDED.provider_name,
  provider_type = EXCLUDED.provider_type,
  protocol = EXCLUDED.protocol,
  default_api_base = EXCLUDED.default_api_base,
  auth_type = EXCLUDED.auth_type,
  supported_endpoints = EXCLUDED.supported_endpoints,
  error_mapping = EXCLUDED.error_mapping,
  health_check_path = EXCLUDED.health_check_path,
  default_rate_limit_rpm = EXCLUDED.default_rate_limit_rpm,
  default_rate_limit_tpm = EXCLUDED.default_rate_limit_tpm,
  model_template_count = EXCLUDED.model_template_count,
  built_in = EXCLUDED.built_in,
  description = EXCLUDED.description;

INSERT INTO model_template (
  id, provider_template_id, provider_name, provider_model_name, default_display_name,
  context_length, supported_endpoints, supports_streaming, supports_tools,
  capability_tags, default_cost_level, default_quality_level, compliance_tags,
  built_in, status)
VALUES
  ('mt_xiaomi_mimo_v2_5_pro', 'tpl_xiaomi_mimo', 'Xiaomi MiMo', 'mimo-v2.5-pro',
   'MiMo V2.5 Pro', NULL, '["Chat", "Responses"]', true, true,
   '["文本生成", "代码生成", "推理", "智能体", "国产化", "高质量", "工具调用"]',
   '中', '旗舰', '["国产化"]', '是', '可发布'),
  ('mt_xiaomi_mimo_v2_5', 'tpl_xiaomi_mimo', 'Xiaomi MiMo', 'mimo-v2.5',
   'MiMo V2.5', NULL, '["Chat", "Responses"]', true, true,
   '["文本生成", "代码生成", "智能体", "国产化", "低成本", "工具调用"]',
   '低', '高质量', '["国产化"]', '是', '可发布')
ON CONFLICT (id) DO UPDATE SET
  provider_template_id = EXCLUDED.provider_template_id,
  provider_name = EXCLUDED.provider_name,
  provider_model_name = EXCLUDED.provider_model_name,
  default_display_name = EXCLUDED.default_display_name,
  context_length = EXCLUDED.context_length,
  supported_endpoints = EXCLUDED.supported_endpoints,
  supports_streaming = EXCLUDED.supports_streaming,
  supports_tools = EXCLUDED.supports_tools,
  capability_tags = EXCLUDED.capability_tags,
  default_cost_level = EXCLUDED.default_cost_level,
  default_quality_level = EXCLUDED.default_quality_level,
  compliance_tags = EXCLUDED.compliance_tags,
  built_in = EXCLUDED.built_in,
  status = EXCLUDED.status;

UPDATE provider_template
SET model_template_count = (
  SELECT COUNT(*)::int
  FROM model_template
  WHERE provider_template_id = 'tpl_xiaomi_mimo'
)
WHERE id = 'tpl_xiaomi_mimo';

INSERT INTO provider_price_source(
  id, name, source_class, adapter_code, provider_type, auth_mode,
  endpoint, official_hosts, region, default_currency, schedule_expression,
  auto_publish, max_auto_change_ratio, confirmation_runs, config, status,
  next_run_at, parser_version, fetch_mode, source_priority, price_nature)
VALUES (
  'builtin_xiaomi_mimo_cn_official_price',
  'Xiaomi MiMo 中国区官方价格',
  'OFFICIAL',
  'XIAOMI_MIMO_OFFICIAL_PAGE',
  'xiaomi_mimo',
  'NONE',
  'https://mimo.mi.com/docs/zh-CN/price/pay-as-you-go',
  '["mimo.mi.com"]',
  'cn',
  'CNY',
  'P1D',
  true,
  0.1000,
  2,
  '{"official":true,"scope":"TEXT_REALTIME_STANDARD","publishOriginalOnly":true,"cacheWritePolicy":"LIMITED_TIME_FREE","ignoredBillingFamilies":["ASR","TTS","WEB_SEARCH"]}',
  'PAUSED',
  NULL,
  '1.0.0',
  'AUTO',
  100,
  'ORIGINAL')
ON CONFLICT (id) DO NOTHING;
