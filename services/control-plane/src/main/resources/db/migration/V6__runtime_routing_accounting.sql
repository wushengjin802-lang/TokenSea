-- Forward-only data-plane contracts for platform-model routing and accounting.

ALTER TABLE model_price ALTER COLUMN model_id DROP NOT NULL;
ALTER TABLE model_price
  ADD COLUMN IF NOT EXISTS platform_model_id varchar(64),
  ADD COLUMN IF NOT EXISTS provider_instance_id varchar(64);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_model_price_platform_model') THEN
    ALTER TABLE model_price ADD CONSTRAINT fk_model_price_platform_model
      FOREIGN KEY (platform_model_id) REFERENCES platform_model(id) ON DELETE CASCADE NOT VALID;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_model_price_provider_instance') THEN
    ALTER TABLE model_price ADD CONSTRAINT fk_model_price_provider_instance
      FOREIGN KEY (provider_instance_id) REFERENCES provider_instance(id) ON DELETE CASCADE NOT VALID;
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_model_price_platform_effective
  ON model_price(platform_model_id, provider_instance_id, status, effective_from DESC);

ALTER TABLE usage_record
  ADD COLUMN IF NOT EXISTS price_version_id varchar(64),
  ADD COLUMN IF NOT EXISTS budget_reserved_amount numeric(20,8) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS request_attempt (
  id varchar(64) PRIMARY KEY,
  request_id varchar(100) NOT NULL,
  attempt_no int NOT NULL,
  provider_instance_id varchar(64) REFERENCES provider_instance(id) ON DELETE SET NULL,
  runtime_model_name varchar(200),
  price_version_id varchar(64),
  status varchar(40) NOT NULL,
  http_status int,
  error_code varchar(120),
  prompt_tokens int NOT NULL DEFAULT 0,
  completion_tokens int NOT NULL DEFAULT 0,
  total_tokens int NOT NULL DEFAULT 0,
  latency_ms int,
  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(request_id, attempt_no)
);
CREATE INDEX IF NOT EXISTS idx_request_attempt_request ON request_attempt(request_id, attempt_no);
CREATE INDEX IF NOT EXISTS idx_request_attempt_provider ON request_attempt(provider_instance_id, created_at DESC);

