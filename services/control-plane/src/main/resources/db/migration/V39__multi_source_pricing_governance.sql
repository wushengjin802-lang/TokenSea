-- Phase 1 multi-source pricing governance: connector metadata, controlled SKU mappings,
-- unmapped-record evidence, separated billing credentials and immutable billing snapshots.
-- Existing official-page adapters, price diffs, published prices and usage records are preserved.

-- 1. Make the acquisition method and governance policy explicit without replacing adapter_code.
ALTER TABLE provider_price_source
  ADD COLUMN IF NOT EXISTS connector_code varchar(60),
  ADD COLUMN IF NOT EXISTS data_scope varchar(30) NOT NULL DEFAULT 'DOCUMENT',
  ADD COLUMN IF NOT EXISTS trust_level varchar(40) NOT NULL DEFAULT 'OFFICIAL_PUBLIC',
  ADD COLUMN IF NOT EXISTS publish_policy varchar(30) NOT NULL DEFAULT 'MANUAL_ONLY',
  ADD COLUMN IF NOT EXISTS schema_version varchar(40) NOT NULL DEFAULT 'price-record-v1',
  ADD COLUMN IF NOT EXISTS credential_ref varchar(64),
  ADD COLUMN IF NOT EXISTS credential_purpose varchar(30) NOT NULL DEFAULT 'NONE',
  ADD COLUMN IF NOT EXISTS mapping_profile varchar(100);

UPDATE provider_price_source
SET connector_code = CASE
      WHEN adapter_code IN ('AZURE_RETAIL_PRICES','AWS_PRICE_LIST_BULK','GOOGLE_CLOUD_CATALOG',
                            'LITELLM_COST_MAP','MODELS_DEV') THEN adapter_code
      ELSE 'HTTP_DOCUMENT'
    END,
    data_scope = CASE
      WHEN adapter_code IN ('LITELLM_COST_MAP','MODELS_DEV') THEN 'REFERENCE_DATASET'
      WHEN adapter_code IN ('AZURE_RETAIL_PRICES','AWS_PRICE_LIST_BULK','GOOGLE_CLOUD_CATALOG') THEN 'PUBLIC_CATALOG'
      ELSE 'DOCUMENT'
    END,
    trust_level = CASE
      WHEN adapter_code IN ('LITELLM_COST_MAP','MODELS_DEV') THEN 'COMMUNITY_REFERENCE'
      ELSE 'OFFICIAL_PUBLIC'
    END,
    publish_policy = CASE
      WHEN source_class='PUBLIC_REFERENCE' THEN 'MANUAL_ONLY'
      WHEN auto_publish THEN 'AUTO_LOW_RISK'
      ELSE 'MANUAL_ONLY'
    END,
    credential_purpose = CASE WHEN auth_mode='PROVIDER_INSTANCE' THEN 'PRICING_READ' ELSE 'NONE' END,
    mapping_profile = coalesce(mapping_profile, nullif(config->>'mappingProfile','')),
    updated_at = now()
WHERE connector_code IS NULL;

