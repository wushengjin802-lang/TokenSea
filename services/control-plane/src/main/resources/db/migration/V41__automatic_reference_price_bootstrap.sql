-- Automatic public reference price bootstrap and daily refresh.
-- Public reference prices are estimates only and never overwrite formal provider prices,
-- contract prices, billing reconciliation or immutable usage cost snapshots.

ALTER TABLE provider_price_source
  ADD COLUMN IF NOT EXISTS managed_by varchar(20) NOT NULL DEFAULT 'USER',
  ADD COLUMN IF NOT EXISTS source_purpose varchar(30) NOT NULL DEFAULT 'FORMAL_PRICE',
  ADD COLUMN IF NOT EXISTS publish_target varchar(40) NOT NULL DEFAULT 'PRICE_DIFF',
  ADD COLUMN IF NOT EXISTS bootstrap_version varchar(80),
  ADD COLUMN IF NOT EXISTS stale_after_hours integer NOT NULL DEFAULT 168,
  ADD COLUMN IF NOT EXISTS last_checked_at timestamptz,
  ADD COLUMN IF NOT EXISTS last_good_sync_at timestamptz;

ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_managed_by;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_managed_by
  CHECK(managed_by IN ('SYSTEM','USER'));
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_source_purpose;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_source_purpose
  CHECK(source_purpose IN ('REFERENCE','FORMAL_PRICE','BILLING'));
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_publish_target;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_publish_target
  CHECK(publish_target IN ('PUBLIC_REFERENCE_ONLY','PRICE_DIFF','BILLING_RECONCILIATION'));
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_stale_hours;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_stale_hours
  CHECK(stale_after_hours BETWEEN 1 AND 8760);

ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_adapter;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_adapter CHECK(
  adapter_code IN (
    'BUNDLED_REFERENCE','LITELLM_COST_MAP','MODELS_DEV',
    'AZURE_RETAIL_PRICES','AWS_PRICE_LIST_BULK','GOOGLE_CLOUD_CATALOG','GENERIC_DOCUMENT',
    'DEEPSEEK_OFFICIAL_PAGE','QWEN_OFFICIAL_PAGE','KIMI_OFFICIAL_PAGE',
    'XIAOMI_MIMO_OFFICIAL_PAGE','ZHIPU_OFFICIAL_PAGE',
    'OFFICIAL_JSON','OFFICIAL_CSV'
  )
);

ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_connector;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_connector CHECK(
  connector_code IN (
    'BUNDLED_REFERENCE','HTTP_DOCUMENT','AZURE_RETAIL_PRICES','AWS_PRICE_LIST_BULK',
    'GOOGLE_CLOUD_CATALOG','LITELLM_COST_MAP','MODELS_DEV'
  )
);

ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_connector_governance;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_connector_governance CHECK(
  (source_class='PUBLIC_REFERENCE'
    AND data_scope='REFERENCE_DATASET'
    AND trust_level='COMMUNITY_REFERENCE'
    AND publish_policy='MANUAL_ONLY'
    AND credential_purpose='NONE')
  OR
  (source_class='OFFICIAL'
    AND data_scope IN ('PUBLIC_CATALOG','ACCOUNT_PRICING','DOCUMENT')
    AND trust_level IN ('OFFICIAL_PUBLIC','OFFICIAL_ACCOUNT'))
);

INSERT INTO provider_price_source(
  id,name,source_class,adapter_code,provider_type,auth_mode,endpoint,official_hosts,
  region,default_currency,schedule_expression,auto_publish,max_auto_change_ratio,
  confirmation_runs,config,status,next_run_at,parser_version,fetch_mode,source_priority,
  price_nature,connector_code,data_scope,trust_level,publish_policy,schema_version,
  credential_purpose,mapping_profile,document_type,extraction_mode,minimum_confidence,
  require_manual_review,max_document_pages,max_document_bytes,llm_model,
  managed_by,source_purpose,publish_target,bootstrap_version,stale_after_hours,
  created_by,updated_by)
