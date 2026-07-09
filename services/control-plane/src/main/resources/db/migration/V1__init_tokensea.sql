CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS user_account (
  id varchar(64) PRIMARY KEY,
  username varchar(100) UNIQUE NOT NULL,
  password_hash varchar(255) NOT NULL,
  display_name varchar(100),
  email varchar(200),
  status varchar(30) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS role (
  id varchar(64) PRIMARY KEY,
  code varchar(100) UNIQUE NOT NULL,
  name varchar(100) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS permission (
  id varchar(64) PRIMARY KEY,
  code varchar(160) UNIQUE NOT NULL,
  name varchar(160) NOT NULL,
  type varchar(30) NOT NULL DEFAULT 'MENU',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS user_role (
  user_id varchar(64) NOT NULL,
  role_id varchar(64) NOT NULL,
  PRIMARY KEY(user_id, role_id)
);

CREATE TABLE IF NOT EXISTS role_permission (
  role_id varchar(64) NOT NULL,
  permission_id varchar(64) NOT NULL,
  PRIMARY KEY(role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS tenant (
  id varchar(64) PRIMARY KEY,
  name varchar(200) NOT NULL,
  type varchar(40) NOT NULL DEFAULT 'INTERNAL',
  status varchar(40) NOT NULL DEFAULT 'ACTIVE',
  owner_name varchar(100),
  contact_email varchar(200),
  model_scope text NOT NULL DEFAULT '[]',
  monthly_budget numeric(20,6),
  remark text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS department (
  id varchar(64) PRIMARY KEY,
  tenant_id varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
  name varchar(200) NOT NULL,
  parent_id varchar(64),
  status varchar(40) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS project (
  id varchar(64) PRIMARY KEY,
  tenant_id varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
  name varchar(200) NOT NULL,
  owner_name varchar(100),
  monthly_budget numeric(20,6),
  status varchar(40) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS app (
  id varchar(64) PRIMARY KEY,
  tenant_id varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
  project_id varchar(64) REFERENCES project(id) ON DELETE SET NULL,
  name varchar(200) NOT NULL,
  owner_name varchar(100),
  environment varchar(40) NOT NULL DEFAULT 'DEV',
  status varchar(40) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS provider (
  id varchar(64) PRIMARY KEY,
  name varchar(200) NOT NULL,
  provider_type varchar(80) NOT NULL,
  api_style varchar(80) NOT NULL DEFAULT 'openai_compatible',
  base_url varchar(500),
  region varchar(100),
  status varchar(40) NOT NULL DEFAULT 'ACTIVE',
  health_check_url varchar(500),
  rate_limit_rpm int,
  rate_limit_tpm int,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS provider_secret (
  id varchar(64) PRIMARY KEY,
  provider_id varchar(64) NOT NULL REFERENCES provider(id) ON DELETE CASCADE,
  secret_name varchar(120) NOT NULL DEFAULT 'api_key',
  secret_cipher text NOT NULL,
  secret_last4 varchar(8),
  status varchar(40) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS model (
  id varchar(64) PRIMARY KEY,
  alias varchar(160) UNIQUE NOT NULL,
  display_name varchar(200) NOT NULL,
  context_length int,
  capability_tags text NOT NULL DEFAULT '[]',
  supported_endpoints text NOT NULL DEFAULT '["chat/completions"]',
  status varchar(40) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS model_deployment (
  id varchar(64) PRIMARY KEY,
  model_id varchar(64) NOT NULL REFERENCES model(id) ON DELETE CASCADE,
  provider_id varchar(64) NOT NULL REFERENCES provider(id) ON DELETE CASCADE,
  deployment_name varchar(200) NOT NULL,
  runtime_model_name varchar(200) NOT NULL,
  priority int NOT NULL DEFAULT 100,
  weight int NOT NULL DEFAULT 100,
  status varchar(40) NOT NULL DEFAULT 'ACTIVE',
  timeout_seconds int NOT NULL DEFAULT 120,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS model_price (
  id varchar(64) PRIMARY KEY,
  model_id varchar(64) NOT NULL REFERENCES model(id) ON DELETE CASCADE,
  currency varchar(20) NOT NULL DEFAULT 'CNY',
  input_cost_per_1k numeric(20,8) NOT NULL DEFAULT 0,
  output_cost_per_1k numeric(20,8) NOT NULL DEFAULT 0,
  input_price_per_1k numeric(20,8) NOT NULL DEFAULT 0,
  output_price_per_1k numeric(20,8) NOT NULL DEFAULT 0,
  effective_from timestamptz NOT NULL DEFAULT now(),
  effective_to timestamptz,
  status varchar(40) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS api_key (
  id varchar(64) PRIMARY KEY,
  tenant_id varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
  project_id varchar(64) REFERENCES project(id) ON DELETE SET NULL,
  app_id varchar(64) REFERENCES app(id) ON DELETE SET NULL,
  name varchar(200) NOT NULL,
  key_hash varchar(128) UNIQUE NOT NULL,
  key_prefix varchar(24) NOT NULL,
  status varchar(40) NOT NULL DEFAULT 'PENDING',
  approval_status varchar(40) NOT NULL DEFAULT 'PENDING',
  model_scope text NOT NULL DEFAULT '[]',
  budget_amount numeric(20,6),
  rpm_limit int,
  tpm_limit int,
  qps_limit int,
  ip_whitelist text NOT NULL DEFAULT '[]',
  expires_at timestamptz,
  created_by varchar(64),
  approved_by varchar(64),
  approved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS approval_task (
  id varchar(64) PRIMARY KEY,
  object_type varchar(80) NOT NULL,
  object_id varchar(64) NOT NULL,
  status varchar(40) NOT NULL DEFAULT 'PENDING',
  applicant_id varchar(64),
  approver_id varchar(64),
  opinion text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS route_policy (
  id varchar(64) PRIMARY KEY,
  name varchar(200) NOT NULL,
  model_alias varchar(160) NOT NULL,
  strategy varchar(80) NOT NULL DEFAULT 'priority',
  fallback_enabled boolean NOT NULL DEFAULT true,
  status varchar(40) NOT NULL DEFAULT 'ACTIVE',
  config text NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS usage_record (
  id varchar(64) PRIMARY KEY,
  request_id varchar(100) NOT NULL,
  tenant_id varchar(64),
  project_id varchar(64),
  app_id varchar(64),
  api_key_id varchar(64),
  model_alias varchar(160),
  runtime_model_name varchar(200),
  provider_id varchar(64),
  prompt_tokens int NOT NULL DEFAULT 0,
  completion_tokens int NOT NULL DEFAULT 0,
  total_tokens int NOT NULL DEFAULT 0,
  cost_amount numeric(20,8) NOT NULL DEFAULT 0,
  sales_amount numeric(20,8) NOT NULL DEFAULT 0,
  currency varchar(20) NOT NULL DEFAULT 'CNY',
  status varchar(40) NOT NULL,
  error_code varchar(120),
  latency_ms int,
  fallback_chain text NOT NULL DEFAULT '[]',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_usage_created ON usage_record(created_at);
CREATE INDEX IF NOT EXISTS idx_usage_key ON usage_record(api_key_id);

CREATE TABLE IF NOT EXISTS billing_record (
  id varchar(64) PRIMARY KEY,
  tenant_id varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
  period_start date NOT NULL,
  period_end date NOT NULL,
  total_tokens bigint NOT NULL DEFAULT 0,
  total_cost numeric(20,8) NOT NULL DEFAULT 0,
  total_sales numeric(20,8) NOT NULL DEFAULT 0,
  status varchar(40) NOT NULL DEFAULT 'DRAFT',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS audit_log (
  id varchar(64) PRIMARY KEY,
  actor_id varchar(64),
  actor_name varchar(100),
  action varchar(160) NOT NULL,
  object_type varchar(80),
  object_id varchar(64),
  before_value text,
  after_value text,
  ip_address varchar(100),
  user_agent text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS provider_health (
  id varchar(64) PRIMARY KEY,
  provider_id varchar(64) REFERENCES provider(id) ON DELETE CASCADE,
  status varchar(40) NOT NULL,
  latency_ms int,
  error_message text,
  checked_at timestamptz NOT NULL DEFAULT now()
);


CREATE TABLE IF NOT EXISTS identity_record (
  id varchar(64) PRIMARY KEY,
  usage_record_id varchar(64),
  identity_level varchar(20),
  identity_source varchar(120),
  source_ip varchar(100),
  user_agent text,
  session_id_hash varchar(128),
  confidence_score numeric(8,4),
  created_at timestamptz NOT NULL DEFAULT now()
);
