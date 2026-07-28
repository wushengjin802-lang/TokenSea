-- Qwen/Kimi official pricing, model discovery lifecycle and production admission governance.
-- Forward-only migration. Historical prices, deployments, usage and cost snapshots are preserved.

-- 1. Price source fetch strategy and official-source governance.
ALTER TABLE provider_price_source
  ADD COLUMN IF NOT EXISTS fetch_mode varchar(30) NOT NULL DEFAULT 'AUTO',
  ADD COLUMN IF NOT EXISTS source_priority int NOT NULL DEFAULT 100,
  ADD COLUMN IF NOT EXISTS price_nature varchar(30) NOT NULL DEFAULT 'ORIGINAL',
  ADD COLUMN IF NOT EXISTS structure_fingerprint varchar(128),
  ADD COLUMN IF NOT EXISTS last_structure_fingerprint varchar(128),
  ADD COLUMN IF NOT EXISTS structure_changed_at timestamptz;

ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_adapter;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_adapter CHECK(
  adapter_code IN (
    'LITELLM_COST_MAP','MODELS_DEV','DEEPSEEK_OFFICIAL_PAGE','QWEN_OFFICIAL_PAGE',
    'KIMI_OFFICIAL_PAGE','OFFICIAL_JSON','OFFICIAL_CSV'
  )
);
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_fetch_mode
  CHECK(fetch_mode IN ('AUTO','STRUCTURED_HTTP','STATIC_HTML','HEADLESS'));
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_source_priority
  CHECK(source_priority BETWEEN 1 AND 10000);
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_nature
  CHECK(price_nature IN ('ORIGINAL','PROMOTIONAL','FREE_QUOTA'));

UPDATE provider_price_source
SET source_priority = CASE WHEN source_class='OFFICIAL' THEN 100 ELSE 1000 END,
    price_nature = CASE WHEN source_class='OFFICIAL' THEN 'ORIGINAL' ELSE price_nature END,
    updated_at = now()
WHERE source_priority = 100 OR source_priority IS NULL;

-- 2. Persist official-source semantics on catalog and immutable effective price versions.
ALTER TABLE provider_model_price_catalog
  ADD COLUMN IF NOT EXISTS price_nature varchar(30) NOT NULL DEFAULT 'ORIGINAL',
  ADD COLUMN IF NOT EXISTS pricing_conditions jsonb NOT NULL DEFAULT '{}',
  ADD COLUMN IF NOT EXISTS source_priority int NOT NULL DEFAULT 100,
  ADD COLUMN IF NOT EXISTS source_evidence_path varchar(1200),
  ADD COLUMN IF NOT EXISTS source_published_at timestamptz;
ALTER TABLE provider_model_price_catalog ADD CONSTRAINT ck_provider_catalog_price_nature
  CHECK(price_nature IN ('ORIGINAL','PROMOTIONAL','FREE_QUOTA'));
ALTER TABLE provider_model_price_catalog ADD CONSTRAINT ck_provider_catalog_pricing_conditions
  CHECK(jsonb_typeof(pricing_conditions)='object');
ALTER TABLE provider_model_price_catalog ADD CONSTRAINT ck_provider_catalog_source_priority
  CHECK(source_priority BETWEEN 1 AND 10000);

ALTER TABLE public_model_price_reference
  ADD COLUMN IF NOT EXISTS price_nature varchar(30) NOT NULL DEFAULT 'ORIGINAL',
  ADD COLUMN IF NOT EXISTS pricing_conditions jsonb NOT NULL DEFAULT '{}',
  ADD COLUMN IF NOT EXISTS source_priority int NOT NULL DEFAULT 1000,
  ADD COLUMN IF NOT EXISTS source_evidence_path varchar(1200),
  ADD COLUMN IF NOT EXISTS source_published_at timestamptz;
ALTER TABLE public_model_price_reference ADD CONSTRAINT ck_public_reference_price_nature
  CHECK(price_nature IN ('ORIGINAL','PROMOTIONAL','FREE_QUOTA'));
ALTER TABLE public_model_price_reference ADD CONSTRAINT ck_public_reference_pricing_conditions
  CHECK(jsonb_typeof(pricing_conditions)='object');
ALTER TABLE public_model_price_reference ADD CONSTRAINT ck_public_reference_source_priority
  CHECK(source_priority BETWEEN 1 AND 10000);

