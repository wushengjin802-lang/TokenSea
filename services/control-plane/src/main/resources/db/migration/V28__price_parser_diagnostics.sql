ALTER TABLE provider_price_sync_run
  ADD COLUMN IF NOT EXISTS parse_status varchar(50),
  ADD COLUMN IF NOT EXISTS parsed_table_count int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS matched_table_count int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS generated_price_count int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS diagnostic_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN provider_price_sync_run.parse_status IS '价格解析状态，例如 PRICE_PARSED、PRICE_TABLE_NOT_MATCHED、NO_PRICE_TABLE';
COMMENT ON COLUMN provider_price_sync_run.parsed_table_count IS '解析页面中发现的 HTML table 总数';
COMMENT ON COLUMN provider_price_sync_run.matched_table_count IS '匹配到供应商价格结构的 table 数量';
COMMENT ON COLUMN provider_price_sync_run.generated_price_count IS '本次解析生成的标准化价格记录数量';
COMMENT ON COLUMN provider_price_sync_run.diagnostic_snapshot IS '价格解析器输出的结构化诊断快照';
