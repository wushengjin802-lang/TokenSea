-- Phase 3 forward-only governance, discovery and actual-cost contracts. No seed data.

CREATE TABLE data_source (
  id varchar(64) PRIMARY KEY, name varchar(160) NOT NULL, source_type varchar(40) NOT NULL,
  endpoint varchar(1000), provider_instance_id varchar(64) REFERENCES provider_instance(id) ON DELETE SET NULL,
  auth_ref varchar(200), sync_mode varchar(30) NOT NULL DEFAULT 'MANUAL', schedule_expression varchar(120),
  status varchar(30) NOT NULL DEFAULT 'ACTIVE', config jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_data_source_type CHECK(source_type IN ('PROVIDER_API','PUBLIC_REFERENCE','FILE_IMPORT')),
  CONSTRAINT ck_data_source_status CHECK(status IN ('ACTIVE','SUSPENDED','DISABLED'))
);

CREATE TABLE sync_job (
  id varchar(64) PRIMARY KEY, data_source_id varchar(64) NOT NULL REFERENCES data_source(id),
  job_type varchar(40) NOT NULL, trigger_type varchar(30) NOT NULL DEFAULT 'MANUAL', status varchar(30) NOT NULL DEFAULT 'PENDING',
  cursor_value varchar(500), records_read int NOT NULL DEFAULT 0, records_changed int NOT NULL DEFAULT 0,
  error_code varchar(120), error_message varchar(1000), started_at timestamptz, completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_sync_job_status CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED'))
);
CREATE INDEX idx_sync_job_source_created ON sync_job(data_source_id,created_at DESC);

