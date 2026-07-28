-- 支持管理员撤销误发布的供应商官方价格差异。
-- 撤销仅改变治理状态；业务代码负责停用目录、退役派生价格版本并重新计算部署价格状态。

ALTER TABLE provider_price_diff
  DROP CONSTRAINT IF EXISTS ck_provider_price_diff_status;

ALTER TABLE provider_price_diff
  ADD CONSTRAINT ck_provider_price_diff_status
  CHECK(status IN ('PENDING','AUTO_PUBLISHED','APPROVED','REJECTED','IGNORED','REVOKED'));
