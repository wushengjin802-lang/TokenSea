-- Switch the built-in DeepSeek official price source from the global USD page
-- to the Chinese CNY page. Existing published USD catalog versions and usage
-- snapshots remain immutable; the next sync creates reviewable CURRENCY_CHANGED
-- diffs before any CNY catalog version is published.

UPDATE provider_price_diff
SET status = 'IGNORED',
    decision_reason = 'DeepSeek 官方价格源切换至中文人民币页面，旧页面待审核差异失效',
    decided_by = 'SYSTEM',
    decided_at = now(),
    updated_at = now()
WHERE price_source_id = 'builtin_deepseek_official_price'
  AND status = 'PENDING';

UPDATE provider_price_source
SET endpoint = 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/',
    official_hosts = '["api-docs.deepseek.com"]'::jsonb,
    region = 'global',
    default_currency = 'CNY',
    auto_publish = false,
    config = coalesce(config, '{}'::jsonb)
      || '{"official":true,"locale":"zh-CN","expectedCurrency":"CNY"}'::jsonb,
    etag = NULL,
    last_modified = NULL,
    last_content_hash = NULL,
    last_error = NULL,
    parser_version = '2.0.0',
    next_run_at = CASE WHEN status = 'ACTIVE' THEN now() ELSE next_run_at END,
    updated_by = 'SYSTEM',
    updated_at = now()
WHERE id = 'builtin_deepseek_official_price';
