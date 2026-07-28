-- 模型候选渠道验证记录增强
-- 增加最近一次验证结果，用于区分接口调用成功和渠道真实验证结果

ALTER TABLE model_discovery_candidate
  ADD COLUMN IF NOT EXISTS last_verification_at timestamptz,
  ADD COLUMN IF NOT EXISTS last_verification_result varchar(30),
  ADD COLUMN IF NOT EXISTS last_verification_message varchar(1200),
  ADD COLUMN IF NOT EXISTS verification_attempt_count int NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_model_candidate_verification
  ON model_discovery_candidate(last_verification_at DESC);
