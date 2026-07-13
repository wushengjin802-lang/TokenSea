-- Phase 3C forward-only runtime execution metadata. V5-V10 remain immutable.

ALTER TABLE data_source
  ADD COLUMN IF NOT EXISTS last_sync_at timestamptz,
  ADD COLUMN IF NOT EXISTS next_run_at timestamptz,
  ADD COLUMN IF NOT EXISTS last_sync_status varchar(30),
  ADD COLUMN IF NOT EXISTS last_sync_error varchar(1000);

ALTER TABLE sync_job
  ADD COLUMN IF NOT EXISTS requested_by varchar(64),
  ADD COLUMN IF NOT EXISTS scheduled_for timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN IF NOT EXISTS execution_log jsonb NOT NULL DEFAULT '[]',
  ADD COLUMN IF NOT EXISTS lock_owner varchar(100),
  ADD COLUMN IF NOT EXISTS heartbeat_at timestamptz;
CREATE INDEX IF NOT EXISTS idx_sync_job_runnable ON sync_job(status,scheduled_for,created_at);

ALTER TABLE capability_validation
  ADD COLUMN IF NOT EXISTS probe_endpoint varchar(1000),
  ADD COLUMN IF NOT EXISTS http_status int,
  ADD COLUMN IF NOT EXISTS stream_verified boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS probe_request_id varchar(100);

ALTER TABLE price_version
  ADD COLUMN IF NOT EXISTS activated_by varchar(64),
  ADD COLUMN IF NOT EXISTS activated_at timestamptz;

CREATE TABLE IF NOT EXISTS budget_rule_event (
  id varchar(64) PRIMARY KEY,
  rule_id varchar(64) NOT NULL REFERENCES budget_rule(id),
  request_id varchar(100),
  current_cost numeric(20,8) NOT NULL,
  estimated_cost numeric(20,8) NOT NULL,
  threshold_cost numeric(20,8) NOT NULL,
  action varchar(30) NOT NULL,
  detail jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_budget_rule_event_rule ON budget_rule_event(rule_id,created_at DESC);

ALTER TABLE platform_setting ADD COLUMN IF NOT EXISTS description varchar(500), ADD COLUMN IF NOT EXISTS sensitive boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS runtime_quickstart_config (
  id varchar(64) PRIMARY KEY,
  tenant_id varchar(64) REFERENCES tenant(id),
  api_key_id varchar(64) REFERENCES api_key(id),
  platform_model_id varchar(64) REFERENCES platform_model(id),
  gateway_base varchar(500) NOT NULL,
  status varchar(30) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,api_key_id,platform_model_id)
);
