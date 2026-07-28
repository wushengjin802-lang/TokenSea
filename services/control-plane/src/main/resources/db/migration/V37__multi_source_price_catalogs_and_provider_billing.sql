-- Multi-source machine-readable price catalogs, generic document extraction and provider billing synchronization.
-- Provider billing records are evidence for reconciliation; they never overwrite price versions.

ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_adapter;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_adapter CHECK(
  adapter_code IN (
    'LITELLM_COST_MAP','MODELS_DEV',
    'AZURE_RETAIL_PRICES','AWS_PRICE_LIST_BULK','GOOGLE_CLOUD_CATALOG','GENERIC_DOCUMENT',
    'DEEPSEEK_OFFICIAL_PAGE','QWEN_OFFICIAL_PAGE','KIMI_OFFICIAL_PAGE',
    'XIAOMI_MIMO_OFFICIAL_PAGE','ZHIPU_OFFICIAL_PAGE',
    'OFFICIAL_JSON','OFFICIAL_CSV'
  )
);

CREATE TABLE provider_billing_source (
  id varchar(64) PRIMARY KEY,
  name varchar(160) NOT NULL,
  provider_instance_id varchar(64) NOT NULL REFERENCES provider_instance(id) ON DELETE RESTRICT,
  adapter_code varchar(60) NOT NULL,
  endpoint varchar(1200) NOT NULL,
  official_hosts jsonb NOT NULL DEFAULT '[]',
  default_currency varchar(3) NOT NULL DEFAULT 'USD',
  schedule_expression varchar(120) NOT NULL DEFAULT 'P1D',
  config jsonb NOT NULL DEFAULT '{}',
  status varchar(30) NOT NULL DEFAULT 'DRAFT',
  next_run_at timestamptz,
  last_success_at timestamptz,
  last_failure_at timestamptz,
  last_error varchar(1000),
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_provider_billing_source_name UNIQUE(name),
  CONSTRAINT ck_provider_billing_adapter CHECK(adapter_code IN ('OPENAI_COSTS_API','GENERIC_BILLING_JSON')),
  CONSTRAINT ck_provider_billing_status CHECK(status IN ('DRAFT','ACTIVE','PAUSED','DEGRADED','DISABLED')),
  CONSTRAINT ck_provider_billing_currency CHECK(default_currency ~ '^[A-Z]{3}$')
);
CREATE INDEX idx_provider_billing_source_due ON provider_billing_source(status,next_run_at);

CREATE TABLE provider_billing_sync_run (
  id varchar(64) PRIMARY KEY,
  billing_source_id varchar(64) NOT NULL REFERENCES provider_billing_source(id) ON DELETE CASCADE,
  trigger_type varchar(30) NOT NULL DEFAULT 'MANUAL',
  status varchar(30) NOT NULL DEFAULT 'PENDING',
  period_start timestamptz NOT NULL,
  period_end timestamptz NOT NULL,
  records_fetched int NOT NULL DEFAULT 0,
  amount_fetched numeric(30,12) NOT NULL DEFAULT 0,
  currency varchar(3),
  error_code varchar(120),
  error_message varchar(1000),
  execution_log jsonb NOT NULL DEFAULT '[]',
  started_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_provider_billing_run_trigger CHECK(trigger_type IN ('MANUAL','SCHEDULED')),
  CONSTRAINT ck_provider_billing_run_status CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','NO_CHANGE','FAILED','CANCELLED')),
  CONSTRAINT ck_provider_billing_run_period CHECK(period_end>period_start)
);
CREATE INDEX idx_provider_billing_run_source ON provider_billing_sync_run(billing_source_id,created_at DESC);
CREATE UNIQUE INDEX uq_provider_billing_run_active_source
  ON provider_billing_sync_run(billing_source_id) WHERE status IN ('PENDING','RUNNING');

CREATE TABLE provider_billing_record (
  id varchar(64) PRIMARY KEY,
  billing_source_id varchar(64) NOT NULL REFERENCES provider_billing_source(id) ON DELETE RESTRICT,
  sync_run_id varchar(64) NOT NULL REFERENCES provider_billing_sync_run(id) ON DELETE CASCADE,
  provider_instance_id varchar(64) NOT NULL REFERENCES provider_instance(id) ON DELETE RESTRICT,
  period_start timestamptz NOT NULL,
  period_end timestamptz NOT NULL,
  currency varchar(3) NOT NULL,
  amount numeric(30,12) NOT NULL,
  input_tokens bigint,
  output_tokens bigint,
  request_count bigint,
  line_item varchar(300),
  provider_model_name varchar(240),
  provider_project_id varchar(160),
  source_ref varchar(1200) NOT NULL,
  evidence_hash varchar(64) NOT NULL,
  raw_payload jsonb NOT NULL DEFAULT '{}',
  observed_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_provider_billing_record_period CHECK(period_end>period_start),
  CONSTRAINT ck_provider_billing_record_amount CHECK(amount>=0),
  CONSTRAINT ck_provider_billing_record_currency CHECK(currency ~ '^[A-Z]{3}$'),
  CONSTRAINT uq_provider_billing_evidence UNIQUE(billing_source_id,evidence_hash)
);
CREATE INDEX idx_provider_billing_record_provider_period
  ON provider_billing_record(provider_instance_id,period_start,period_end);
CREATE INDEX idx_provider_billing_record_source ON provider_billing_record(billing_source_id,created_at DESC);

ALTER TABLE provider_reconciliation
  ADD COLUMN billing_source_id varchar(64) REFERENCES provider_billing_source(id) ON DELETE SET NULL,
  ADD COLUMN billing_sync_run_id varchar(64) REFERENCES provider_billing_sync_run(id) ON DELETE SET NULL;

CREATE INDEX idx_provider_reconciliation_billing_source
  ON provider_reconciliation(billing_source_id,period_start,period_end);
