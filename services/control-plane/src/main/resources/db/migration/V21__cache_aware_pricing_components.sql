-- TokenSea 缓存感知计价体系升级。
-- 研发测试版本按一次性升级处理：清理现有测试价格与成本快照，统一使用组件数组 V2。

UPDATE route_policy
SET status = 'DRAFT',
    config = '{}',
    updated_at = now()
WHERE status <> 'RETIRED';

UPDATE platform_model
SET route_policy_id = NULL,
    route_policy = '{}',
    price_policy_id = NULL,
    price_policy = '{}',
    status = '草稿',
    updated_at = now();

DELETE FROM provider_price_component;
DELETE FROM usage_cost_snapshot;
DELETE FROM provider_price_diff;
DELETE FROM price_version;
DELETE FROM provider_model_price_catalog;
DELETE FROM public_model_price_reference;
DELETE FROM provider_price_raw_snapshot;
DELETE FROM provider_price_sync_run;
DELETE FROM model_price;
DELETE FROM approval_request WHERE resource_type IN ('MODEL_PRICE','PRICE_VERSION');
DELETE FROM governance_version WHERE resource_type IN ('MODEL_PRICE','PRICE_VERSION');

UPDATE request_attempt
SET cost_snapshot = '{}',
    actual_cost_amount = 0
WHERE cost_snapshot <> '{}'::jsonb OR actual_cost_amount <> 0;

UPDATE public_model_reference
SET reference_prices = '{}',
    reference_source_hash = NULL,
    reference_updated_at = NULL,
    updated_at = now()
WHERE reference_prices <> '{}'::jsonb;

UPDATE provider_price_source
SET etag = NULL,
    last_modified = NULL,
    last_success_at = NULL,
    last_failure_at = NULL,
    last_error = NULL,
    updated_at = now();

-- 目录摘要字段：输入价格明确表示缓存未命中输入价。
ALTER TABLE provider_model_price_catalog
  ADD COLUMN cache_read_unit_price numeric(30,12),
  ADD COLUMN cache_write_unit_price numeric(30,12),
  ADD COLUMN cache_read_mode varchar(30) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN cache_write_mode varchar(30) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN price_components jsonb NOT NULL DEFAULT '[]',
  ADD COLUMN component_schema_version int NOT NULL DEFAULT 2,
  ADD COLUMN price_completeness_status varchar(40) NOT NULL DEFAULT 'PARTIAL',
  ADD COLUMN cache_pricing_status varchar(40) NOT NULL DEFAULT 'UNKNOWN_CACHE_PRICE';

ALTER TABLE provider_model_price_catalog
  ADD CONSTRAINT ck_provider_catalog_cache_amount
    CHECK(cache_read_unit_price IS NULL OR cache_read_unit_price >= 0),
  ADD CONSTRAINT ck_provider_catalog_cache_write_amount
    CHECK(cache_write_unit_price IS NULL OR cache_write_unit_price >= 0),
  ADD CONSTRAINT ck_provider_catalog_cache_read_mode
    CHECK(cache_read_mode IN ('EXPLICIT','EXPLICIT_ZERO','INHERIT_INPUT','NOT_APPLICABLE','UNKNOWN')),
  ADD CONSTRAINT ck_provider_catalog_cache_write_mode
    CHECK(cache_write_mode IN ('EXPLICIT','EXPLICIT_ZERO','INHERIT_INPUT','NOT_APPLICABLE','UNKNOWN')),
  ADD CONSTRAINT ck_provider_catalog_component_array
    CHECK(jsonb_typeof(price_components) = 'array'),
  ADD CONSTRAINT ck_provider_catalog_component_schema
    CHECK(component_schema_version >= 2),
  ADD CONSTRAINT ck_provider_catalog_completeness
    CHECK(price_completeness_status IN ('COMPLETE','PARTIAL','UNKNOWN_CACHE_PRICE','UNSUPPORTED_CACHE')),
  ADD CONSTRAINT ck_provider_catalog_cache_status
    CHECK(cache_pricing_status IN ('COMPLETE','PARTIAL','UNKNOWN_CACHE_PRICE','UNSUPPORTED_CACHE'));

