-- Forward-only control-plane contracts. V5/V6 are immutable once published.

-- Bootstrap is a database-serialized, one-time operation. Existing installations
-- are marked initialized without guessing which user should receive ADMIN.
CREATE TABLE IF NOT EXISTS platform_bootstrap_state (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  initialized boolean NOT NULL DEFAULT false,
  initialized_by varchar(64),
  initialized_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO platform_bootstrap_state(singleton, initialized)
VALUES (true, EXISTS (SELECT 1 FROM user_account))
ON CONFLICT (singleton) DO NOTHING;
INSERT INTO role(id, code, name) VALUES ('role_admin', 'ADMIN', '平台管理员')
ON CONFLICT (code) DO NOTHING;

ALTER TABLE provider_instance
  ADD COLUMN IF NOT EXISTS last_connection_test_host varchar(255),
  ADD COLUMN IF NOT EXISTS last_connection_test_addresses text;

-- Provider secrets have exactly one owner. Existing V1 provider secrets remain
-- valid; new channel secrets use provider_instance_id.
ALTER TABLE provider_secret ALTER COLUMN provider_id DROP NOT NULL;
ALTER TABLE provider_secret ADD COLUMN IF NOT EXISTS provider_instance_id varchar(64);
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_provider_secret_instance') THEN
    ALTER TABLE provider_secret ADD CONSTRAINT fk_provider_secret_instance
      FOREIGN KEY (provider_instance_id) REFERENCES provider_instance(id) ON DELETE CASCADE NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_provider_secret_exact_owner') THEN
    ALTER TABLE provider_secret ADD CONSTRAINT ck_provider_secret_exact_owner
      CHECK (num_nonnulls(provider_id, provider_instance_id)=1) NOT VALID;
  END IF;
END $$;
ALTER TABLE provider_secret VALIDATE CONSTRAINT fk_provider_secret_instance;
ALTER TABLE provider_secret VALIDATE CONSTRAINT ck_provider_secret_exact_owner;
CREATE INDEX IF NOT EXISTS idx_provider_secret_instance_name
  ON provider_secret(provider_instance_id, secret_name, status);

-- V6 introduced these NOT VALID FKs; validate them without changing V6.
ALTER TABLE model_price VALIDATE CONSTRAINT fk_model_price_platform_model;
ALTER TABLE model_price VALIDATE CONSTRAINT fk_model_price_provider_instance;

-- A price belongs either to one legacy model, or to one platform model with an
-- optional channel override. Zero is explicitly allowed for free models; all
-- monetary values must still be non-negative.
UPDATE model_price SET currency=upper(currency) WHERE currency<>upper(currency);
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_model_price_exact_owner') THEN
    ALTER TABLE model_price ADD CONSTRAINT ck_model_price_exact_owner CHECK (
      (model_id IS NOT NULL AND platform_model_id IS NULL AND provider_instance_id IS NULL)
      OR (model_id IS NULL AND platform_model_id IS NOT NULL)
    ) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_model_price_nonnegative') THEN
    ALTER TABLE model_price ADD CONSTRAINT ck_model_price_nonnegative CHECK (
      input_cost_per_1k>=0 AND output_cost_per_1k>=0
      AND input_price_per_1k>=0 AND output_price_per_1k>=0
    ) NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_model_price_currency') THEN
    ALTER TABLE model_price ADD CONSTRAINT ck_model_price_currency
      CHECK (currency ~ '^[A-Z]{3}$') NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_model_price_effective_range') THEN
    ALTER TABLE model_price ADD CONSTRAINT ck_model_price_effective_range
      CHECK (effective_to IS NULL OR effective_to>effective_from) NOT VALID;
  END IF;
END $$;
ALTER TABLE model_price VALIDATE CONSTRAINT ck_model_price_exact_owner;
ALTER TABLE model_price VALIDATE CONSTRAINT ck_model_price_nonnegative;
ALTER TABLE model_price VALIDATE CONSTRAINT ck_model_price_currency;
ALTER TABLE model_price VALIDATE CONSTRAINT ck_model_price_effective_range;

CREATE EXTENSION IF NOT EXISTS btree_gist;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ex_model_price_legacy_active') THEN
    ALTER TABLE model_price ADD CONSTRAINT ex_model_price_legacy_active
      EXCLUDE USING gist (
        model_id WITH =,
        tstzrange(effective_from, coalesce(effective_to, 'infinity'::timestamptz), '[)') WITH &&
      ) WHERE (status='ACTIVE' AND model_id IS NOT NULL);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ex_model_price_platform_active') THEN
    ALTER TABLE model_price ADD CONSTRAINT ex_model_price_platform_active
      EXCLUDE USING gist (
        platform_model_id WITH =,
        (coalesce(provider_instance_id, '')) WITH =,
        tstzrange(effective_from, coalesce(effective_to, 'infinity'::timestamptz), '[)') WITH &&
      ) WHERE (status='ACTIVE' AND platform_model_id IS NOT NULL);
  END IF;
END $$;

-- Platform model references are real contracts, not display-only labels.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_platform_model_route_policy') THEN
    ALTER TABLE platform_model ADD CONSTRAINT fk_platform_model_route_policy
      FOREIGN KEY (route_policy_id) REFERENCES route_policy(id) ON DELETE RESTRICT NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_platform_model_price_policy') THEN
    ALTER TABLE platform_model ADD CONSTRAINT fk_platform_model_price_policy
      FOREIGN KEY (price_policy_id) REFERENCES model_price(id) ON DELETE RESTRICT NOT VALID;
  END IF;
END $$;
ALTER TABLE platform_model VALIDATE CONSTRAINT fk_platform_model_route_policy;
ALTER TABLE platform_model VALIDATE CONSTRAINT fk_platform_model_price_policy;

-- Empty model scope is never interpreted as unrestricted.
WITH revoked AS (
  UPDATE api_key SET status='DISABLED', approval_status='REVOKED', updated_at=now()
  WHERE btrim(coalesce(model_scope,'')) IN ('','[]') AND status<>'DISABLED'
  RETURNING id
)
INSERT INTO audit_log(id, action, object_type, object_id, after_value, created_at, updated_at)
SELECT replace(gen_random_uuid()::text,'-',''), 'KEY_SCOPE_REVOKED', 'ApiKey', id,
       '{"reason":"empty_model_scope"}', now(), now() FROM revoked;