ALTER TABLE provider_price_source ALTER COLUMN connector_code SET NOT NULL;
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_connector;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_connector CHECK(
  connector_code IN (
    'HTTP_DOCUMENT','AZURE_RETAIL_PRICES','AWS_PRICE_LIST_BULK','GOOGLE_CLOUD_CATALOG',
    'LITELLM_COST_MAP','MODELS_DEV'
  )
);
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_data_scope;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_data_scope CHECK(
  data_scope IN ('PUBLIC_CATALOG','ACCOUNT_PRICING','REFERENCE_DATASET','DOCUMENT')
);
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_trust_level;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_trust_level CHECK(
  trust_level IN ('OFFICIAL_PUBLIC','OFFICIAL_ACCOUNT','COMMUNITY_REFERENCE')
);
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_publish_policy;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_publish_policy CHECK(
  publish_policy IN ('AUTO_LOW_RISK','MANUAL_ONLY')
);
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS fk_provider_price_credential;
ALTER TABLE provider_price_source ADD CONSTRAINT fk_provider_price_credential
  FOREIGN KEY(credential_ref) REFERENCES provider_secret(id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE provider_price_source VALIDATE CONSTRAINT fk_provider_price_credential;
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_credential_purpose;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_credential_purpose CHECK(
  credential_purpose IN ('NONE','PRICING_READ')
);
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_connector_governance;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_connector_governance CHECK(
  (source_class='PUBLIC_REFERENCE'
    AND data_scope='REFERENCE_DATASET'
    AND trust_level='COMMUNITY_REFERENCE'
    AND publish_policy='MANUAL_ONLY'
    AND credential_purpose='NONE')
  OR
  (source_class='OFFICIAL'
    AND data_scope IN ('PUBLIC_CATALOG','ACCOUNT_PRICING','DOCUMENT')
    AND trust_level IN ('OFFICIAL_PUBLIC','OFFICIAL_ACCOUNT'))
);
UPDATE provider_price_source
SET status='PAUSED',next_run_at=NULL,
    last_error='需要配置独立的 PRICING_READ 凭据后才能启用价格同步',updated_at=now()
WHERE auth_mode='PROVIDER_INSTANCE' AND credential_ref IS NULL AND status='ACTIVE';

ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_credential_binding;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_credential_binding CHECK(
  (auth_mode='NONE' AND credential_ref IS NULL AND credential_purpose='NONE')
  OR
  (auth_mode='PROVIDER_INSTANCE' AND credential_purpose='PRICING_READ'
    AND (status<>'ACTIVE' OR credential_ref IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_provider_price_source_connector
  ON provider_price_source(connector_code,data_scope,status);
CREATE INDEX IF NOT EXISTS idx_provider_price_source_credential
  ON provider_price_source(credential_ref,status);

-- 2. Keep inference, pricing and billing credentials separate.
ALTER TABLE provider_secret
  ADD COLUMN IF NOT EXISTS secret_purpose varchar(30) NOT NULL DEFAULT 'INFERENCE';
ALTER TABLE provider_secret DROP CONSTRAINT IF EXISTS ck_provider_secret_purpose;
ALTER TABLE provider_secret ADD CONSTRAINT ck_provider_secret_purpose CHECK(
  secret_purpose IN ('INFERENCE','PRICING_READ','BILLING_READ')
);
CREATE INDEX IF NOT EXISTS idx_provider_secret_instance_purpose
  ON provider_secret(provider_instance_id,secret_purpose,status,created_at DESC);

ALTER TABLE provider_billing_source
  ADD COLUMN IF NOT EXISTS credential_ref varchar(64),
  ADD COLUMN IF NOT EXISTS credential_purpose varchar(30) NOT NULL DEFAULT 'BILLING_READ',
  ADD COLUMN IF NOT EXISTS schema_version varchar(40) NOT NULL DEFAULT 'provider-cost-record-v1',
  ADD COLUMN IF NOT EXISTS variance_alert_ratio numeric(8,4) NOT NULL DEFAULT 0.0500,
  ADD COLUMN IF NOT EXISTS observation_mode boolean NOT NULL DEFAULT true;

ALTER TABLE provider_billing_source DROP CONSTRAINT IF EXISTS fk_provider_billing_credential;
ALTER TABLE provider_billing_source ADD CONSTRAINT fk_provider_billing_credential
  FOREIGN KEY(credential_ref) REFERENCES provider_secret(id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE provider_billing_source VALIDATE CONSTRAINT fk_provider_billing_credential;
ALTER TABLE provider_billing_source DROP CONSTRAINT IF EXISTS ck_provider_billing_credential_purpose;
ALTER TABLE provider_billing_source ADD CONSTRAINT ck_provider_billing_credential_purpose
  CHECK(credential_purpose='BILLING_READ');
ALTER TABLE provider_billing_source DROP CONSTRAINT IF EXISTS ck_provider_billing_variance_ratio;
ALTER TABLE provider_billing_source ADD CONSTRAINT ck_provider_billing_variance_ratio
  CHECK(variance_alert_ratio>=0 AND variance_alert_ratio<=10);
CREATE INDEX IF NOT EXISTS idx_provider_billing_source_credential
  ON provider_billing_source(credential_ref,status);

-- Existing sources must explicitly select a dedicated BILLING_READ secret before resuming.
UPDATE provider_billing_source
SET status='PAUSED', next_run_at=NULL,
    last_error='需要配置独立的 BILLING_READ 凭据后才能启用账单同步',
    updated_at=now()
WHERE credential_ref IS NULL AND status='ACTIVE';

-- 3. External SKU/model mapping rules. Java adapters consume these rules before regex fallback.
CREATE TABLE price_source_mapping_rule (
  id varchar(64) PRIMARY KEY,
  price_source_id varchar(64) NOT NULL REFERENCES provider_price_source(id) ON DELETE CASCADE,
  mapping_profile varchar(100) NOT NULL DEFAULT 'DEFAULT',
  rule_name varchar(180) NOT NULL,
  external_service_pattern varchar(500),
  external_product_pattern varchar(500),
  external_sku_pattern varchar(500),
  external_meter_pattern varchar(500),
  external_model_pattern varchar(500),
  target_provider_type varchar(80),
  target_model_name varchar(240) NOT NULL,
  target_component_type varchar(60) NOT NULL,
  target_request_mode varchar(40) NOT NULL DEFAULT 'STANDARD',
  target_service_tier varchar(80) NOT NULL DEFAULT 'DEFAULT',
  target_context_tier varchar(80) NOT NULL DEFAULT 'DEFAULT',
  target_region varchar(80),
  billing_basis varchar(60) NOT NULL DEFAULT 'TOKEN',
  billing_quantity bigint NOT NULL DEFAULT 1000000,
  transform_config jsonb NOT NULL DEFAULT '{}',
  priority int NOT NULL DEFAULT 100,
  status varchar(30) NOT NULL DEFAULT 'ACTIVE',
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_price_mapping_component CHECK(target_component_type IN(
    'INPUT_TOKEN','OUTPUT_TOKEN','CACHE_READ_TOKEN','CACHE_WRITE_TOKEN','REASONING_TOKEN',
    'IMAGE_INPUT','IMAGE_OUTPUT','AUDIO_SECOND','VIDEO_SECOND','REQUEST'
  )),
  CONSTRAINT ck_price_mapping_basis CHECK(billing_basis IN(
    'TOKEN','REQUEST','IMAGE','SECOND','MINUTE','CHARACTER','AUDIO_MINUTE','TOKEN_SECOND'
  )),
  CONSTRAINT ck_price_mapping_quantity CHECK(billing_quantity>0),
  CONSTRAINT ck_price_mapping_priority CHECK(priority BETWEEN 1 AND 10000),
  CONSTRAINT ck_price_mapping_status CHECK(status IN ('ACTIVE','PAUSED','DISABLED'))
);
CREATE INDEX idx_price_mapping_source
  ON price_source_mapping_rule(price_source_id,status,priority,id);
CREATE UNIQUE INDEX uq_price_mapping_source_name
  ON price_source_mapping_rule(price_source_id,lower(rule_name));

-- 4. Preserve records that could not be mapped instead of silently dropping them.
CREATE TABLE price_source_unmapped_record (
  id varchar(64) PRIMARY KEY,
  price_source_id varchar(64) NOT NULL REFERENCES provider_price_source(id) ON DELETE CASCADE,
  sync_run_id varchar(64) NOT NULL REFERENCES provider_price_sync_run(id) ON DELETE CASCADE,
  raw_snapshot_id varchar(64) NOT NULL REFERENCES provider_price_raw_snapshot(id) ON DELETE RESTRICT,
  external_record_id varchar(300),
  external_service varchar(500),
  external_product varchar(500),
  external_sku varchar(500),
  external_meter varchar(500),
  external_model varchar(500),
  external_region varchar(160),
  external_currency varchar(3),
  external_unit varchar(160),
  external_price numeric(30,12),
  reason_code varchar(80) NOT NULL,
  reason_message varchar(1000),
  evidence_hash varchar(64) NOT NULL,
  raw_payload jsonb NOT NULL DEFAULT '{}',
  status varchar(30) NOT NULL DEFAULT 'OPEN',
  occurrence_count int NOT NULL DEFAULT 1,
  first_seen_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  resolved_by_mapping_id varchar(64) REFERENCES price_source_mapping_rule(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_price_unmapped_status CHECK(status IN ('OPEN','MAPPED','IGNORED')),
  CONSTRAINT ck_price_unmapped_occurrence CHECK(occurrence_count>0),
  UNIQUE(price_source_id,evidence_hash)
);
CREATE INDEX idx_price_unmapped_source_status
  ON price_source_unmapped_record(price_source_id,status,last_seen_at DESC);

-- 5. Store immutable provider billing responses independently from normalized billing records.
CREATE TABLE provider_billing_raw_snapshot (
  id varchar(64) PRIMARY KEY,
  billing_source_id varchar(64) NOT NULL REFERENCES provider_billing_source(id) ON DELETE RESTRICT,
  sync_run_id varchar(64) NOT NULL REFERENCES provider_billing_sync_run(id) ON DELETE CASCADE,
  source_endpoint varchar(1200) NOT NULL,
  final_endpoint varchar(1200) NOT NULL,
  http_status int NOT NULL,
  content_type varchar(200),
  checksum varchar(64) NOT NULL,
  response_bytes int NOT NULL,
  page_count int NOT NULL DEFAULT 1,
  raw_content text NOT NULL,
  fetched_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_provider_billing_snapshot_bytes CHECK(response_bytes>=0),
  CONSTRAINT ck_provider_billing_snapshot_pages CHECK(page_count>0),
  UNIQUE(sync_run_id)
);
CREATE INDEX idx_provider_billing_snapshot_source
  ON provider_billing_raw_snapshot(billing_source_id,fetched_at DESC);
