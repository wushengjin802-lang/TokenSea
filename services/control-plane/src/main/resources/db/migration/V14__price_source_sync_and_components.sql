-- Price-source synchronization, immutable evidence snapshots, reviewable diffs and extensible price components.
-- Public reference sources (LiteLLM/models.dev) never directly overwrite production provider prices.

CREATE TABLE provider_price_source (
  id varchar(64) PRIMARY KEY,
  name varchar(160) NOT NULL,
  source_class varchar(30) NOT NULL,
  adapter_code varchar(60) NOT NULL,
  provider_type varchar(80),
  provider_instance_id varchar(64) REFERENCES provider_instance(id) ON DELETE RESTRICT,
  auth_mode varchar(30) NOT NULL DEFAULT 'NONE',
  endpoint varchar(1200) NOT NULL,
  official_hosts jsonb NOT NULL DEFAULT '[]',
  region varchar(80) NOT NULL DEFAULT 'global',
  default_currency varchar(3) NOT NULL DEFAULT 'USD',
  schedule_expression varchar(120) NOT NULL DEFAULT 'P1D',
  auto_publish boolean NOT NULL DEFAULT false,
  max_auto_change_ratio numeric(8,4) NOT NULL DEFAULT 0.3000,
  confirmation_runs int NOT NULL DEFAULT 1,
  config jsonb NOT NULL DEFAULT '{}',
  status varchar(30) NOT NULL DEFAULT 'PAUSED',
  next_run_at timestamptz,
  last_success_at timestamptz,
  last_failure_at timestamptz,
  last_error varchar(1000),
  etag varchar(500),
  last_modified varchar(500),
  last_content_hash varchar(64),
  parser_version varchar(40) NOT NULL DEFAULT '1.0.0',
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_provider_price_source_class CHECK(source_class IN ('PUBLIC_REFERENCE','OFFICIAL')),
  CONSTRAINT ck_provider_price_source_auth CHECK(auth_mode IN ('NONE','PROVIDER_INSTANCE')),
  CONSTRAINT ck_provider_price_source_auth_owner CHECK(
    (auth_mode='NONE' AND provider_instance_id IS NULL) OR
    (auth_mode='PROVIDER_INSTANCE' AND provider_instance_id IS NOT NULL)),
  CONSTRAINT ck_provider_price_adapter CHECK(adapter_code IN ('LITELLM_COST_MAP','MODELS_DEV','DEEPSEEK_OFFICIAL_PAGE','OFFICIAL_JSON','OFFICIAL_CSV')),
  CONSTRAINT ck_provider_price_source_status CHECK(status IN ('DRAFT','ACTIVE','PAUSED','DEGRADED','DISABLED')),
  CONSTRAINT ck_provider_price_source_currency CHECK(default_currency ~ '^[A-Z]{3}$'),
  CONSTRAINT ck_provider_price_source_ratio CHECK(max_auto_change_ratio>=0 AND max_auto_change_ratio<=10),
  CONSTRAINT ck_provider_price_confirmation CHECK(confirmation_runs BETWEEN 1 AND 10)
);
CREATE INDEX idx_provider_price_source_due ON provider_price_source(status,next_run_at);

CREATE TABLE provider_price_sync_run (
  id varchar(64) PRIMARY KEY,
  price_source_id varchar(64) NOT NULL REFERENCES provider_price_source(id) ON DELETE CASCADE,
  trigger_type varchar(30) NOT NULL DEFAULT 'MANUAL',
  status varchar(30) NOT NULL DEFAULT 'PENDING',
  scheduled_for timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  completed_at timestamptz,
  http_status int,
  records_fetched int NOT NULL DEFAULT 0,
  records_normalized int NOT NULL DEFAULT 0,
  records_changed int NOT NULL DEFAULT 0,
  records_auto_published int NOT NULL DEFAULT 0,
  records_review_required int NOT NULL DEFAULT 0,
  error_code varchar(120),
  error_message varchar(1000),
  execution_log jsonb NOT NULL DEFAULT '[]',
  lock_owner varchar(100),
  heartbeat_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_provider_price_run_trigger CHECK(trigger_type IN ('MANUAL','SCHEDULED','MODEL_DISCOVERY')),
  CONSTRAINT ck_provider_price_run_status CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','NO_CHANGE','REVIEW_REQUIRED','FAILED','CANCELLED'))
);
CREATE INDEX idx_provider_price_run_source ON provider_price_sync_run(price_source_id,created_at DESC);
CREATE INDEX idx_provider_price_run_pending ON provider_price_sync_run(status,scheduled_for);
CREATE UNIQUE INDEX uq_provider_price_run_active_source
  ON provider_price_sync_run(price_source_id) WHERE status IN ('PENDING','RUNNING');

