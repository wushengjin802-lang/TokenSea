-- Zhipu pricing page loads its JavaScript bundles from static.bigmodel.cn.
-- The Egress Proxy uses exact host allowlisting, so the render asset host must be explicit.

UPDATE provider_price_source
SET official_hosts = '["bigmodel.cn", "static.bigmodel.cn"]'::jsonb,
    config = coalesce(config, '{}'::jsonb)
      || '{"renderAssetHosts":["static.bigmodel.cn"]}'::jsonb,
    updated_at = now()
WHERE id = 'builtin_zhipu_cn_official_price'
  AND adapter_code = 'ZHIPU_OFFICIAL_PAGE';
