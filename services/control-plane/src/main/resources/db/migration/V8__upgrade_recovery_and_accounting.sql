-- Forward-only recovery and accounting contracts. V5/V6/V7 are immutable.

CREATE TABLE IF NOT EXISTS migration_quarantine (
  id varchar(64) PRIMARY KEY,
  source_table varchar(80) NOT NULL,
  source_id varchar(64) NOT NULL,
  reason varchar(200) NOT NULL,
  quarantined_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(source_table, source_id, reason)
);

-- Never infer an administrator. Existing installations without ADMIN are made
-- recoverable through the explicit one-time bootstrap-token path.
UPDATE platform_bootstrap_state s
SET initialized = EXISTS (
      SELECT 1 FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE r.code='ADMIN'
    ),
    initialized_by = CASE WHEN EXISTS (
      SELECT 1 FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE r.code='ADMIN'
    ) THEN initialized_by ELSE NULL END,
    initialized_at = CASE WHEN EXISTS (
      SELECT 1 FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE r.code='ADMIN'
    ) THEN initialized_at ELSE NULL END,
    updated_at = now()
WHERE singleton=true;

CREATE TABLE IF NOT EXISTS platform_setting (
  setting_key varchar(100) PRIMARY KEY,
  setting_value varchar(500) NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO platform_setting(setting_key, setting_value)
VALUES ('BUDGET_CURRENCY', 'CNY') ON CONFLICT (setting_key) DO NOTHING;

ALTER TABLE usage_record
  ADD COLUMN IF NOT EXISTS budget_status varchar(40) NOT NULL DEFAULT 'NOT_APPLICABLE',
  ADD COLUMN IF NOT EXISTS accounting_status varchar(40) NOT NULL DEFAULT 'PENDING';

CREATE TABLE IF NOT EXISTS accounting_outbox (
  id varchar(64) PRIMARY KEY,
  aggregate_type varchar(80) NOT NULL,
  aggregate_id varchar(100) NOT NULL,
  event_type varchar(120) NOT NULL,
  payload text NOT NULL DEFAULT '{}',
  status varchar(40) NOT NULL DEFAULT 'PENDING',
  attempts int NOT NULL DEFAULT 0,
  available_at timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_accounting_outbox_pending
  ON accounting_outbox(status, available_at, created_at);
