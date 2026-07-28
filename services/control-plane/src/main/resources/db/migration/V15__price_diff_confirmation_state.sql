-- Persist consecutive price-difference confirmations independently from sync_run_id.
-- A pending diff keeps one durable confirmation state. Repeated observations of the
-- same normalized value increment confirmation_count; a different value resets it.

ALTER TABLE provider_price_diff
  ADD COLUMN IF NOT EXISTS confirmation_count int NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS last_confirmed_hash varchar(64),
  ADD COLUMN IF NOT EXISTS last_confirmed_at timestamptz;

UPDATE provider_price_diff
SET confirmation_count = greatest(coalesce(confirmation_count, 1), 1),
    last_confirmed_at = coalesce(last_confirmed_at, updated_at, created_at)
WHERE status = 'PENDING';

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ck_provider_price_diff_confirmation_count'
  ) THEN
    ALTER TABLE provider_price_diff
      ADD CONSTRAINT ck_provider_price_diff_confirmation_count
      CHECK (confirmation_count >= 1) NOT VALID;
  END IF;
END $$;

ALTER TABLE provider_price_diff
  VALIDATE CONSTRAINT ck_provider_price_diff_confirmation_count;

CREATE INDEX IF NOT EXISTS idx_provider_price_diff_confirmation
  ON provider_price_diff(status, confirmation_count, last_confirmed_at DESC);
