-- Normalize provider template enable status.
-- Template status expresses business state, not whether the button can be clicked.
UPDATE provider_template
SET status = '未启用'
WHERE status IN ('可启用', '可配置', '待配置', 'PENDING', 'ACTIVE');

UPDATE provider_template t
SET status = '已启用'
WHERE EXISTS (
  SELECT 1
  FROM provider_instance i
  WHERE i.provider_template_id = t.id
    AND i.status <> '停用'
);

UPDATE provider_template
SET status = '已停用'
WHERE status IN ('停用', '禁用', 'DISABLED');

CREATE INDEX IF NOT EXISTS idx_provider_instance_template
ON provider_instance(provider_template_id);

CREATE INDEX IF NOT EXISTS idx_provider_instance_name
ON provider_instance(instance_name);
