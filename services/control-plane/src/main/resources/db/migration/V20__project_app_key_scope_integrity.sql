-- Strengthen tenant -> project -> app -> Virtual Key hierarchy integrity.
-- Existing records are preserved; all future inserts and updates must satisfy the ownership chain.

CREATE INDEX IF NOT EXISTS idx_app_project ON app(project_id);
CREATE INDEX IF NOT EXISTS idx_api_key_project ON api_key(project_id);
CREATE INDEX IF NOT EXISTS idx_api_key_app ON api_key(app_id);
CREATE INDEX IF NOT EXISTS idx_usage_project ON usage_record(project_id);
CREATE INDEX IF NOT EXISTS idx_usage_app ON usage_record(app_id);

CREATE OR REPLACE FUNCTION tokensea_validate_app_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  project_tenant varchar(64);
BEGIN
  IF NEW.project_id IS NULL THEN
    RAISE EXCEPTION '应用必须归属于项目' USING ERRCODE='23514';
  END IF;
  SELECT tenant_id INTO project_tenant FROM project WHERE id=NEW.project_id;
  IF project_tenant IS NULL THEN
    RAISE EXCEPTION '应用所属项目不存在' USING ERRCODE='23503';
  END IF;
  IF project_tenant<>NEW.tenant_id THEN
    RAISE EXCEPTION '应用租户与项目租户不一致' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_validate_app_scope ON app;
CREATE TRIGGER trg_validate_app_scope
BEFORE INSERT OR UPDATE OF tenant_id,project_id ON app
FOR EACH ROW EXECUTE FUNCTION tokensea_validate_app_scope();

CREATE OR REPLACE FUNCTION tokensea_validate_api_key_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  project_tenant varchar(64);
  app_tenant varchar(64);
  app_project varchar(64);
BEGIN
  IF NEW.project_id IS NOT NULL THEN
    SELECT tenant_id INTO project_tenant FROM project WHERE id=NEW.project_id;
    IF project_tenant IS NULL THEN
      RAISE EXCEPTION 'Virtual Key 所属项目不存在' USING ERRCODE='23503';
    END IF;
    IF project_tenant<>NEW.tenant_id THEN
      RAISE EXCEPTION 'Virtual Key 租户与项目租户不一致' USING ERRCODE='23514';
    END IF;
  END IF;

  IF NEW.app_id IS NOT NULL THEN
    IF NEW.project_id IS NULL THEN
      RAISE EXCEPTION 'Virtual Key 选择应用时必须同时选择项目' USING ERRCODE='23514';
    END IF;
    SELECT tenant_id,project_id INTO app_tenant,app_project FROM app WHERE id=NEW.app_id;
    IF app_tenant IS NULL THEN
      RAISE EXCEPTION 'Virtual Key 所属应用不存在' USING ERRCODE='23503';
    END IF;
    IF app_tenant<>NEW.tenant_id OR app_project<>NEW.project_id THEN
      RAISE EXCEPTION 'Virtual Key 的租户、项目和应用归属不一致' USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_validate_api_key_scope ON api_key;
CREATE TRIGGER trg_validate_api_key_scope
BEFORE INSERT OR UPDATE OF tenant_id,project_id,app_id ON api_key
FOR EACH ROW EXECUTE FUNCTION tokensea_validate_api_key_scope();
