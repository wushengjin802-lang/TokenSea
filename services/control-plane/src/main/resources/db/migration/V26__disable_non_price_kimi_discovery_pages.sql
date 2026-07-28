-- Kimi 定价文档导航中包含“产品导航、联网搜索、充值限速、促销活动”等辅助页面。
-- 这些页面不是当前 KIMI_OFFICIAL_PAGE 适配器支持的模型 Token 定价页，
-- 不应被自动创建为价格源，也不应因没有模型输入/输出价格而产生同步失败告警。

WITH non_price_sources AS (
  SELECT id
  FROM provider_price_source
  WHERE adapter_code = 'KIMI_OFFICIAL_PAGE'
    AND config->>'autoDiscovered' = 'true'
    AND endpoint ~ '^https://platform\.kimi\.com/docs/pricing/(chat|tools|limits|promotion)/?$'
)
UPDATE provider_price_source s
SET status = 'DISABLED',
    next_run_at = NULL,
    last_error = 'V26 已禁用 Kimi 非模型 Token 定价辅助页面',
    config = jsonb_set(
      coalesce(s.config, '{}'::jsonb),
      '{disabledReason}',
      to_jsonb('KIMI_NON_MODEL_PRICING_PAGE'::text),
      true
    ),
    updated_by = 'SYSTEM',
    updated_at = now()
WHERE s.id IN (SELECT id FROM non_price_sources);

WITH non_price_sources AS (
  SELECT id
  FROM provider_price_source
  WHERE adapter_code = 'KIMI_OFFICIAL_PAGE'
    AND config->>'autoDiscovered' = 'true'
    AND endpoint ~ '^https://platform\.kimi\.com/docs/pricing/(chat|tools|limits|promotion)/?$'
)
UPDATE provider_price_sync_run r
SET status = 'CANCELLED',
    error_code = 'KIMI_NON_MODEL_PRICING_PAGE',
    error_message = '该页面不是模型 Token 定价证据，已停止同步',
    completed_at = coalesce(completed_at, now()),
    updated_at = now()
WHERE r.price_source_id IN (SELECT id FROM non_price_sources)
  AND r.status IN ('PENDING','RUNNING');

WITH non_price_sources AS (
  SELECT id
  FROM provider_price_source
  WHERE adapter_code = 'KIMI_OFFICIAL_PAGE'
    AND config->>'autoDiscovered' = 'true'
    AND endpoint ~ '^https://platform\.kimi\.com/docs/pricing/(chat|tools|limits|promotion)/?$'
)
UPDATE provider_price_diff d
SET status = 'REJECTED',
    decision_reason = 'V26：来源是 Kimi 辅助或促销页面，不作为模型 Token 定价证据',
    decided_at = now(),
    updated_at = now()
WHERE d.price_source_id IN (SELECT id FROM non_price_sources)
  AND d.status = 'PENDING';

WITH non_price_sources AS (
  SELECT id
  FROM provider_price_source
  WHERE adapter_code = 'KIMI_OFFICIAL_PAGE'
    AND config->>'autoDiscovered' = 'true'
    AND endpoint ~ '^https://platform\.kimi\.com/docs/pricing/(chat|tools|limits|promotion)/?$'
)
UPDATE alert_event a
SET status = 'RESOLVED',
    resolved_by = 'SYSTEM',
    resolved_at = coalesce(resolved_at, now()),
    detail = jsonb_set(
      coalesce(a.detail, '{}'::jsonb),
      '{resolution}',
      to_jsonb('该 URL 是 Kimi 辅助/促销页面，已从模型价格源发现范围中排除'::text),
      true
    ),
    updated_at = now()
WHERE a.alert_type = 'PRICE_SOURCE_SYNC_FAILED'
  AND a.resource_type = 'PRICE_SOURCE'
  AND a.resource_id IN (SELECT id FROM non_price_sources)
  AND a.status IN ('OPEN','ACKNOWLEDGED');
