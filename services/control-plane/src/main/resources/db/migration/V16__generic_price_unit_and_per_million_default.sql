-- TokenSea 研发阶段价格模型一次性升级：
-- 1. 页面与默认数据统一使用每百万 Token；
-- 2. 底层使用 billing_basis + billing_quantity + unit_price 的通用计费模型；
-- 3. 研发测试数据不保留，避免历史每千 Token 数据与新语义混用。

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

ALTER TABLE price_version DROP CONSTRAINT IF EXISTS ck_price_amount;
ALTER TABLE price_version RENAME COLUMN input_amount_per_1k TO input_unit_price;
ALTER TABLE price_version RENAME COLUMN output_amount_per_1k TO output_unit_price;
ALTER TABLE price_version ADD COLUMN billing_basis varchar(40) NOT NULL DEFAULT 'TOKEN';
ALTER TABLE price_version ADD COLUMN billing_quantity bigint NOT NULL DEFAULT 1000000;
ALTER TABLE price_version ADD CONSTRAINT ck_price_unit_amount
  CHECK(input_unit_price >= 0 AND output_unit_price >= 0 AND billing_quantity > 0);
ALTER TABLE price_version ADD CONSTRAINT ck_price_billing_basis
  CHECK(billing_basis IN ('TOKEN','REQUEST','IMAGE','SECOND','MINUTE','CHARACTER','AUDIO_MINUTE'));

ALTER TABLE provider_model_price_catalog DROP CONSTRAINT IF EXISTS ck_provider_price_unit;
ALTER TABLE provider_model_price_catalog DROP CONSTRAINT IF EXISTS ck_provider_price_amount;
ALTER TABLE provider_model_price_catalog RENAME COLUMN billing_unit TO billing_basis;
ALTER TABLE provider_model_price_catalog RENAME COLUMN input_amount_per_1k TO input_unit_price;
ALTER TABLE provider_model_price_catalog RENAME COLUMN output_amount_per_1k TO output_unit_price;
ALTER TABLE provider_model_price_catalog ALTER COLUMN billing_basis SET DEFAULT 'TOKEN';
ALTER TABLE provider_model_price_catalog ADD COLUMN billing_quantity bigint NOT NULL DEFAULT 1000000;
ALTER TABLE provider_model_price_catalog ADD CONSTRAINT ck_provider_price_unit_amount
  CHECK(input_unit_price >= 0 AND output_unit_price >= 0 AND billing_quantity > 0);
ALTER TABLE provider_model_price_catalog ADD CONSTRAINT ck_provider_price_billing_basis
  CHECK(billing_basis IN ('TOKEN','REQUEST','IMAGE','SECOND','MINUTE','CHARACTER','AUDIO_MINUTE'));

ALTER TABLE public_model_price_reference DROP CONSTRAINT IF EXISTS ck_public_price_reference_amount;
ALTER TABLE public_model_price_reference RENAME COLUMN input_amount_per_1k TO input_unit_price;
ALTER TABLE public_model_price_reference RENAME COLUMN output_amount_per_1k TO output_unit_price;
ALTER TABLE public_model_price_reference ADD COLUMN billing_basis varchar(40) NOT NULL DEFAULT 'TOKEN';
ALTER TABLE public_model_price_reference ADD COLUMN billing_quantity bigint NOT NULL DEFAULT 1000000;
ALTER TABLE public_model_price_reference ADD CONSTRAINT ck_public_price_reference_unit_amount
  CHECK(input_unit_price >= 0 AND output_unit_price >= 0 AND billing_quantity > 0);
ALTER TABLE public_model_price_reference ADD CONSTRAINT ck_public_price_reference_billing_basis
  CHECK(billing_basis IN ('TOKEN','REQUEST','IMAGE','SECOND','MINUTE','CHARACTER','AUDIO_MINUTE'));

ALTER TABLE usage_cost_snapshot RENAME COLUMN input_amount_per_1k TO input_unit_price;
ALTER TABLE usage_cost_snapshot RENAME COLUMN output_amount_per_1k TO output_unit_price;
ALTER TABLE usage_cost_snapshot ADD COLUMN billing_basis varchar(40) NOT NULL DEFAULT 'TOKEN';
ALTER TABLE usage_cost_snapshot ADD COLUMN billing_quantity bigint NOT NULL DEFAULT 1000000;
ALTER TABLE usage_cost_snapshot ADD CONSTRAINT ck_usage_cost_snapshot_unit_amount
  CHECK(input_unit_price >= 0 AND output_unit_price >= 0 AND billing_quantity > 0);
ALTER TABLE usage_cost_snapshot ADD CONSTRAINT ck_usage_cost_snapshot_billing_basis
  CHECK(billing_basis IN ('TOKEN','REQUEST','IMAGE','SECOND','MINUTE','CHARACTER','AUDIO_MINUTE'));

ALTER TABLE provider_price_component ADD COLUMN unit_quantity bigint NOT NULL DEFAULT 1000000;
ALTER TABLE provider_price_component ADD CONSTRAINT ck_provider_price_component_quantity
  CHECK(unit_quantity > 0);
ALTER TABLE provider_price_component ADD CONSTRAINT ck_provider_price_component_basis
  CHECK(unit_basis IN ('TOKEN','REQUEST','IMAGE','SECOND','MINUTE','CHARACTER','AUDIO_MINUTE'));

ALTER TABLE model_price DROP CONSTRAINT IF EXISTS ck_model_price_nonnegative;
ALTER TABLE model_price RENAME COLUMN input_cost_per_1k TO input_cost_unit_price;
ALTER TABLE model_price RENAME COLUMN output_cost_per_1k TO output_cost_unit_price;
ALTER TABLE model_price RENAME COLUMN input_price_per_1k TO input_price_unit_price;
ALTER TABLE model_price RENAME COLUMN output_price_per_1k TO output_price_unit_price;
ALTER TABLE model_price ADD COLUMN billing_basis varchar(40) NOT NULL DEFAULT 'TOKEN';
ALTER TABLE model_price ADD COLUMN billing_quantity bigint NOT NULL DEFAULT 1000000;
ALTER TABLE model_price ADD CONSTRAINT ck_model_price_unit_amount
  CHECK(input_cost_unit_price >= 0 AND output_cost_unit_price >= 0
    AND input_price_unit_price >= 0 AND output_price_unit_price >= 0
    AND billing_quantity > 0);
ALTER TABLE model_price ADD CONSTRAINT ck_model_price_billing_basis
  CHECK(billing_basis IN ('TOKEN','REQUEST','IMAGE','SECOND','MINUTE','CHARACTER','AUDIO_MINUTE'));
