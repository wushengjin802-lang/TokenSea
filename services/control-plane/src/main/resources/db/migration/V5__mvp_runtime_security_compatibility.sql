-- Forward-only compatibility fixes for the MVP runtime and immutable records.
-- Historical migrations are intentionally left unchanged.

ALTER TABLE usage_record
  ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE audit_log
  ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

-- Gateway and console use the canonical upper-case values.
UPDATE usage_record SET status = 'SUCCESS' WHERE upper(status) = 'SUCCESS' AND status <> 'SUCCESS';
UPDATE usage_record SET status = 'FAILED' WHERE upper(status) IN ('FAILED', 'FAIL', 'ERROR') AND status <> 'FAILED';

-- A secret can now belong to the real provider_instance object. Legacy provider
-- references remain valid so existing installations are not broken.
ALTER TABLE provider_secret
  ALTER COLUMN provider_id DROP NOT NULL;
ALTER TABLE provider_secret
  ADD COLUMN IF NOT EXISTS provider_instance_id varchar(64) REFERENCES provider_instance(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_provider_secret_instance
  ON provider_secret(provider_instance_id, status);

ALTER TABLE provider_instance
  ADD COLUMN IF NOT EXISTS last_connection_test_at timestamptz,
  ADD COLUMN IF NOT EXISTS last_connection_test_status varchar(40),
  ADD COLUMN IF NOT EXISTS last_connection_test_error text;

-- These exact rows were introduced by V2/V4 as catalogue demonstrations. They
-- have no real provider binding, so they must not be presented as callable.
-- A user-created or already-bound service model is deliberately not touched.
UPDATE platform_model
SET status = '草稿',
    actual_models = '[]',
    route_policy_id = NULL,
    route_policy = NULL,
    price_policy_id = NULL,
    price_policy = NULL,
    updated_at = now()
WHERE id IN (
  'pm_chat_enterprise', 'pm_chat_reasoning', 'pm_chat_lowcost',
  'pm_code_agent', 'pm_embedding_standard', 'pm_rerank_standard',
  'pm_image_generation', 'pm_realtime_voice'
)
AND provider_instance_ids = '[]';

-- Useful indexes for the data-plane lookups.
CREATE INDEX IF NOT EXISTS idx_platform_model_runtime
  ON platform_model(platform_model_name, status);
CREATE INDEX IF NOT EXISTS idx_api_key_hash_status
  ON api_key(key_hash, status, approval_status);
