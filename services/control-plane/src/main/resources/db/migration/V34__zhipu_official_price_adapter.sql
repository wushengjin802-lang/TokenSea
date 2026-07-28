-- Zhipu BigModel official price adapter and paused built-in CNY source.
-- The price page is JavaScript rendered, so the source intentionally uses the isolated Headless Fetcher.

ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_adapter;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_adapter CHECK(
  adapter_code IN (
    'LITELLM_COST_MAP','MODELS_DEV','DEEPSEEK_OFFICIAL_PAGE','QWEN_OFFICIAL_PAGE',
    'KIMI_OFFICIAL_PAGE','XIAOMI_MIMO_OFFICIAL_PAGE','ZHIPU_OFFICIAL_PAGE',
    'OFFICIAL_JSON','OFFICIAL_CSV'
  )
);

INSERT INTO model_template (
  id, provider_template_id, provider_name, provider_model_name, default_display_name,
  context_length, supported_endpoints, supports_streaming, supports_tools,
  capability_tags, default_cost_level, default_quality_level, compliance_tags,
  built_in, status)
VALUES
  ('mt_glm_5_1', 'tpl_zhipu', 'Z.AI / 智谱 GLM', 'glm-5.1', 'GLM-5.1', NULL,
   '["Chat", "Responses"]', true, true,
   '["文本生成", "代码生成", "长上下文", "智能体", "国产化", "高质量", "缓存"]',
   '高', '旗舰', '["国产化"]', '是', '可发布'),
  ('mt_glm_4_5_air', 'tpl_zhipu', 'Z.AI / 智谱 GLM', 'glm-4.5-air', 'GLM-4.5 Air', 128000,
   '["Chat", "Responses"]', true, true,
   '["文本生成", "代码生成", "智能体", "国产化", "低成本", "缓存"]',
   '低', '高质量', '["国产化"]', '是', '可发布'),
  ('mt_glm_4_7_flashx', 'tpl_zhipu', 'Z.AI / 智谱 GLM', 'glm-4.7-flashx', 'GLM-4.7 FlashX', 200000,
   '["Chat", "Responses"]', true, true,
   '["文本生成", "低延迟", "低成本", "国产化", "缓存"]',
   '低', '高质量', '["国产化"]', '是', '可发布'),
  ('mt_glm_5v_turbo', 'tpl_zhipu', 'Z.AI / 智谱 GLM', 'glm-5v-turbo', 'GLM-5V Turbo', NULL,
   '["Chat", "Images", "Video"]', true, true,
   '["多模态", "视觉理解", "视频理解", "国产化", "高质量", "缓存"]',
   '高', '旗舰', '["国产化"]', '是', '可发布'),
  ('mt_glm_4_6v_flashx', 'tpl_zhipu', 'Z.AI / 智谱 GLM', 'glm-4.6v-flashx', 'GLM-4.6V FlashX', 128000,
   '["Chat", "Images", "Video"]', true, true,
   '["多模态", "视觉理解", "视频理解", "国产化", "低成本", "缓存"]',
   '低', '高质量', '["国产化"]', '是', '可发布'),
  ('mt_glm_4_6v_flash', 'tpl_zhipu', 'Z.AI / 智谱 GLM', 'glm-4.6v-flash', 'GLM-4.6V Flash', 128000,
   '["Chat", "Images", "Video"]', true, true,
   '["多模态", "视觉理解", "视频理解", "国产化", "免费", "缓存"]',
   '低', '普通', '["国产化"]', '是', '可发布'),
  ('mt_glm_4_5v', 'tpl_zhipu', 'Z.AI / 智谱 GLM', 'glm-4.5v', 'GLM-4.5V', 64000,
   '["Chat", "Images", "Video"]', true, true,
   '["多模态", "视觉理解", "视频理解", "国产化", "缓存"]',
   '中', '高质量', '["国产化"]', '是', '可发布')
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
  SELECT COUNT(*)::int FROM model_template WHERE provider_template_id = 'tpl_zhipu'
),
    description = 'Z.AI / 智谱 BigModel 开放平台；支持 GLM 文本、代码、智能体、视觉和视频理解模型。'
WHERE id = 'tpl_zhipu';

INSERT INTO provider_price_source(
  id, name, source_class, adapter_code, provider_type, auth_mode,
  endpoint, official_hosts, region, default_currency, schedule_expression,
  auto_publish, max_auto_change_ratio, confirmation_runs, config, status,
  next_run_at, parser_version, fetch_mode, source_priority, price_nature)
VALUES (
  'builtin_zhipu_cn_official_price',
  '智谱中国区官方价格',
  'OFFICIAL',
  'ZHIPU_OFFICIAL_PAGE',
  'zhipu',
  'NONE',
  'https://bigmodel.cn/pricing',
  '["bigmodel.cn"]',
  'cn',
  'CNY',
  'P1D',
  true,
  0.1000,
  2,
  '{"official":true,"scope":"EXPLICIT_INPUT_OUTPUT_TOKEN","publishOriginalOnly":true,"requiresHeadless":true,"cacheStorageUnit":"MILLION_TOKEN_HOUR","ignoredBillingFamilies":["LEGACY_COMBINED_PRICING","SEARCH","KNOWLEDGE_BASE","FINE_TUNING","PRIVATE_INSTANCE","PRIVATE_DEPLOYMENT"]}',
  'PAUSED',
  NULL,
  '1.0.0',
  'HEADLESS',
  100,
  'ORIGINAL')
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  source_class = EXCLUDED.source_class,
  adapter_code = EXCLUDED.adapter_code,
  provider_type = EXCLUDED.provider_type,
  auth_mode = EXCLUDED.auth_mode,
  endpoint = EXCLUDED.endpoint,
  official_hosts = EXCLUDED.official_hosts,
  region = EXCLUDED.region,
  default_currency = EXCLUDED.default_currency,
  schedule_expression = EXCLUDED.schedule_expression,
  auto_publish = EXCLUDED.auto_publish,
  max_auto_change_ratio = EXCLUDED.max_auto_change_ratio,
  confirmation_runs = EXCLUDED.confirmation_runs,
  config = EXCLUDED.config,
  parser_version = EXCLUDED.parser_version,
  fetch_mode = EXCLUDED.fetch_mode,
  source_priority = EXCLUDED.source_priority,
  price_nature = EXCLUDED.price_nature,
  updated_at = now();