ALTER TABLE price_version
  ADD COLUMN IF NOT EXISTS price_nature varchar(30) NOT NULL DEFAULT 'ORIGINAL',
  ADD COLUMN IF NOT EXISTS pricing_conditions jsonb NOT NULL DEFAULT '{}',
  ADD COLUMN IF NOT EXISTS source_priority int NOT NULL DEFAULT 100,
  ADD COLUMN IF NOT EXISTS source_evidence_path varchar(1200),
  ADD COLUMN IF NOT EXISTS source_published_at timestamptz,
  ADD COLUMN IF NOT EXISTS contract_id varchar(64),
  ADD COLUMN IF NOT EXISTS contract_name varchar(240),
  ADD COLUMN IF NOT EXISTS provider_instance_id varchar(64),
  ADD COLUMN IF NOT EXISTS contract_reference varchar(1000);
ALTER TABLE price_version ADD CONSTRAINT ck_price_version_price_nature
  CHECK(price_nature IN ('ORIGINAL','PROMOTIONAL','FREE_QUOTA'));
ALTER TABLE price_version ADD CONSTRAINT ck_price_version_pricing_conditions
  CHECK(jsonb_typeof(pricing_conditions)='object');
ALTER TABLE price_version ADD CONSTRAINT ck_price_version_source_priority
  CHECK(source_priority BETWEEN 1 AND 10000);
