-- Server-owned user-to-tenant authorization and provider target snapshots.
CREATE TABLE IF NOT EXISTS user_tenant (
  user_id varchar(64) NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
  tenant_id varchar(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
  status varchar(40) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(user_id, tenant_id)
);
CREATE INDEX IF NOT EXISTS idx_user_tenant_tenant ON user_tenant(tenant_id, status);

ALTER TABLE provider_instance
  ADD COLUMN IF NOT EXISTS last_connection_test_port int;
ALTER TABLE provider_instance
  ADD CONSTRAINT ck_provider_connection_test_port
  CHECK (last_connection_test_port IS NULL OR last_connection_test_port BETWEEN 1 AND 65535) NOT VALID;
ALTER TABLE provider_instance VALIDATE CONSTRAINT ck_provider_connection_test_port;