-- 生效价格版本固化完整组件，运行时不再回查当前目录。
ALTER TABLE price_version
  ADD COLUMN cache_read_unit_price numeric(30,12),
  ADD COLUMN cache_write_unit_price numeric(30,12),
  ADD COLUMN cache_read_mode varchar(30) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN cache_write_mode varchar(30) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN component_schema_version int NOT NULL DEFAULT 2,
  ADD COLUMN price_completeness_status varchar(40) NOT NULL DEFAULT 'PARTIAL';

ALTER TABLE price_version ALTER COLUMN price_components SET DEFAULT '[]';
ALTER TABLE price_version
  ADD CONSTRAINT ck_price_version_cache_amount
    CHECK(cache_read_unit_price IS NULL OR cache_read_unit_price >= 0),
  ADD CONSTRAINT ck_price_version_cache_write_amount
    CHECK(cache_write_unit_price IS NULL OR cache_write_unit_price >= 0),
  ADD CONSTRAINT ck_price_version_cache_read_mode
    CHECK(cache_read_mode IN ('EXPLICIT','EXPLICIT_ZERO','INHERIT_INPUT','NOT_APPLICABLE','UNKNOWN')),
  ADD CONSTRAINT ck_price_version_cache_write_mode
    CHECK(cache_write_mode IN ('EXPLICIT','EXPLICIT_ZERO','INHERIT_INPUT','NOT_APPLICABLE','UNKNOWN')),
  ADD CONSTRAINT ck_price_version_component_array
    CHECK(jsonb_typeof(price_components) = 'array'),
  ADD CONSTRAINT ck_price_version_component_schema
    CHECK(component_schema_version >= 2),
  ADD CONSTRAINT ck_price_version_completeness
    CHECK(price_completeness_status IN ('COMPLETE','PARTIAL','UNKNOWN_CACHE_PRICE','UNSUPPORTED_CACHE'));

-- 公共参考同步也保留缓存摘要，便于对比但不直接覆盖生产价格。
ALTER TABLE public_model_price_reference
  ADD COLUMN cache_read_unit_price numeric(30,12),
  ADD COLUMN cache_write_unit_price numeric(30,12),
  ADD COLUMN cache_read_mode varchar(30) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN cache_write_mode varchar(30) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN component_schema_version int NOT NULL DEFAULT 2,
  ADD COLUMN price_completeness_status varchar(40) NOT NULL DEFAULT 'PARTIAL';

ALTER TABLE public_model_price_reference ALTER COLUMN price_components SET DEFAULT '[]';
ALTER TABLE public_model_price_reference
  ADD CONSTRAINT ck_public_reference_cache_amount
    CHECK(cache_read_unit_price IS NULL OR cache_read_unit_price >= 0),
  ADD CONSTRAINT ck_public_reference_cache_write_amount
    CHECK(cache_write_unit_price IS NULL OR cache_write_unit_price >= 0),
  ADD CONSTRAINT ck_public_reference_cache_read_mode
    CHECK(cache_read_mode IN ('EXPLICIT','EXPLICIT_ZERO','INHERIT_INPUT','NOT_APPLICABLE','UNKNOWN')),
  ADD CONSTRAINT ck_public_reference_cache_write_mode
    CHECK(cache_write_mode IN ('EXPLICIT','EXPLICIT_ZERO','INHERIT_INPUT','NOT_APPLICABLE','UNKNOWN')),
  ADD CONSTRAINT ck_public_reference_component_array
    CHECK(jsonb_typeof(price_components) = 'array'),
  ADD CONSTRAINT ck_public_reference_completeness
    CHECK(price_completeness_status IN ('COMPLETE','PARTIAL','UNKNOWN_CACHE_PRICE','UNSUPPORTED_CACHE'));

-- 价格组件支持同类型多变体、组件模式和作用域优先级。
-- UNKNOWN / NOT_APPLICABLE 组件没有单价，因此取消旧字段的 NOT NULL。
ALTER TABLE provider_price_component ALTER COLUMN unit_price DROP NOT NULL;
ALTER TABLE provider_price_component
  ADD COLUMN variant varchar(80) NOT NULL DEFAULT 'DEFAULT',
  ADD COLUMN component_mode varchar(30) NOT NULL DEFAULT 'EXPLICIT',
  ADD COLUMN priority int NOT NULL DEFAULT 100,
  ADD COLUMN source_ref varchar(1200),
  ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}';