ALTER TABLE price_version DROP CONSTRAINT IF EXISTS fk_price_version_provider_instance;
ALTER TABLE price_version ADD CONSTRAINT fk_price_version_provider_instance
  FOREIGN KEY(provider_instance_id) REFERENCES provider_instance(id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE price_version VALIDATE CONSTRAINT fk_price_version_provider_instance;

ALTER TABLE price_version DROP CONSTRAINT IF EXISTS ck_price_layer;
ALTER TABLE price_version ADD CONSTRAINT ck_price_layer CHECK(
  price_layer IN ('PUBLIC_REFERENCE','PROVIDER_OFFICIAL','CHANNEL_ACTUAL','CONTRACT_PRICE','INTERNAL_ACCOUNTING')
);
ALTER TABLE price_version DROP CONSTRAINT IF EXISTS ck_price_layer_owner;
ALTER TABLE price_version ADD CONSTRAINT ck_price_layer_owner CHECK (
  (price_layer='PUBLIC_REFERENCE' AND public_model_reference_id IS NOT NULL AND deployment_id IS NULL AND platform_model_id IS NULL) OR
  (price_layer IN ('PROVIDER_OFFICIAL','CHANNEL_ACTUAL','CONTRACT_PRICE') AND public_model_reference_id IS NULL AND deployment_id IS NOT NULL AND platform_model_id IS NULL) OR
  (price_layer='INTERNAL_ACCOUNTING' AND public_model_reference_id IS NULL AND deployment_id IS NULL AND platform_model_id IS NOT NULL)
) NOT VALID;
ALTER TABLE price_version VALIDATE CONSTRAINT ck_price_layer_owner;
CREATE INDEX IF NOT EXISTS idx_price_version_contract
  ON price_version(deployment_id,status,effective_from DESC) WHERE price_layer='CONTRACT_PRICE';

-- 3. Expand reviewable price differences for source conflicts and tier/alias changes.
ALTER TABLE provider_price_diff
  ADD COLUMN IF NOT EXISTS source_priority int NOT NULL DEFAULT 100,
  ADD COLUMN IF NOT EXISTS source_conflict_group varchar(128),
  ADD COLUMN IF NOT EXISTS structure_fingerprint varchar(128);
ALTER TABLE provider_price_diff DROP CONSTRAINT IF EXISTS ck_provider_price_diff_type;
ALTER TABLE provider_price_diff ADD CONSTRAINT ck_provider_price_diff_type CHECK(diff_type IN (
  'MODEL_ADDED','MODEL_REMOVED','PRICE_CHANGED','CURRENCY_CHANGED','UNIT_CHANGED',
  'BILLING_DIMENSION_CHANGED','REGION_CHANGED','MODEL_MAPPING_CHANGED','SOURCE_STRUCTURE_CHANGED',
  'SOURCE_CONFLICT','PRICE_NATURE_CHANGED','CONTEXT_TIER_CHANGED','ALIAS_TARGET_CHANGED'
));
ALTER TABLE provider_price_diff ADD CONSTRAINT ck_provider_price_diff_source_priority
  CHECK(source_priority BETWEEN 1 AND 10000);
CREATE INDEX IF NOT EXISTS idx_provider_price_diff_conflict
  ON provider_price_diff(source_conflict_group,status,created_at DESC)
  WHERE source_conflict_group IS NOT NULL;

-- 4. Govern provider aliases and version relationships independently from price rows.
CREATE TABLE provider_model_alias (
  id varchar(64) PRIMARY KEY,
  provider_type varchar(80) NOT NULL,
  canonical_model_id varchar(64) REFERENCES public_model_reference(id) ON DELETE SET NULL,
  provider_model_name varchar(240) NOT NULL,
  target_provider_model_name varchar(240) NOT NULL,
  relation_type varchar(30) NOT NULL,
  region varchar(80) NOT NULL DEFAULT 'global',
  source_type varchar(50) NOT NULL,
  source_ref varchar(1200) NOT NULL,
  raw_snapshot_id varchar(64),
  evidence_hash varchar(64) NOT NULL,
  review_status varchar(30) NOT NULL DEFAULT 'PENDING_REVIEW',
  review_reason varchar(1000),
  reviewed_by varchar(64),
  reviewed_at timestamptz,
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_provider_alias_relation CHECK(relation_type IN ('EXACT_ALIAS','STABLE_ALIAS','VERSION_OF','VARIANT_OF')),
  CONSTRAINT ck_provider_alias_review CHECK(review_status IN ('PENDING_REVIEW','APPROVED','REJECTED','MIGRATED_APPROVED','EXPIRED')),
  CONSTRAINT ck_provider_alias_period CHECK(effective_to IS NULL OR effective_to>effective_from),
  CONSTRAINT uq_provider_alias_version UNIQUE(provider_type,provider_model_name,region,relation_type,effective_from)
);
CREATE INDEX idx_provider_alias_lookup
  ON provider_model_alias(lower(provider_type),lower(provider_model_name),lower(region),review_status,effective_from DESC);
CREATE INDEX idx_provider_alias_target
  ON provider_model_alias(lower(provider_type),lower(target_provider_model_name),lower(region),review_status);

-- Preserve existing catalog aliases as explicitly migrated/approved relationships.
INSERT INTO provider_model_alias(
  id,provider_type,provider_model_name,target_provider_model_name,relation_type,region,
  source_type,source_ref,evidence_hash,review_status,effective_from,created_at,updated_at)
SELECT
  md5(c.id||':'||a.alias),c.provider_type,a.alias,c.provider_model_name,'EXACT_ALIAS',c.region,
  'CATALOG_MIGRATION',c.source_ref,md5(c.id||':'||a.alias||':'||c.provider_model_name),
  'MIGRATED_APPROVED',c.effective_from,now(),now()
FROM provider_model_price_catalog c
CROSS JOIN LATERAL jsonb_array_elements_text(c.aliases) a(alias)
WHERE btrim(a.alias)<>''
ON CONFLICT DO NOTHING;

-- 5. Official-document candidates are not routable deployments until verified by a channel or live probe.
CREATE TABLE model_discovery_candidate (
  id varchar(64) PRIMARY KEY,
  provider_type varchar(80) NOT NULL,
  candidate_model_name varchar(240) NOT NULL,
  display_name varchar(320),
  source_type varchar(50) NOT NULL,
  source_ref varchar(1200) NOT NULL,
  raw_snapshot_id varchar(64),
  evidence_hash varchar(64) NOT NULL,
  region varchar(80) NOT NULL DEFAULT 'global',
  raw_attributes jsonb NOT NULL DEFAULT '{}',
  channel_verified_count int NOT NULL DEFAULT 0,
  status varchar(30) NOT NULL DEFAULT 'CANDIDATE',
  first_seen_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  verified_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_model_candidate_status CHECK(status IN ('CANDIDATE','CHANNEL_VERIFIED','PRICE_ONLY','REJECTED','EXPIRED')),
  CONSTRAINT ck_model_candidate_verified_count CHECK(channel_verified_count>=0),
  CONSTRAINT ck_model_candidate_attributes CHECK(jsonb_typeof(raw_attributes)='object'),
  UNIQUE(provider_type,candidate_model_name,region,source_ref)
);
CREATE INDEX idx_model_candidate_review
  ON model_discovery_candidate(provider_type,status,last_seen_at DESC);

-- 6. Split technical discovery/health from business production approval.
ALTER TABLE channel_model_deployment
  ADD COLUMN IF NOT EXISTS discovery_status varchar(30) NOT NULL DEFAULT 'DISCOVERED',
  ADD COLUMN IF NOT EXISTS health_status varchar(30) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN IF NOT EXISTS price_status varchar(30) NOT NULL DEFAULT 'MISSING',
  ADD COLUMN IF NOT EXISTS production_status varchar(30) NOT NULL DEFAULT 'CANDIDATE',
  ADD COLUMN IF NOT EXISTS missing_streak int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS last_missing_at timestamptz,
  ADD COLUMN IF NOT EXISTS last_probe_at timestamptz,
  ADD COLUMN IF NOT EXISTS last_probe_status varchar(30),
  ADD COLUMN IF NOT EXISTS production_approved_by varchar(64),
  ADD COLUMN IF NOT EXISTS production_approved_at timestamptz,
  ADD COLUMN IF NOT EXISTS production_decision_reason varchar(1000),
  ADD COLUMN IF NOT EXISTS recovery_requires_review boolean NOT NULL DEFAULT false;

ALTER TABLE channel_model_deployment ADD CONSTRAINT ck_deployment_discovery_status
  CHECK(discovery_status IN ('DISCOVERED','SUSPECTED_MISSING','MISSING_CONFIRMED','RECOVERED'));
ALTER TABLE channel_model_deployment ADD CONSTRAINT ck_deployment_health_status
  CHECK(health_status IN ('UNKNOWN','PROBE_PENDING','HEALTHY','DEGRADED','UNAVAILABLE'));
ALTER TABLE channel_model_deployment ADD CONSTRAINT ck_deployment_price_status
  CHECK(price_status IN ('MISSING','MATCHED_OFFICIAL','MATCHED_CHANNEL','MATCHED_CONTRACT','CONFLICT'));
ALTER TABLE channel_model_deployment ADD CONSTRAINT ck_deployment_production_status
  CHECK(production_status IN ('CANDIDATE','READY_FOR_REVIEW','APPROVED','REJECTED','SUSPENDED'));
ALTER TABLE channel_model_deployment ADD CONSTRAINT ck_deployment_missing_streak CHECK(missing_streak>=0);
ALTER TABLE channel_model_deployment ADD CONSTRAINT ck_deployment_probe_status
  CHECK(last_probe_status IS NULL OR last_probe_status IN ('PASSED','FAILED','INCONCLUSIVE'));

UPDATE channel_model_deployment d
SET discovery_status = CASE WHEN d.review_status='MISSING' THEN 'MISSING_CONFIRMED' ELSE 'DISCOVERED' END,
    missing_streak = CASE WHEN d.review_status='MISSING' THEN 4 ELSE 0 END,
    health_status = CASE
      WHEN EXISTS(select 1 from capability_validation v where v.deployment_id=d.id and v.test_type='LIVE_PROBE' and v.status='PASSED') THEN 'HEALTHY'
      WHEN EXISTS(select 1 from capability_validation v where v.deployment_id=d.id and v.test_type='LIVE_PROBE' and v.status='FAILED') THEN 'UNAVAILABLE'
      ELSE 'UNKNOWN'
    END,
    last_probe_at = (select max(v.validated_at) from capability_validation v where v.deployment_id=d.id and v.test_type='LIVE_PROBE'),
    last_probe_status = (select v.status from capability_validation v where v.deployment_id=d.id and v.test_type='LIVE_PROBE' order by v.validated_at desc limit 1),
    price_status = CASE
      WHEN EXISTS(select 1 from price_version p where p.deployment_id=d.id and p.price_layer='CONTRACT_PRICE' and p.status='ACTIVE' and p.effective_from<=now() and (p.effective_to is null or p.effective_to>now())) THEN 'MATCHED_CONTRACT'
      WHEN EXISTS(select 1 from price_version p where p.deployment_id=d.id and p.price_layer='CHANNEL_ACTUAL' and p.status='ACTIVE' and p.effective_from<=now() and (p.effective_to is null or p.effective_to>now())) THEN 'MATCHED_CHANNEL'
      WHEN EXISTS(select 1 from price_version p where p.deployment_id=d.id and p.price_layer='PROVIDER_OFFICIAL' and p.status='ACTIVE' and p.effective_from<=now() and (p.effective_to is null or p.effective_to>now())) THEN 'MATCHED_OFFICIAL'
      ELSE 'MISSING'
    END,
    production_status = CASE
      WHEN d.review_status='MISSING' OR d.routing_status='SUSPENDED' THEN 'SUSPENDED'
      WHEN d.review_status='APPROVED' AND d.routing_status='ELIGIBLE'
        AND EXISTS(select 1 from capability_validation v where v.deployment_id=d.id and v.test_type='LIVE_PROBE' and v.status='PASSED') THEN 'APPROVED'
      ELSE 'CANDIDATE'
    END,
    production_approved_by = CASE
      WHEN d.review_status='APPROVED' AND d.routing_status='ELIGIBLE'
        AND EXISTS(select 1 from capability_validation v where v.deployment_id=d.id and v.test_type='LIVE_PROBE' and v.status='PASSED') THEN 'MIGRATION_V23'
      ELSE NULL
    END,
    production_approved_at = CASE
      WHEN d.review_status='APPROVED' AND d.routing_status='ELIGIBLE'
        AND EXISTS(select 1 from capability_validation v where v.deployment_id=d.id and v.test_type='LIVE_PROBE' and v.status='PASSED') THEN now()
      ELSE NULL
    END,
    production_decision_reason = CASE
      WHEN d.review_status='APPROVED' AND d.routing_status='ELIGIBLE'
        AND EXISTS(select 1 from capability_validation v where v.deployment_id=d.id and v.test_type='LIVE_PROBE' and v.status='PASSED') THEN 'V23兼容迁移：保留升级前已验证且可路由的部署'
      ELSE NULL
    END,
    updated_at = now();

CREATE INDEX idx_channel_deployment_lifecycle
  ON channel_model_deployment(provider_instance_id,production_status,health_status,discovery_status,price_status);
CREATE INDEX idx_channel_deployment_missing
  ON channel_model_deployment(discovery_status,missing_streak,last_missing_at DESC);

-- 7. Reliable post-commit orchestration for price/model rematching.
CREATE TABLE governance_event_outbox (
  id varchar(64) PRIMARY KEY,
  event_type varchar(80) NOT NULL,
  aggregate_type varchar(80) NOT NULL,
  aggregate_id varchar(100) NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}',
  status varchar(30) NOT NULL DEFAULT 'PENDING',
  retry_count int NOT NULL DEFAULT 0,
  next_retry_at timestamptz NOT NULL DEFAULT now(),
  last_error varchar(1000),
  created_at timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_governance_outbox_status CHECK(status IN ('PENDING','PROCESSING','PROCESSED','FAILED','CANCELLED')),
  CONSTRAINT ck_governance_outbox_retry CHECK(retry_count>=0),
  CONSTRAINT ck_governance_outbox_payload CHECK(jsonb_typeof(payload)='object')
);
CREATE INDEX idx_governance_outbox_pending
  ON governance_event_outbox(status,next_retry_at,created_at);

