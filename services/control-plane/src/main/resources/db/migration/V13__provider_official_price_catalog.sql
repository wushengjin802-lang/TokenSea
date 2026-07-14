-- Database-backed provider official price catalog and automatic deployment pricing.
-- V1-V12 remain immutable. This migration introduces a runtime-managed catalog;
-- YAML/SQL seed files are only bootstrap/import sources, never the runtime source of truth.

CREATE TABLE provider_model_price_catalog (
  id varchar(64) PRIMARY KEY,
  provider_type varchar(80) NOT NULL,
  provider_model_name varchar(200) NOT NULL,
  display_name varchar(300),
  aliases jsonb NOT NULL DEFAULT '[]',
  currency varchar(3) NOT NULL,
  billing_unit varchar(40) NOT NULL DEFAULT 'PER_1K_TOKENS',
  input_amount_per_1k numeric(20,8) NOT NULL,
  output_amount_per_1k numeric(20,8) NOT NULL,
  source_type varchar(50) NOT NULL,
  source_ref varchar(1000) NOT NULL,
  source_confidence numeric(5,4),
  source_updated_at timestamptz,
  effective_from timestamptz NOT NULL,
  effective_to timestamptz,
  revision int NOT NULL DEFAULT 1,
  status varchar(30) NOT NULL DEFAULT 'ACTIVE',
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_provider_price_currency CHECK(currency ~ '^[A-Z]{3}$'),
  CONSTRAINT ck_provider_price_unit CHECK(billing_unit IN ('PER_1K_TOKENS')),
  CONSTRAINT ck_provider_price_amount CHECK(input_amount_per_1k>=0 AND output_amount_per_1k>=0),
  CONSTRAINT ck_provider_price_source CHECK(source_type IN ('OFFICIAL_REFERENCE','PROVIDER_API','MANUAL_VERIFIED')),
  CONSTRAINT ck_provider_price_confidence CHECK(source_confidence IS NULL OR source_confidence BETWEEN 0 AND 1),
  CONSTRAINT ck_provider_price_period CHECK(effective_to IS NULL OR effective_to>effective_from),
  CONSTRAINT ck_provider_price_status CHECK(status IN ('ACTIVE','INACTIVE')),
  UNIQUE(provider_type,provider_model_name,revision)
);
CREATE INDEX idx_provider_price_catalog_lookup
  ON provider_model_price_catalog(lower(provider_type),lower(provider_model_name),status,effective_from DESC);
CREATE INDEX idx_provider_price_catalog_aliases ON provider_model_price_catalog USING gin(aliases);
CREATE UNIQUE INDEX uq_provider_price_catalog_active
  ON provider_model_price_catalog(lower(provider_type),lower(provider_model_name)) WHERE status='ACTIVE';

ALTER TABLE price_version
  ADD COLUMN IF NOT EXISTS catalog_price_id varchar(64),
  ADD COLUMN IF NOT EXISTS auto_generated boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS match_type varchar(30),
  ADD COLUMN IF NOT EXISTS source_updated_at timestamptz;

ALTER TABLE price_version DROP CONSTRAINT IF EXISTS fk_price_version_catalog;
ALTER TABLE price_version ADD CONSTRAINT fk_price_version_catalog
  FOREIGN KEY(catalog_price_id) REFERENCES provider_model_price_catalog(id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE price_version VALIDATE CONSTRAINT fk_price_version_catalog;

ALTER TABLE price_version DROP CONSTRAINT IF EXISTS ck_price_layer;
ALTER TABLE price_version ADD CONSTRAINT ck_price_layer
  CHECK(price_layer IN ('PUBLIC_REFERENCE','PROVIDER_OFFICIAL','CHANNEL_ACTUAL','INTERNAL_ACCOUNTING'));

ALTER TABLE price_version DROP CONSTRAINT IF EXISTS ck_price_layer_owner;
ALTER TABLE price_version ADD CONSTRAINT ck_price_layer_owner CHECK (
 (price_layer='PUBLIC_REFERENCE' AND public_model_reference_id IS NOT NULL AND deployment_id IS NULL AND platform_model_id IS NULL) OR
 (price_layer IN ('PROVIDER_OFFICIAL','CHANNEL_ACTUAL') AND public_model_reference_id IS NULL AND deployment_id IS NOT NULL AND platform_model_id IS NULL) OR
 (price_layer='INTERNAL_ACCOUNTING' AND public_model_reference_id IS NULL AND deployment_id IS NULL AND platform_model_id IS NOT NULL)) NOT VALID;
ALTER TABLE price_version VALIDATE CONSTRAINT ck_price_layer_owner;

ALTER TABLE price_version DROP CONSTRAINT IF EXISTS ck_price_match_type;
ALTER TABLE price_version ADD CONSTRAINT ck_price_match_type
  CHECK(match_type IS NULL OR match_type IN ('EXACT','ALIAS','MANUAL')) NOT VALID;
ALTER TABLE price_version VALIDATE CONSTRAINT ck_price_match_type;

CREATE UNIQUE INDEX uq_price_version_active_provider_official
  ON price_version(deployment_id) WHERE price_layer='PROVIDER_OFFICIAL' AND status='ACTIVE';

-- Preserve reusable official/provider-API prices already configured against deployments.
INSERT INTO provider_model_price_catalog(
  id,provider_type,provider_model_name,display_name,currency,billing_unit,
  input_amount_per_1k,output_amount_per_1k,source_type,source_ref,source_confidence,
  source_updated_at,effective_from,effective_to,revision,status,created_at,updated_at)
SELECT
  'catalog_'||p.id,
  i.provider_type,
  d.provider_model_name,
  d.display_name,
  p.currency,
  'PER_1K_TOKENS',
  p.input_amount_per_1k,
  p.output_amount_per_1k,
  p.source_type,
  p.source_ref,
  p.source_confidence,
  coalesce(p.updated_at,p.created_at),
  p.effective_from,
  p.effective_to,
  1,
  CASE WHEN p.status='ACTIVE' THEN 'ACTIVE' ELSE 'INACTIVE' END,
  p.created_at,
  p.updated_at
FROM price_version p
JOIN channel_model_deployment d ON d.id=p.deployment_id
JOIN provider_instance i ON i.id=d.provider_instance_id
WHERE p.price_layer='CHANNEL_ACTUAL'
  AND p.source_type IN ('OFFICIAL_REFERENCE','PROVIDER_API')
ON CONFLICT DO NOTHING;

UPDATE error_code_registry
SET reason_zh='模型未匹配可用的供应商官方价格',
    retry_advice_zh='同步或维护官方价格目录后重新匹配模型',
    updated_at=now()
WHERE code='TOKENSEA_PRICE_NOT_CONFIGURED';