ALTER TABLE provider_price_component
  ADD CONSTRAINT ck_provider_component_mode
    CHECK(component_mode IN ('EXPLICIT','EXPLICIT_ZERO','INHERIT_INPUT','NOT_APPLICABLE','UNKNOWN')),
  ADD CONSTRAINT ck_provider_component_price_mode CHECK(
    (component_mode = 'EXPLICIT' AND unit_price IS NOT NULL AND unit_price >= 0)
    OR (component_mode = 'EXPLICIT_ZERO' AND unit_price = 0)
    OR (component_mode = 'INHERIT_INPUT' AND unit_price IS NOT NULL AND unit_price >= 0)
    OR (component_mode IN ('NOT_APPLICABLE','UNKNOWN') AND unit_price IS NULL)
  ),
  ADD CONSTRAINT ck_provider_component_priority CHECK(priority >= 0),
  ADD CONSTRAINT ck_provider_component_metadata CHECK(jsonb_typeof(metadata) = 'object');

DO $$
DECLARE constraint_name text;
BEGIN
  FOR constraint_name IN
    SELECT conname
    FROM pg_constraint
    WHERE conrelid = 'provider_price_component'::regclass
      AND contype = 'u'
  LOOP
    EXECUTE format('ALTER TABLE provider_price_component DROP CONSTRAINT %I', constraint_name);
  END LOOP;
END $$;

CREATE UNIQUE INDEX uq_provider_price_component_variant_scope
  ON provider_price_component(catalog_price_id,component_type,variant,scope_hash);
CREATE INDEX idx_provider_price_component_match
  ON provider_price_component(catalog_price_id,component_type,priority,variant);

-- 请求级不可变成本证据。
ALTER TABLE usage_cost_snapshot
  ADD COLUMN input_uncached_tokens int NOT NULL DEFAULT 0,
  ADD COLUMN input_tokens_total int NOT NULL DEFAULT 0,
  ADD COLUMN output_tokens int NOT NULL DEFAULT 0,
  ADD COLUMN cache_storage_token_seconds numeric(30,6) NOT NULL DEFAULT 0,
  ADD COLUMN usage_schema_version int NOT NULL DEFAULT 2,
  ADD COLUMN usage_source varchar(80) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN usage_evidence jsonb NOT NULL DEFAULT '{}',
  ADD COLUMN cache_gross_savings numeric(30,12) NOT NULL DEFAULT 0,
  ADD COLUMN cache_write_premium numeric(30,12) NOT NULL DEFAULT 0,
  ADD COLUMN cache_storage_cost numeric(30,12) NOT NULL DEFAULT 0,
  ADD COLUMN cache_net_savings numeric(30,12) NOT NULL DEFAULT 0,
  ADD COLUMN cost_status varchar(40) NOT NULL DEFAULT 'COMPLETE';

ALTER TABLE usage_cost_snapshot ALTER COLUMN price_components SET DEFAULT '[]';
ALTER TABLE usage_cost_snapshot ALTER COLUMN cost_components SET DEFAULT '[]';
ALTER TABLE usage_cost_snapshot
  ADD CONSTRAINT ck_usage_snapshot_component_array CHECK(jsonb_typeof(price_components) = 'array'),
  ADD CONSTRAINT ck_usage_snapshot_cost_component_array CHECK(jsonb_typeof(cost_components) = 'array'),
  ADD CONSTRAINT ck_usage_snapshot_token_counts CHECK(
    input_uncached_tokens >= 0 AND input_tokens_total >= 0 AND cache_read_tokens >= 0
    AND cache_write_tokens >= 0 AND output_tokens >= 0 AND reasoning_tokens >= 0
    AND cache_storage_token_seconds >= 0
  ),
  ADD CONSTRAINT ck_usage_snapshot_schema CHECK(usage_schema_version >= 2),
  ADD CONSTRAINT ck_usage_snapshot_cost_status CHECK(
    cost_status IN ('COMPLETE','INCOMPLETE_USAGE','INCOMPLETE_PRICE','AMBIGUOUS_COMPONENT','INVALID_USAGE')
  );

CREATE INDEX idx_usage_cost_snapshot_cache
  ON usage_cost_snapshot(created_at,cache_read_tokens,cache_write_tokens,cost_status);
