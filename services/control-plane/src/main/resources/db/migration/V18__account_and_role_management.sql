-- TokenSea 账户与角色管理基础能力（V18：避免与已执行的汇率迁移 V17 冲突）。
-- 研发阶段允许直接规范现有测试数据，补齐角色状态、系统内置标识和关联约束。

ALTER TABLE user_account
  ADD COLUMN IF NOT EXISTS password_changed_at timestamptz,
  ADD COLUMN IF NOT EXISTS last_login_at timestamptz;

UPDATE user_account
SET status='ACTIVE'
WHERE status IS NULL OR status NOT IN ('ACTIVE','DISABLED');

ALTER TABLE user_account DROP CONSTRAINT IF EXISTS ck_user_account_status;
ALTER TABLE user_account ADD CONSTRAINT ck_user_account_status
  CHECK(status IN ('ACTIVE','DISABLED'));

ALTER TABLE role
  ADD COLUMN IF NOT EXISTS description text,
  ADD COLUMN IF NOT EXISTS status varchar(30) NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN IF NOT EXISTS system_builtin boolean NOT NULL DEFAULT false;

UPDATE role
SET status='ACTIVE'
WHERE status IS NULL OR status NOT IN ('ACTIVE','INACTIVE');

UPDATE role
SET system_builtin=true
WHERE code='ADMIN';

ALTER TABLE role DROP CONSTRAINT IF EXISTS ck_role_status;
ALTER TABLE role ADD CONSTRAINT ck_role_status
  CHECK(status IN ('ACTIVE','INACTIVE'));

INSERT INTO role(id,code,name,description,status,system_builtin)
VALUES
  ('role_admin','ADMIN','平台管理员','拥有 TokenSea 平台全部管理权限。','ACTIVE',true),
  ('role_tenant_user','TENANT_USER','租户用户','访问已授权租户、项目、应用和开发者门户。','ACTIVE',true)
ON CONFLICT(code) DO UPDATE SET
  name=excluded.name,
  description=coalesce(role.description,excluded.description),
  status='ACTIVE',
  system_builtin=true,
  updated_at=now();

INSERT INTO permission(id,code,name,type)
VALUES
  ('perm_user_read','USER_READ','查看账户','ACTION'),
  ('perm_user_write','USER_WRITE','管理账户','ACTION'),
  ('perm_role_read','ROLE_READ','查看角色','ACTION'),
  ('perm_role_write','ROLE_WRITE','管理角色','ACTION'),
  ('perm_tenant_read','TENANT_READ','查看租户与应用','ACTION'),
  ('perm_tenant_write','TENANT_WRITE','管理租户与应用','ACTION'),
  ('perm_model_read','MODEL_READ','查看模型配置','ACTION'),
  ('perm_model_write','MODEL_WRITE','管理模型配置','ACTION'),
  ('perm_key_read','API_KEY_READ','查看 API Key','ACTION'),
  ('perm_key_write','API_KEY_WRITE','管理 API Key','ACTION'),
  ('perm_usage_read','USAGE_READ','查看调用与用量','ACTION'),
  ('perm_cost_read','COST_READ','查看成本与对账','ACTION'),
  ('perm_audit_read','AUDIT_READ','查看审计日志','ACTION'),
  ('perm_system_write','SYSTEM_WRITE','管理系统设置','ACTION')
ON CONFLICT(code) DO UPDATE SET
  name=excluded.name,
  type=excluded.type,
  updated_at=now();

DELETE FROM user_role ur
WHERE NOT EXISTS (SELECT 1 FROM user_account u WHERE u.id=ur.user_id)
   OR NOT EXISTS (SELECT 1 FROM role r WHERE r.id=ur.role_id);

DELETE FROM role_permission rp
WHERE NOT EXISTS (SELECT 1 FROM role r WHERE r.id=rp.role_id)
   OR NOT EXISTS (SELECT 1 FROM permission p WHERE p.id=rp.permission_id);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_user_role_user') THEN
    ALTER TABLE user_role ADD CONSTRAINT fk_user_role_user
      FOREIGN KEY(user_id) REFERENCES user_account(id) ON DELETE CASCADE;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_user_role_role') THEN
    ALTER TABLE user_role ADD CONSTRAINT fk_user_role_role
      FOREIGN KEY(role_id) REFERENCES role(id) ON DELETE RESTRICT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_role_permission_role') THEN
    ALTER TABLE role_permission ADD CONSTRAINT fk_role_permission_role
      FOREIGN KEY(role_id) REFERENCES role(id) ON DELETE CASCADE;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='fk_role_permission_permission') THEN
    ALTER TABLE role_permission ADD CONSTRAINT fk_role_permission_permission
      FOREIGN KEY(permission_id) REFERENCES permission(id) ON DELETE RESTRICT;
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_user_account_status ON user_account(status,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_role_status ON role(status,code);
CREATE INDEX IF NOT EXISTS idx_user_role_role ON user_role(role_id,user_id);
CREATE INDEX IF NOT EXISTS idx_role_permission_permission ON role_permission(permission_id,role_id);

INSERT INTO role_permission(role_id,permission_id)
SELECT r.id,p.id FROM role r CROSS JOIN permission p
WHERE r.code='ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permission(role_id,permission_id)
SELECT r.id,p.id FROM role r CROSS JOIN permission p
WHERE r.code='TENANT_USER'
  AND p.code IN ('TENANT_READ','MODEL_READ','API_KEY_READ','USAGE_READ')
ON CONFLICT DO NOTHING;