VALUES(
  'builtin_reference_price_bundle','TokenSea 内置参考价格快照','PUBLIC_REFERENCE',
  'BUNDLED_REFERENCE',NULL,'NONE','classpath:reference-prices/reference-price-bootstrap.json',
  '[]','global','USD','P3650D',true,0.3000,1,
  '{"referenceOnly":true,"offlineBootstrap":true}','ACTIVE',NULL,'1.0.0','STRUCTURED_HTTP',
  10,'ORIGINAL','BUNDLED_REFERENCE','REFERENCE_DATASET','COMMUNITY_REFERENCE',
  'MANUAL_ONLY','reference-price-bundle-v1','NONE','BUNDLE','JSON','DETERMINISTIC',
  1.00000,false,1,20000000,NULL,'SYSTEM','REFERENCE','PUBLIC_REFERENCE_ONLY',NULL,720,
  'SYSTEM','SYSTEM')
ON CONFLICT(id) DO UPDATE SET
  name=excluded.name,
  source_class=excluded.source_class,
  adapter_code=excluded.adapter_code,
  endpoint=excluded.endpoint,
  status='ACTIVE',
  next_run_at=NULL,
  auto_publish=true,
  managed_by='SYSTEM',
  source_purpose='REFERENCE',
  publish_target='PUBLIC_REFERENCE_ONLY',
  stale_after_hours=720,
  updated_by='SYSTEM',
  updated_at=now();

UPDATE provider_price_source
SET managed_by='SYSTEM',
    source_purpose='REFERENCE',
    publish_target='PUBLIC_REFERENCE_ONLY',
    status='ACTIVE',
    next_run_at=now(),
    schedule_expression='P1D',
    auto_publish=true,
    stale_after_hours=168,
    last_good_sync_at=coalesce(last_good_sync_at,last_success_at),
    last_error=NULL,
    updated_by='SYSTEM',
    updated_at=now()
WHERE id IN ('builtin_litellm_cost_map','builtin_models_dev');

ALTER TABLE public_model_price_reference
  ADD COLUMN IF NOT EXISTS bundle_version varchar(80),
  ADD COLUMN IF NOT EXISTS source_rank integer NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS is_current boolean NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS last_seen_at timestamptz,
  ADD COLUMN IF NOT EXISTS stale_at timestamptz,
  ADD COLUMN IF NOT EXISTS price_status varchar(20) NOT NULL DEFAULT 'CURRENT';

ALTER TABLE public_model_price_reference DROP CONSTRAINT IF EXISTS ck_public_reference_price_status;
ALTER TABLE public_model_price_reference ADD CONSTRAINT ck_public_reference_price_status
  CHECK(price_status IN ('CURRENT','STALE','MISSING','DISPUTED'));
CREATE INDEX IF NOT EXISTS idx_public_reference_current
  ON public_model_price_reference(is_current,price_status,provider_type,provider_model_name,source_rank DESC);
CREATE INDEX IF NOT EXISTS idx_public_reference_stale
  ON public_model_price_reference(stale_at) WHERE is_current=true;

UPDATE public_model_price_reference r
SET source_rank=coalesce(s.source_priority,0),
    last_seen_at=coalesce(r.last_seen_at,r.observed_at),
    stale_at=coalesce(r.stale_at,r.observed_at + make_interval(hours => coalesce(s.stale_after_hours,168))),
    price_status=case
      when coalesce(r.stale_at,r.observed_at + make_interval(hours => coalesce(s.stale_after_hours,168))) < now()
        then 'STALE'
      else 'CURRENT'
    end
FROM provider_price_source s
WHERE s.id=r.price_source_id;

CREATE OR REPLACE VIEW v_current_public_model_price_reference AS
WITH ranked AS (
  SELECT r.*,s.name AS source_name,s.adapter_code,s.managed_by,s.last_good_sync_at,
         row_number() OVER (
           PARTITION BY lower(r.provider_type),lower(r.provider_model_name),lower(r.region),
                        lower(r.request_mode),lower(r.service_tier),lower(r.context_tier)
           ORDER BY
             CASE WHEN r.price_status='CURRENT' AND (r.stale_at IS NULL OR r.stale_at>now()) THEN 0 ELSE 1 END,
             r.source_rank DESC,
             r.source_priority DESC,
             r.source_confidence DESC,
             r.observed_at DESC,
             r.id
         ) AS reference_rank
  FROM public_model_price_reference r
  JOIN provider_price_source s ON s.id=r.price_source_id
  WHERE r.is_current=true AND r.status='ACTIVE'
)
SELECT * FROM ranked WHERE reference_rank=1;
