-- Phase 2: generic price-document extraction, evidence lineage and record-level review.
-- Existing raw snapshots and price diffs remain authoritative; these tables add traceability.

ALTER TABLE provider_price_source
  ADD COLUMN IF NOT EXISTS document_type varchar(30) NOT NULL DEFAULT 'AUTO',
  ADD COLUMN IF NOT EXISTS extraction_mode varchar(40) NOT NULL DEFAULT 'DETERMINISTIC',
  ADD COLUMN IF NOT EXISTS minimum_confidence numeric(6,5) NOT NULL DEFAULT 0.85000,
  ADD COLUMN IF NOT EXISTS require_manual_review boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS max_document_pages int NOT NULL DEFAULT 200,
  ADD COLUMN IF NOT EXISTS max_document_bytes int NOT NULL DEFAULT 20000000,
  ADD COLUMN IF NOT EXISTS llm_model varchar(160);

ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_document_type;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_document_type CHECK(
  document_type IN ('AUTO','HTML','JSON','CSV','PDF','TEXT','BINARY')
);
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_extraction_mode;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_extraction_mode CHECK(
  extraction_mode IN ('DETERMINISTIC','DETERMINISTIC_LLM','SPECIALIZED')
);
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_minimum_confidence;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_minimum_confidence CHECK(
  minimum_confidence>=0 AND minimum_confidence<=1
);
ALTER TABLE provider_price_source DROP CONSTRAINT IF EXISTS ck_provider_price_document_limits;
ALTER TABLE provider_price_source ADD CONSTRAINT ck_provider_price_document_limits CHECK(
  max_document_pages BETWEEN 1 AND 500 AND max_document_bytes BETWEEN 100000 AND 50000000
);

UPDATE provider_price_source
SET extraction_mode=CASE
      WHEN adapter_code='GENERIC_DOCUMENT'
        AND lower(coalesce(config->>'llmEnabled','false')) IN ('true','1','yes','on')
        THEN 'DETERMINISTIC_LLM'
      WHEN adapter_code='GENERIC_DOCUMENT' THEN 'DETERMINISTIC'
      ELSE 'SPECIALIZED'
    END,
    document_type=CASE
      WHEN adapter_code='OFFICIAL_JSON' THEN 'JSON'
      WHEN adapter_code='OFFICIAL_CSV' THEN 'CSV'
      ELSE 'AUTO'
    END,
    minimum_confidence=CASE
      WHEN coalesce(config->>'minimumConfidence','') ~ '^[0-9]+([.][0-9]+)?$'
        THEN least(greatest((config->>'minimumConfidence')::numeric,0),1)
      ELSE 0.85000
    END,
    require_manual_review=lower(coalesce(config->>'requireManualReview','false')) IN ('true','1','yes','on'),
    max_document_pages=CASE
      WHEN coalesce(config->>'maxPages','') ~ '^[0-9]+$'
        THEN least(greatest((config->>'maxPages')::int,1),500)
      ELSE 200
    END,
    max_document_bytes=CASE
      WHEN coalesce(config->>'maxResponseBytes','') ~ '^[0-9]+$'
        THEN least(greatest((config->>'maxResponseBytes')::int,100000),50000000)
      ELSE 20000000
    END,
    llm_model=nullif(config->>'llmModel','')
WHERE adapter_code IN ('GENERIC_DOCUMENT','OFFICIAL_JSON','OFFICIAL_CSV',
  'DEEPSEEK_OFFICIAL_PAGE','QWEN_OFFICIAL_PAGE','KIMI_OFFICIAL_PAGE',
  'XIAOMI_MIMO_OFFICIAL_PAGE','ZHIPU_OFFICIAL_PAGE');

CREATE TABLE price_document_extraction_run (
  id varchar(64) PRIMARY KEY,
  price_source_id varchar(64) NOT NULL REFERENCES provider_price_source(id) ON DELETE CASCADE,
  sync_run_id varchar(64) REFERENCES provider_price_sync_run(id) ON DELETE CASCADE,
  raw_snapshot_id varchar(64) NOT NULL REFERENCES provider_price_raw_snapshot(id) ON DELETE CASCADE,
  document_type varchar(30) NOT NULL,
  extractor_code varchar(80) NOT NULL,
  extraction_mode varchar(40) NOT NULL,
  schema_version varchar(40) NOT NULL DEFAULT 'price-record-v1',
  llm_model varchar(160),
  llm_request_id varchar(160),
  llm_prompt_hash varchar(64),
  llm_response_hash varchar(64),
  llm_latency_ms int,
  deterministic_record_count int NOT NULL DEFAULT 0,
  llm_record_count int NOT NULL DEFAULT 0,
  accepted_record_count int NOT NULL DEFAULT 0,
  rejected_record_count int NOT NULL DEFAULT 0,
  evidence_complete_count int NOT NULL DEFAULT 0,
  confidence_summary jsonb NOT NULL DEFAULT '{}',
  validation_summary jsonb NOT NULL DEFAULT '{}',
  status varchar(30) NOT NULL DEFAULT 'RUNNING',
  error_message varchar(1000),
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_price_extraction_document_type CHECK(document_type IN ('JSON','CSV','HTML','PDF','TEXT','BINARY')),
  CONSTRAINT ck_price_extraction_mode CHECK(extraction_mode IN ('DETERMINISTIC','DETERMINISTIC_LLM','SPECIALIZED')),
  CONSTRAINT ck_price_extraction_status CHECK(status IN ('RUNNING','SUCCEEDED','REVIEW_REQUIRED','FAILED','REJECTED')),
  CONSTRAINT ck_price_extraction_counts CHECK(
    deterministic_record_count>=0 AND llm_record_count>=0 AND accepted_record_count>=0
    AND rejected_record_count>=0 AND evidence_complete_count>=0
  )
);
CREATE INDEX idx_price_extraction_source ON price_document_extraction_run(price_source_id,created_at DESC);
CREATE INDEX idx_price_extraction_sync ON price_document_extraction_run(sync_run_id,created_at DESC);
CREATE INDEX idx_price_extraction_status ON price_document_extraction_run(status,created_at DESC);

