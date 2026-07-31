-- Public reference prices are advisory only. A healthy model can enter production review
-- even when the automatic reference library has no exact provider/model match.
UPDATE channel_model_deployment
SET production_status='READY_FOR_REVIEW',
    review_status='PENDING_REVIEW',
    routing_status='INELIGIBLE',
    updated_at=now()
WHERE health_status='HEALTHY'
  AND production_status='CANDIDATE'
  AND discovery_status<>'MISSING_CONFIRMED'
  AND (
    SELECT v.status
    FROM capability_validation v
    WHERE v.deployment_id=channel_model_deployment.id
      AND v.test_type='LIVE_PROBE'
    ORDER BY v.validated_at DESC
    LIMIT 1
  )='PASSED';