-- 8. Built-in official sources start paused and require test-fetch/test-parse before activation.
INSERT INTO provider_price_source(
  id,name,source_class,adapter_code,provider_type,auth_mode,endpoint,official_hosts,region,
  default_currency,schedule_expression,auto_publish,max_auto_change_ratio,confirmation_runs,
  config,status,next_run_at,parser_version,fetch_mode,source_priority,price_nature)
VALUES
  ('builtin_qwen_cn_official_price','千问中国内地官方价格','OFFICIAL','QWEN_OFFICIAL_PAGE','qwen','NONE',
   'https://help.aliyun.com/zh/model-studio/model-pricing','["help.aliyun.com"]','cn','CNY','P1D',true,0.1000,2,
   '{"official":true,"scope":"TEXT_REALTIME_STANDARD","publishOriginalOnly":true,"discoverAliases":true}',
   'PAUSED',null,'1.0.0','AUTO',100,'ORIGINAL'),
  ('builtin_kimi_cn_official_price','Kimi 中国站官方价格','OFFICIAL','KIMI_OFFICIAL_PAGE','moonshot','NONE',
   'https://platform.kimi.com/docs/pricing/chat-k26','["platform.kimi.com"]','cn','CNY','P1D',true,0.1000,2,
   '{"official":true,"scope":"TEXT_REALTIME_STANDARD","publishOriginalOnly":true,"discoverPricingPages":true,"seedPricingPages":["https://platform.kimi.com/docs/pricing/chat-k26","https://platform.kimi.com/docs/pricing/chat-k3","https://platform.kimi.com/docs/pricing/chat-k27-code"]}',
   'PAUSED',null,'1.0.0','AUTO',100,'ORIGINAL')
ON CONFLICT(id) DO NOTHING;