CREATE TABLE provider_model_snapshot (
  id varchar(64) PRIMARY KEY, provider_instance_id varchar(64) NOT NULL REFERENCES provider_instance(id),
  sync_job_id varchar(64) REFERENCES sync_job(id), source_endpoint varchar(1000) NOT NULL,
  http_status int NOT NULL, checksum varchar(64) NOT NULL, raw_payload jsonb NOT NULL,
  discovered_at timestamptz NOT NULL DEFAULT now(), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_provider_snapshot_instance ON provider_model_snapshot(provider_instance_id,discovered_at DESC);

CREATE TABLE public_model_reference (
  id varchar(64) PRIMARY KEY, canonical_name varchar(240) NOT NULL UNIQUE, display_name varchar(240) NOT NULL,
  vendor varchar(160), family varchar(160), capability_claims jsonb NOT NULL DEFAULT '[]',
  context_length int, source_type varchar(40) NOT NULL, source_ref varchar(1000), source_confidence numeric(5,4),
  status varchar(30) NOT NULL DEFAULT 'ACTIVE', version int NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_reference_status CHECK(status IN ('ACTIVE','DEPRECATED','DISPUTED')),
  CONSTRAINT ck_reference_confidence CHECK(source_confidence IS NULL OR source_confidence BETWEEN 0 AND 1)
);

CREATE TABLE channel_model_deployment (
  id varchar(64) PRIMARY KEY, provider_instance_id varchar(64) NOT NULL REFERENCES provider_instance(id),
  public_model_reference_id varchar(64) REFERENCES public_model_reference(id) ON DELETE SET NULL,
  provider_model_name varchar(240) NOT NULL, display_name varchar(240), raw_model jsonb NOT NULL,
  field_sources jsonb NOT NULL DEFAULT '{}', source_snapshot_id varchar(64) NOT NULL REFERENCES provider_model_snapshot(id),
  review_status varchar(30) NOT NULL DEFAULT 'PENDING_REVIEW', routing_status varchar(30) NOT NULL DEFAULT 'INELIGIBLE',
  first_seen_at timestamptz NOT NULL DEFAULT now(), last_seen_at timestamptz NOT NULL DEFAULT now(), missing_at timestamptz,
  version int NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(provider_instance_id,provider_model_name),
  CONSTRAINT ck_deployment_review CHECK(review_status IN ('PENDING_REVIEW','APPROVED','REJECTED','MISSING')),
  CONSTRAINT ck_deployment_routing CHECK(routing_status IN ('INELIGIBLE','ELIGIBLE','SUSPENDED'))
);
CREATE INDEX idx_channel_deployment_instance ON channel_model_deployment(provider_instance_id,review_status);

CREATE TABLE model_discovery_diff (
  id varchar(64) PRIMARY KEY, deployment_id varchar(64) NOT NULL REFERENCES channel_model_deployment(id),
  snapshot_id varchar(64) NOT NULL REFERENCES provider_model_snapshot(id), field_name varchar(160) NOT NULL,
  old_value jsonb, new_value jsonb, source varchar(500) NOT NULL, confidence numeric(5,4),
  decision varchar(30) NOT NULL DEFAULT 'PENDING', decision_reason varchar(1000), decided_by varchar(64), decided_at timestamptz,
  rollback_of varchar(64) REFERENCES model_discovery_diff(id), created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_diff_decision CHECK(decision IN ('PENDING','ACCEPTED','IGNORED','PINNED','ROLLED_BACK')),
  CONSTRAINT ck_diff_confidence CHECK(confidence IS NULL OR confidence BETWEEN 0 AND 1)
);
CREATE INDEX idx_model_diff_pending ON model_discovery_diff(decision,created_at DESC);

CREATE TABLE capability_validation (
  id varchar(64) PRIMARY KEY, deployment_id varchar(64) NOT NULL REFERENCES channel_model_deployment(id),
  capability_code varchar(100) NOT NULL, test_type varchar(80) NOT NULL, request_summary jsonb NOT NULL DEFAULT '{}',
  response_summary jsonb NOT NULL DEFAULT '{}', status varchar(30) NOT NULL, evidence_ref varchar(1000),
  latency_ms int, validated_by varchar(64), validated_at timestamptz NOT NULL DEFAULT now(), created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_capability_status CHECK(status IN ('PASSED','FAILED','INCONCLUSIVE'))
);
CREATE INDEX idx_capability_deployment ON capability_validation(deployment_id,validated_at DESC);

CREATE TABLE price_version (
  id varchar(64) PRIMARY KEY, price_layer varchar(40) NOT NULL, public_model_reference_id varchar(64) REFERENCES public_model_reference(id),
  deployment_id varchar(64) REFERENCES channel_model_deployment(id), platform_model_id varchar(64) REFERENCES platform_model(id),
  currency varchar(3) NOT NULL, input_amount_per_1k numeric(20,8) NOT NULL, output_amount_per_1k numeric(20,8) NOT NULL,
  source_type varchar(50) NOT NULL, source_ref varchar(1000), source_confidence numeric(5,4), version int NOT NULL,
  effective_from timestamptz NOT NULL, effective_to timestamptz, status varchar(30) NOT NULL DEFAULT 'DRAFT',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_price_layer CHECK(price_layer IN ('PUBLIC_REFERENCE','CHANNEL_ACTUAL','INTERNAL_ACCOUNTING')),
  CONSTRAINT ck_price_status CHECK(status IN ('DRAFT','PENDING_APPROVAL','ACTIVE','RETIRED','REJECTED')),
  CONSTRAINT ck_price_amount CHECK(input_amount_per_1k>=0 AND output_amount_per_1k>=0),
  CONSTRAINT ck_price_period CHECK(effective_to IS NULL OR effective_to>effective_from),
  CONSTRAINT ck_price_confidence CHECK(source_confidence IS NULL OR source_confidence BETWEEN 0 AND 1)
);
CREATE INDEX idx_price_version_effective ON price_version(price_layer,status,effective_from DESC);

CREATE TABLE budget_rule (
  id varchar(64) PRIMARY KEY, scope_type varchar(30) NOT NULL, scope_id varchar(64) NOT NULL,
  currency varchar(3) NOT NULL, amount_limit numeric(20,8) NOT NULL, warning_threshold_percent numeric(5,2) NOT NULL DEFAULT 80,
  over_limit_action varchar(30) NOT NULL, degrade_model_alias varchar(200), status varchar(30) NOT NULL DEFAULT 'ACTIVE', version int NOT NULL DEFAULT 1,
  effective_from timestamptz NOT NULL DEFAULT now(), effective_to timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_budget_scope CHECK(scope_type IN ('TENANT','PROJECT','APP','API_KEY')),
  CONSTRAINT ck_budget_action CHECK(over_limit_action IN ('BLOCK','ALERT_ONLY','DEGRADE')),
  CONSTRAINT ck_budget_values CHECK(amount_limit>=0 AND warning_threshold_percent>0 AND warning_threshold_percent<=100),
  UNIQUE(scope_type,scope_id,version)
);

CREATE TABLE governance_version (
  id varchar(64) PRIMARY KEY, resource_type varchar(50) NOT NULL, resource_id varchar(64) NOT NULL,
  version int NOT NULL, snapshot jsonb NOT NULL, source_action varchar(80) NOT NULL,
  created_by varchar(64), created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(resource_type,resource_id,version)
);
CREATE TABLE approval_request (
  id varchar(64) PRIMARY KEY, resource_type varchar(50) NOT NULL, resource_id varchar(64) NOT NULL,
  version_id varchar(64) NOT NULL REFERENCES governance_version(id), risk_level varchar(20) NOT NULL,
  status varchar(30) NOT NULL DEFAULT 'PENDING', reason varchar(1000) NOT NULL,
  requested_by varchar(64), requested_at timestamptz NOT NULL DEFAULT now(), decided_by varchar(64), decision_reason varchar(1000), decided_at timestamptz,
  CONSTRAINT ck_approval_risk CHECK(risk_level IN ('MEDIUM','HIGH','CRITICAL')),
  CONSTRAINT ck_approval_status CHECK(status IN ('PENDING','APPROVED','REJECTED','EXECUTED','CANCELLED'))
);
CREATE INDEX idx_approval_pending ON approval_request(status,requested_at);

CREATE TABLE usage_cost_snapshot (
  id varchar(64) PRIMARY KEY, request_id varchar(100) NOT NULL UNIQUE, usage_record_id varchar(64) REFERENCES usage_record(id),
  price_version_id varchar(64), price_layer varchar(40) NOT NULL DEFAULT 'CHANNEL_ACTUAL', currency varchar(3) NOT NULL,
  input_amount_per_1k numeric(20,8) NOT NULL, output_amount_per_1k numeric(20,8) NOT NULL,
  prompt_tokens int NOT NULL, completion_tokens int NOT NULL, actual_cost_amount numeric(20,8) NOT NULL,
  source_ref varchar(1000), created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE request_attempt ADD COLUMN IF NOT EXISTS cost_snapshot jsonb NOT NULL DEFAULT '{}', ADD COLUMN IF NOT EXISTS actual_cost_amount numeric(20,8) NOT NULL DEFAULT 0;
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS is_default boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX IF NOT EXISTS uq_api_key_default_tenant ON api_key(tenant_id) WHERE is_default=true;

CREATE TABLE provider_reconciliation (
  id varchar(64) PRIMARY KEY, provider_instance_id varchar(64) NOT NULL REFERENCES provider_instance(id),
  period_start date NOT NULL, period_end date NOT NULL, currency varchar(3) NOT NULL,
  internal_cost numeric(20,8) NOT NULL, provider_amount numeric(20,8) NOT NULL, difference_amount numeric(20,8) NOT NULL,
  status varchar(30) NOT NULL DEFAULT 'OPEN', source_ref varchar(1000) NOT NULL, notes varchar(2000),
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_reconciliation_period CHECK(period_end>=period_start),
  CONSTRAINT ck_reconciliation_status CHECK(status IN ('OPEN','MATCHED','DISPUTED','RESOLVED'))
);

CREATE TABLE alert_event (
  id varchar(64) PRIMARY KEY, alert_type varchar(80) NOT NULL, severity varchar(20) NOT NULL,
  resource_type varchar(50) NOT NULL, resource_id varchar(100) NOT NULL, title varchar(300) NOT NULL,
  detail jsonb NOT NULL DEFAULT '{}', status varchar(30) NOT NULL DEFAULT 'OPEN',
  acknowledged_by varchar(64), acknowledged_at timestamptz, resolved_by varchar(64), resolved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_alert_severity CHECK(severity IN ('INFO','WARNING','HIGH','CRITICAL')),
  CONSTRAINT ck_alert_status CHECK(status IN ('OPEN','ACKNOWLEDGED','RESOLVED'))
);
CREATE INDEX idx_alert_open ON alert_event(status,severity,created_at DESC);

CREATE TABLE sensitive_access_log (
  id varchar(64) PRIMARY KEY, actor_id varchar(64) NOT NULL, object_type varchar(80) NOT NULL,
  object_id varchar(100) NOT NULL, reason varchar(1000) NOT NULL, fields_viewed jsonb NOT NULL DEFAULT '[]',
  created_at timestamptz NOT NULL DEFAULT now()
);
