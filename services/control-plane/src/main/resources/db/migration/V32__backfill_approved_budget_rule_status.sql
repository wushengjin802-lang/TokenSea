-- 将已完成批准但历史状态未同步的预算规则恢复为可生效状态。

UPDATE budget_rule b
SET approval_status = 'APPROVED', updated_at = now()
WHERE b.approval_status = 'PENDING_APPROVAL'
  AND EXISTS (
    SELECT 1
    FROM approval_request a
    WHERE a.resource_type = 'BUDGET_RULE'
      AND a.resource_id = b.id
      AND a.status = 'APPROVED'
  );