CREATE TABLE price_document_evidence (
  id varchar(64) PRIMARY KEY,
  extraction_run_id varchar(64) NOT NULL REFERENCES price_document_extraction_run(id) ON DELETE CASCADE,
  record_key varchar(500) NOT NULL,
  page_number int,
  table_index int,
  row_index int,
  column_index int,
  source_text text NOT NULL,
  source_hash varchar(64) NOT NULL,
  coordinates jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_price_evidence_page CHECK(page_number IS NULL OR page_number>0),
  CONSTRAINT ck_price_evidence_indexes CHECK(
    (table_index IS NULL OR table_index>=0) AND (row_index IS NULL OR row_index>=0)
    AND (column_index IS NULL OR column_index>=0)
  ),
  CONSTRAINT uq_price_evidence_record UNIQUE(extraction_run_id,record_key,source_hash)
);
CREATE INDEX idx_price_evidence_run ON price_document_evidence(extraction_run_id,record_key);

CREATE TABLE price_document_extracted_record (
  id varchar(64) PRIMARY KEY,
  extraction_run_id varchar(64) NOT NULL REFERENCES price_document_extraction_run(id) ON DELETE CASCADE,
  evidence_id varchar(64) REFERENCES price_document_evidence(id) ON DELETE SET NULL,
  record_key varchar(500) NOT NULL,
  provider_type varchar(80) NOT NULL,
  provider_model_name varchar(240) NOT NULL,
  normalized_record jsonb NOT NULL,
  extraction_method varchar(60) NOT NULL,
  confidence numeric(6,5) NOT NULL DEFAULT 0,
  validation_status varchar(30) NOT NULL DEFAULT 'PENDING',
  validation_result jsonb NOT NULL DEFAULT '{}',
  review_status varchar(30) NOT NULL DEFAULT 'PENDING',
  correction jsonb NOT NULL DEFAULT '{}',
  reviewed_by varchar(64),
  reviewed_at timestamptz,
  review_reason varchar(1000),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_price_extracted_confidence CHECK(confidence>=0 AND confidence<=1),
  CONSTRAINT ck_price_extracted_validation CHECK(validation_status IN ('PENDING','VALID','WARNING','INVALID')),
  CONSTRAINT ck_price_extracted_review CHECK(review_status IN ('PENDING','ACCEPTED','CORRECTED','REJECTED','NON_PRICE')),
  CONSTRAINT uq_price_extracted_record UNIQUE(extraction_run_id,record_key)
);
CREATE INDEX idx_price_extracted_review ON price_document_extracted_record(review_status,created_at DESC);
CREATE INDEX idx_price_extracted_run ON price_document_extracted_record(extraction_run_id,record_key);

ALTER TABLE provider_price_diff
  ADD COLUMN IF NOT EXISTS extraction_run_id varchar(64),
  ADD COLUMN IF NOT EXISTS evidence_id varchar(64);

ALTER TABLE provider_price_diff DROP CONSTRAINT IF EXISTS fk_provider_price_diff_extraction;
ALTER TABLE provider_price_diff ADD CONSTRAINT fk_provider_price_diff_extraction
  FOREIGN KEY(extraction_run_id) REFERENCES price_document_extraction_run(id) ON DELETE SET NULL NOT VALID;
ALTER TABLE provider_price_diff VALIDATE CONSTRAINT fk_provider_price_diff_extraction;

ALTER TABLE provider_price_diff DROP CONSTRAINT IF EXISTS fk_provider_price_diff_evidence;
ALTER TABLE provider_price_diff ADD CONSTRAINT fk_provider_price_diff_evidence
  FOREIGN KEY(evidence_id) REFERENCES price_document_evidence(id) ON DELETE SET NULL NOT VALID;
ALTER TABLE provider_price_diff VALIDATE CONSTRAINT fk_provider_price_diff_evidence;

CREATE INDEX idx_provider_price_diff_extraction ON provider_price_diff(extraction_run_id,evidence_id);
