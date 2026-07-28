-- 请求级成本快照补充缓存价格摘要与命中率，避免查询页面反向解析组件 JSON。

ALTER TABLE usage_cost_snapshot
  ADD COLUMN cache_read_unit_price numeric(30,12),
  ADD COLUMN cache_read_mode varchar(30) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN cache_write_unit_price numeric(30,12),
  ADD COLUMN cache_write_mode varchar(30) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN cache_hit_rate numeric(18,12) NOT NULL DEFAULT 0;

ALTER TABLE usage_cost_snapshot
  ADD CONSTRAINT ck_usage_snapshot_cache_read_amount
    CHECK(cache_read_unit_price IS NULL OR cache_read_unit_price >= 0),
  ADD CONSTRAINT ck_usage_snapshot_cache_write_amount
    CHECK(cache_write_unit_price IS NULL OR cache_write_unit_price >= 0),
  ADD CONSTRAINT ck_usage_snapshot_cache_read_mode
    CHECK(cache_read_mode IN ('EXPLICIT','EXPLICIT_ZERO','INHERIT_INPUT','NOT_APPLICABLE','UNKNOWN')),
  ADD CONSTRAINT ck_usage_snapshot_cache_write_mode
    CHECK(cache_write_mode IN ('EXPLICIT','EXPLICIT_ZERO','INHERIT_INPUT','NOT_APPLICABLE','UNKNOWN')),
  ADD CONSTRAINT ck_usage_snapshot_cache_hit_rate
    CHECK(cache_hit_rate >= 0 AND cache_hit_rate <= 1);

CREATE INDEX idx_usage_cost_snapshot_cache_rate
  ON usage_cost_snapshot(created_at,cache_hit_rate,cost_status);
