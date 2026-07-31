-- A missing advisory/reference price must not suspend a deployment that was already
-- approved for production and passed its latest live probe. Preserve explicit manual
-- suspensions by limiting this repair to the production-approval decision reason.
UPDATE channel_model_deployment
SET production_status = 'APPROVED',
    routing_status = 'ELIGIBLE',
    updated_at = now()
WHERE production_status = 'SUSPENDED'
  AND routing_status = 'SUSPENDED'
  AND review_status = 'APPROVED'
  AND health_status = 'HEALTHY'
  AND last_probe_status = 'PASSED'
  AND production_decision_reason = '管理员确认模型可进入生产路由';
