-- Kimi 文档导航包含大量指向同一页面的 URL fragment。
-- V23 初版会把这些锚点误识别为独立价格子页面。本迁移只治理自动发现的派生数据：
-- 1. 禁用 fragment-only 子价格源并停止调度；
-- 2. 拒绝其尚未处理的价格差异；
-- 3. 删除仅由 fragment 证据产生的重复模型候选。
-- 同步任务、原始快照、审计记录和真实独立定价页面均保留。

WITH fragment_sources AS (
  SELECT id
  FROM provider_price_source
  WHERE adapter_code = 'KIMI_OFFICIAL_PAGE'
    AND config->>'autoDiscovered' = 'true'
    AND endpoint LIKE '%#%'
)
UPDATE provider_price_source s
SET status = 'DISABLED',
    next_run_at = NULL,
    last_error = 'V24 已禁用误识别的 Kimi 页面锚点价格源',
    config = jsonb_set(
      coalesce(s.config, '{}'::jsonb),
      '{disabledReason}',
      to_jsonb('KIMI_FRAGMENT_SOURCE_DEDUPLICATED'::text),
      true
    ),
    updated_by = 'SYSTEM',
    updated_at = now()
WHERE s.id IN (SELECT id FROM fragment_sources);

WITH fragment_sources AS (
  SELECT id
  FROM provider_price_source
  WHERE adapter_code = 'KIMI_OFFICIAL_PAGE'
    AND config->>'autoDiscovered' = 'true'
    AND endpoint LIKE '%#%'
)
UPDATE provider_price_diff d
SET status = 'REJECTED',
    decision_reason = 'V24：来源是同一 Kimi 定价页面的 URL fragment，不作为独立价格证据',
    decided_at = now(),
    updated_at = now()
WHERE d.status = 'PENDING'
  AND d.price_source_id IN (SELECT id FROM fragment_sources);

DELETE FROM model_discovery_candidate
WHERE lower(provider_type) = 'moonshot'
  AND source_type = 'OFFICIAL_PRICE'
  AND source_ref LIKE '%#%';