CREATE TABLE provider_price_raw_snapshot (
  id varchar(64) PRIMARY KEY,
  price_source_id varchar(64) NOT NULL REFERENCES provider_price_source(id) ON DELETE RESTRICT,
  sync_run_id varchar(64) NOT NULL REFERENCES provider_price_sync_run(id) ON DELETE CASCADE,
  source_endpoint varchar(1200) NOT NULL,
  final_endpoint varchar(1200) NOT NULL,
  http_status int NOT NULL,
  content_type varchar(200),
  etag varchar(500),
  last_modified varchar(500),
  checksum varchar(64) NOT NULL,
  response_bytes int NOT NULL,
  raw_content text NOT NULL,
  parser_version varchar(40) NOT NULL,
  fetched_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(price_source_id,checksum)
);
CREATE INDEX idx_provider_price_snapshot_source ON provider_price_raw_snapshot(price_source_id,fetched_at DESC);

CREATE TABLE provider_price_diff (
  id varchar(64) PRIMARY KEY,
  price_source_id varchar(64) NOT NULL REFERENCES provider_price_source(id) ON DELETE RESTRICT,
  sync_run_id varchar(64) NOT NULL REFERENCES provider_price_sync_run(id) ON DELETE CASCADE,
  raw_snapshot_id varchar(64) NOT NULL REFERENCES provider_price_raw_snapshot(id) ON DELETE RESTRICT,
  provider_type varchar(80) NOT NULL,
  provider_model_name varchar(240) NOT NULL,
  region varchar(80) NOT NULL DEFAULT 'global',
  request_mode varchar(40) NOT NULL DEFAULT 'STANDARD',
  service_tier varchar(80) NOT NULL DEFAULT 'DEFAULT',
  context_tier varchar(80) NOT NULL DEFAULT 'DEFAULT',
  diff_type varchar(60) NOT NULL,
  old_value jsonb,
  new_value jsonb,
  change_ratio numeric(12,6),
  risk_level varchar(20) NOT NULL,
  status varchar(30) NOT NULL DEFAULT 'PENDING',
  decision_reason varchar(1000),
  decided_by varchar(64),
  decided_at timestamptz,
  published_catalog_id varchar(64),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_provider_price_diff_type CHECK(diff_type IN ('MODEL_ADDED','MODEL_REMOVED','PRICE_CHANGED','CURRENCY_CHANGED','UNIT_CHANGED','BILLING_DIMENSION_CHANGED','REGION_CHANGED','MODEL_MAPPING_CHANGED','SOURCE_STRUCTURE_CHANGED')),
  CONSTRAINT ck_provider_price_diff_risk CHECK(risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  CONSTRAINT ck_provider_price_diff_status CHECK(status IN ('PENDING','AUTO_PUBLISHED','APPROVED','REJECTED','IGNORED'))
);
CREATE INDEX idx_provider_price_diff_pending ON provider_price_diff(status,risk_level,created_at DESC);
CREATE INDEX idx_provider_price_diff_model ON provider_price_diff(provider_type,provider_model_name,created_at DESC);

CREATE TABLE public_model_price_reference (
  id varchar(64) PRIMARY KEY,
  price_source_id varchar(64) NOT NULL REFERENCES provider_price_source(id) ON DELETE CASCADE,
  raw_snapshot_id varchar(64) NOT NULL REFERENCES provider_price_raw_snapshot(id) ON DELETE RESTRICT,
  sync_run_id varchar(64) NOT NULL REFERENCES provider_price_sync_run(id) ON DELETE RESTRICT,
  provider_type varchar(80) NOT NULL,
  provider_model_name varchar(240) NOT NULL,
  canonical_name varchar(320) NOT NULL,
  display_name varchar(320),
  currency varchar(3) NOT NULL,
  region varchar(80) NOT NULL DEFAULT 'global',
  request_mode varchar(40) NOT NULL DEFAULT 'STANDARD',
  service_tier varchar(80) NOT NULL DEFAULT 'DEFAULT',
  context_tier varchar(80) NOT NULL DEFAULT 'DEFAULT',
  input_amount_per_1k numeric(30,12) NOT NULL,
  output_amount_per_1k numeric(30,12) NOT NULL,
  price_components jsonb NOT NULL DEFAULT '{}',
  source_ref varchar(1200) NOT NULL,
  evidence_hash varchar(64) NOT NULL,
  source_confidence numeric(5,4) NOT NULL DEFAULT 0.7000,
  status varchar(30) NOT NULL DEFAULT 'ACTIVE',
  observed_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_public_price_reference_status CHECK(status IN ('ACTIVE','MISSING','DISPUTED')),
  CONSTRAINT ck_public_price_reference_amount CHECK(input_amount_per_1k>=0 AND output_amount_per_1k>=0),
  CONSTRAINT ck_public_price_reference_confidence CHECK(source_confidence BETWEEN 0 AND 1),
  UNIQUE(price_source_id,provider_type,provider_model_name,region,request_mode,service_tier,context_tier)
);
CREATE INDEX idx_public_price_reference_model
  ON public_model_price_reference(lower(provider_type),lower(provider_model_name),status);

ALTER TABLE public_model_reference
  ADD COLUMN IF NOT EXISTS reference_prices jsonb NOT NULL DEFAULT '{}',
  ADD COLUMN IF NOT EXISTS reference_source_hash varchar(64),
  ADD COLUMN IF NOT EXISTS reference_updated_at timestamptz;

ALTER TABLE provider_model_price_catalog
  ADD COLUMN IF NOT EXISTS price_source_id varchar(64),
  ADD COLUMN IF NOT EXISTS raw_snapshot_id varchar(64),
  ADD COLUMN IF NOT EXISTS sync_run_id varchar(64),
  ADD COLUMN IF NOT EXISTS parser_version varchar(40),
  ADD COLUMN IF NOT EXISTS publish_mode varchar(20) NOT NULL DEFAULT 'MANUAL',
  ADD COLUMN IF NOT EXISTS evidence_hash varchar(64),
  ADD COLUMN IF NOT EXISTS region varchar(80) NOT NULL DEFAULT 'global',
  ADD COLUMN IF NOT EXISTS request_mode varchar(40) NOT NULL DEFAULT 'STANDARD',
  ADD COLUMN IF NOT EXISTS service_tier varchar(80) NOT NULL DEFAULT 'DEFAULT',
  ADD COLUMN IF NOT EXISTS context_tier varchar(80) NOT NULL DEFAULT 'DEFAULT',
  ADD COLUMN IF NOT EXISTS normalized_price jsonb NOT NULL DEFAULT '{}';

ALTER TABLE provider_model_price_catalog DROP CONSTRAINT IF EXISTS fk_provider_price_source;
ALTER TABLE provider_model_price_catalog ADD CONSTRAINT fk_provider_price_source
  FOREIGN KEY(price_source_id) REFERENCES provider_price_source(id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE provider_model_price_catalog VALIDATE CONSTRAINT fk_provider_price_source;
ALTER TABLE provider_model_price_catalog DROP CONSTRAINT IF EXISTS fk_provider_price_snapshot;
ALTER TABLE provider_model_price_catalog ADD CONSTRAINT fk_provider_price_snapshot
  FOREIGN KEY(raw_snapshot_id) REFERENCES provider_price_raw_snapshot(id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE provider_model_price_catalog VALIDATE CONSTRAINT fk_provider_price_snapshot;
ALTER TABLE provider_model_price_catalog DROP CONSTRAINT IF EXISTS fk_provider_price_sync_run;
ALTER TABLE provider_model_price_catalog ADD CONSTRAINT fk_provider_price_sync_run
  FOREIGN KEY(sync_run_id) REFERENCES provider_price_sync_run(id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE provider_model_price_catalog VALIDATE CONSTRAINT fk_provider_price_sync_run;
ALTER TABLE provider_model_price_catalog DROP CONSTRAINT IF EXISTS ck_provider_price_publish_mode;
ALTER TABLE provider_model_price_catalog ADD CONSTRAINT ck_provider_price_publish_mode
  CHECK(publish_mode IN ('AUTO','MANUAL')) NOT VALID;
ALTER TABLE provider_model_price_catalog VALIDATE CONSTRAINT ck_provider_price_publish_mode;

DROP INDEX IF EXISTS uq_provider_price_catalog_active;
CREATE UNIQUE INDEX uq_provider_price_catalog_active_scope
  ON provider_model_price_catalog(
    lower(provider_type),lower(provider_model_name),lower(region),lower(request_mode),lower(service_tier),lower(context_tier)
  ) WHERE status='ACTIVE';

CREATE TABLE provider_price_component (
  id varchar(64) PRIMARY KEY,
  catalog_price_id varchar(64) NOT NULL REFERENCES provider_model_price_catalog(id) ON DELETE CASCADE,
  component_type varchar(60) NOT NULL,
  unit_price numeric(30,12) NOT NULL,
  unit_basis varchar(60) NOT NULL,
  scope jsonb NOT NULL DEFAULT '{}',
  scope_hash varchar(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_provider_price_component_amount CHECK(unit_price>=0),
  UNIQUE(catalog_price_id,component_type,scope_hash)
);
CREATE INDEX idx_provider_price_component_catalog ON provider_price_component(catalog_price_id);

ALTER TABLE price_version
  ADD COLUMN IF NOT EXISTS price_components jsonb NOT NULL DEFAULT '{}',
  ADD COLUMN IF NOT EXISTS evidence_hash varchar(64),
  ADD COLUMN IF NOT EXISTS region varchar(80) NOT NULL DEFAULT 'global',
  ADD COLUMN IF NOT EXISTS request_mode varchar(40) NOT NULL DEFAULT 'STANDARD',
  ADD COLUMN IF NOT EXISTS service_tier varchar(80) NOT NULL DEFAULT 'DEFAULT',
  ADD COLUMN IF NOT EXISTS context_tier varchar(80) NOT NULL DEFAULT 'DEFAULT';

ALTER TABLE usage_cost_snapshot
  ADD COLUMN IF NOT EXISTS cache_read_tokens int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS cache_write_tokens int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS reasoning_tokens int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS price_components jsonb NOT NULL DEFAULT '{}',
  ADD COLUMN IF NOT EXISTS cost_components jsonb NOT NULL DEFAULT '{}',
  ADD COLUMN IF NOT EXISTS pricing_model varchar(240),
  ADD COLUMN IF NOT EXISTS response_model varchar(240),
  ADD COLUMN IF NOT EXISTS provider_instance_id varchar(64),
  ADD COLUMN IF NOT EXISTS model_deployment_id varchar(64),
  ADD COLUMN IF NOT EXISTS calculator_version varchar(40) NOT NULL DEFAULT '1.0.0',
  ADD COLUMN IF NOT EXISTS evidence_hash varchar(64);

INSERT INTO provider_price_source(
  id,name,source_class,adapter_code,endpoint,official_hosts,default_currency,
  schedule_expression,auto_publish,max_auto_change_ratio,confirmation_runs,status,config)
VALUES
  ('builtin_litellm_cost_map','LiteLLM 公共成本参考','PUBLIC_REFERENCE','LITELLM_COST_MAP',
   'https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json',
   '["raw.githubusercontent.com"]','USD','P1D',false,0.3000,1,'PAUSED','{"referenceOnly":true}'),
  ('builtin_models_dev','models.dev 公共模型参考','PUBLIC_REFERENCE','MODELS_DEV',
   'https://models.dev/api.json','["models.dev"]','USD','P1D',false,0.3000,1,'PAUSED','{"referenceOnly":true}')
ON CONFLICT(id) DO NOTHING;

INSERT INTO provider_price_source(
  id,name,source_class,adapter_code,provider_type,endpoint,official_hosts,region,default_currency,
  schedule_expression,auto_publish,max_auto_change_ratio,confirmation_runs,status,config)
VALUES(
  'builtin_deepseek_official_price','DeepSeek 官方价格页','OFFICIAL','DEEPSEEK_OFFICIAL_PAGE','deepseek',
  'https://api-docs.deepseek.com/quick_start/pricing/','["api-docs.deepseek.com"]','global','USD',
  'P1D',true,0.3000,2,'PAUSED','{"official":true}')
ON CONFLICT(id) DO NOTHING;
