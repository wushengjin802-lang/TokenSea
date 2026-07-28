-- TokenSea 多币种治理：价格与用量明细保留原币种，汇总及预算统一折算为 CNY。

CREATE TABLE IF NOT EXISTS fx_rate_sync_run (
  id varchar(64) PRIMARY KEY,
  trigger_type varchar(30) NOT NULL,
  rate_month date NOT NULL,
  source_url varchar(1000) NOT NULL,
  status varchar(30) NOT NULL DEFAULT 'RUNNING',
  source_date date,
  records_written int NOT NULL DEFAULT 0,
  records_skipped int NOT NULL DEFAULT 0,
  error_message varchar(2000),
  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  created_by varchar(64),
  CONSTRAINT ck_fx_sync_trigger CHECK(trigger_type IN ('SCHEDULED','RECOVERY','MANUAL_TRIGGER','RESTORE_AUTO')),
  CONSTRAINT ck_fx_sync_status CHECK(status IN ('RUNNING','SUCCEEDED','PARTIAL','FAILED')),
  CONSTRAINT ck_fx_sync_month CHECK(rate_month=date_trunc('month',rate_month)::date)
);
CREATE INDEX IF NOT EXISTS idx_fx_sync_run_month ON fx_rate_sync_run(rate_month,started_at DESC);

CREATE TABLE IF NOT EXISTS fx_rate (
  id varchar(64) PRIMARY KEY,
  rate_month date NOT NULL,
  from_currency varchar(3) NOT NULL,
  to_currency varchar(3) NOT NULL DEFAULT 'CNY',
  rate numeric(24,12) NOT NULL,
  source_type varchar(30) NOT NULL,
  source_ref varchar(1000) NOT NULL,
  source_date date NOT NULL,
  note varchar(1000),
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  version int NOT NULL,
  sync_run_id varchar(64) REFERENCES fx_rate_sync_run(id),
  created_by varchar(64),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_fx_rate_month CHECK(rate_month=date_trunc('month',rate_month)::date),
  CONSTRAINT ck_fx_rate_currency CHECK(from_currency ~ '^[A-Z]{3}$' AND to_currency ~ '^[A-Z]{3}$' AND from_currency<>to_currency),
  CONSTRAINT ck_fx_rate_value CHECK(rate>0),
  CONSTRAINT ck_fx_rate_source CHECK(source_type IN ('AUTOMATIC_ECB','MANUAL')),
  CONSTRAINT ck_fx_rate_status CHECK(status IN ('ACTIVE','SUPERSEDED')),
  UNIQUE(rate_month,from_currency,to_currency,version)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_fx_rate_active
  ON fx_rate(rate_month,from_currency,to_currency) WHERE status='ACTIVE';
CREATE INDEX IF NOT EXISTS idx_fx_rate_lookup
  ON fx_rate(rate_month,from_currency,to_currency,status,version DESC);

INSERT INTO platform_setting(setting_key,setting_value,description,sensitive,updated_at)
VALUES
  ('BASE_CURRENCY','CNY','平台汇总、预算与内部核算基准币种',false,now()),
  ('FX_AUTO_UPDATE_ENABLED','true','每月自动更新汇率',false,now()),
  ('FX_RATE_SOURCE_URL','https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml','ECB 官方欧元参考汇率 XML',false,now()),
  ('FX_MANAGED_CURRENCIES','USD','自动维护并折算至 CNY 的原币种',false,now())
ON CONFLICT(setting_key) DO UPDATE SET
  description=excluded.description,
  sensitive=excluded.sensitive,
  updated_at=platform_setting.updated_at;

UPDATE platform_setting
SET setting_value='CNY',updated_at=now()
WHERE setting_key IN ('BASE_CURRENCY','BUDGET_CURRENCY') AND setting_value<>'CNY';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_budget_rule_currency_cny') THEN
    ALTER TABLE budget_rule ADD CONSTRAINT ck_budget_rule_currency_cny CHECK(currency='CNY') NOT VALID;
  END IF;
END $$;

ALTER TABLE usage_record
  ADD COLUMN IF NOT EXISTS budget_currency varchar(3) NOT NULL DEFAULT 'CNY';
UPDATE usage_record SET budget_currency='CNY' WHERE budget_currency<>'CNY';
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_usage_budget_currency') THEN
    ALTER TABLE usage_record ADD CONSTRAINT ck_usage_budget_currency CHECK(budget_currency='CNY') NOT VALID;
  END IF;
END $$;
ALTER TABLE usage_record VALIDATE CONSTRAINT ck_usage_budget_currency;

CREATE OR REPLACE FUNCTION tokensea_fx_rate(
  p_timestamp timestamptz,
  p_from_currency varchar,
  p_to_currency varchar DEFAULT 'CNY'
) RETURNS numeric
LANGUAGE sql
STABLE
AS $$
  SELECT CASE
    WHEN upper(p_from_currency)=upper(p_to_currency) THEN 1::numeric
    ELSE (
      SELECT r.rate
      FROM fx_rate r
      WHERE r.rate_month=date_trunc('month',p_timestamp AT TIME ZONE 'Asia/Shanghai')::date
        AND r.from_currency=upper(p_from_currency)
        AND r.to_currency=upper(p_to_currency)
        AND r.status='ACTIVE'
      ORDER BY r.version DESC
      LIMIT 1
    )
  END
$$;

CREATE OR REPLACE FUNCTION tokensea_fx_amount(
  p_amount numeric,
  p_currency varchar,
  p_timestamp timestamptz,
  p_target_currency varchar DEFAULT 'CNY'
) RETURNS numeric
LANGUAGE sql
STABLE
AS $$
  SELECT CASE
    WHEN p_amount IS NULL THEN NULL
    WHEN upper(p_currency)=upper(p_target_currency) THEN p_amount
    ELSE p_amount*tokensea_fx_rate(p_timestamp,p_currency,p_target_currency)
  END
$$;
