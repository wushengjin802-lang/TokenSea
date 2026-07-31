-- TokenSea 自动参考价格跨来源绑定：
-- 1. 保留价格采集来源，不把聚合渠道价格伪装为模型厂商官方价；
-- 2. 仅允许完整模型名精确匹配；
-- 3. 根据模型原始厂商规则生成可审计绑定，并按官方/厂商/聚合/内置分级回退。

CREATE TABLE reference_model_origin_rule (
  id varchar(64) PRIMARY KEY,
  canonical_provider_type varchar(80) NOT NULL,
  provider_aliases jsonb NOT NULL DEFAULT '[]',
  model_prefix varchar(240) NOT NULL,
  priority int NOT NULL DEFAULT 100,
  source_ref varchar(1200) NOT NULL DEFAULT 'builtin://tokensea/reference-model-origin',
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_reference_origin_aliases CHECK(jsonb_typeof(provider_aliases)='array'),
  CONSTRAINT ck_reference_origin_status CHECK(status IN ('ACTIVE','DISABLED')),
  CONSTRAINT ck_reference_origin_priority CHECK(priority>=0),
  UNIQUE(canonical_provider_type,model_prefix)
);
CREATE INDEX idx_reference_origin_prefix
  ON reference_model_origin_rule(status,priority DESC,model_prefix);

INSERT INTO reference_model_origin_rule(
  id,canonical_provider_type,provider_aliases,model_prefix,priority,source_ref)
VALUES
  ('origin_doubao','volcengine_ark','["volcengine_ark","volcengine","doubao","byteplus"]','doubao-',500,
   'builtin://tokensea/model-origin/doubao'),
  ('origin_deepseek','deepseek','["deepseek"]','deepseek-',500,
   'builtin://tokensea/model-origin/deepseek'),
  ('origin_qwen','qwen','["qwen","dashscope","alibaba"]','qwen-',500,
   'builtin://tokensea/model-origin/qwen'),
  ('origin_qwq','qwen','["qwen","dashscope","alibaba"]','qwq-',500,
   'builtin://tokensea/model-origin/qwq'),
  ('origin_kimi','moonshot','["moonshot","kimi"]','kimi-',500,
   'builtin://tokensea/model-origin/kimi'),
  ('origin_moonshot','moonshot','["moonshot","kimi"]','moonshot-',500,
   'builtin://tokensea/model-origin/moonshot'),
  ('origin_glm','zhipu','["zhipu","zhipuai","glm"]','glm-',500,
   'builtin://tokensea/model-origin/glm'),
  ('origin_mimo','xiaomi_mimo','["xiaomi_mimo","xiaomi","mimo"]','mimo-',500,
   'builtin://tokensea/model-origin/mimo')
ON CONFLICT(canonical_provider_type,model_prefix) DO UPDATE SET
  provider_aliases=excluded.provider_aliases,
  priority=excluded.priority,
  source_ref=excluded.source_ref,
  status='ACTIVE',
  updated_at=now();

CREATE TABLE reference_price_binding (
  id varchar(64) PRIMARY KEY,
  deployment_id varchar(64) NOT NULL REFERENCES channel_model_deployment(id) ON DELETE CASCADE,
  reference_price_id varchar(64) NOT NULL REFERENCES public_model_price_reference(id) ON DELETE CASCADE,
  target_provider_type varchar(80) NOT NULL,
  target_model_name varchar(240) NOT NULL,
  target_region varchar(80) NOT NULL DEFAULT 'global',
  origin_provider_type varchar(80),
  source_provider_type varchar(80) NOT NULL,
  source_model_name varchar(240) NOT NULL,
  source_region varchar(80) NOT NULL DEFAULT 'global',
  match_type varchar(30) NOT NULL,
  match_confidence numeric(5,4) NOT NULL,
  match_reason varchar(1200) NOT NULL,
  generated_by varchar(30) NOT NULL DEFAULT 'SYSTEM_RULE',
  status varchar(20) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_reference_binding_match_type CHECK(
    match_type IN ('OFFICIAL_EXACT','VENDOR_EXACT','AGGREGATOR_EXACT','BUNDLED_EXACT')),
  CONSTRAINT ck_reference_binding_confidence CHECK(match_confidence BETWEEN 0 AND 1),
  CONSTRAINT ck_reference_binding_generated_by CHECK(generated_by IN ('SYSTEM_RULE','MANUAL_REVIEW')),
  CONSTRAINT ck_reference_binding_status CHECK(status IN ('ACTIVE','REJECTED','EXPIRED')),
  UNIQUE(deployment_id,reference_price_id)
);
CREATE INDEX idx_reference_binding_deployment
  ON reference_price_binding(deployment_id,status,match_type,match_confidence DESC);
CREATE INDEX idx_reference_binding_reference
  ON reference_price_binding(reference_price_id,status);

CREATE OR REPLACE FUNCTION tokensea_refresh_reference_price_bindings()
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
  v_active_count integer;
BEGIN
  UPDATE reference_price_binding
  SET status='EXPIRED',updated_at=now()
  WHERE generated_by='SYSTEM_RULE' AND status='ACTIVE';

  WITH deployment_origin AS (
    SELECT d.id deployment_id,
           p.provider_type target_provider_type,
           d.provider_model_name target_model_name,
           coalesce(nullif(trim(p.region),''),'global') target_region,
           origin.canonical_provider_type,
           coalesce(origin.provider_aliases,'[]'::jsonb) provider_aliases
    FROM channel_model_deployment d
    JOIN provider_instance p ON p.id=d.provider_instance_id
    LEFT JOIN LATERAL (
      SELECT o.canonical_provider_type,o.provider_aliases
      FROM reference_model_origin_rule o
      WHERE o.status='ACTIVE'
        AND lower(d.provider_model_name) LIKE lower(o.model_prefix)||'%'
        AND (
          lower(p.provider_type)=lower(o.canonical_provider_type)
          OR EXISTS(
            SELECT 1 FROM jsonb_array_elements_text(o.provider_aliases) alias(value)
            WHERE lower(alias.value)=lower(p.provider_type)
          )
        )
      ORDER BY o.priority DESC,length(o.model_prefix) DESC,o.id
      LIMIT 1
    ) origin ON true
  ), candidates AS (
    SELECT dctx.deployment_id,r.id reference_price_id,
           dctx.target_provider_type,dctx.target_model_name,dctx.target_region,
           dctx.canonical_provider_type origin_provider_type,
           r.provider_type source_provider_type,r.provider_model_name source_model_name,
           r.region source_region,
           CASE
             WHEN s.trust_level IN ('OFFICIAL_PUBLIC','OFFICIAL_ACCOUNT')
               AND (
                 lower(r.provider_type)=lower(dctx.target_provider_type)
                 OR (dctx.canonical_provider_type IS NOT NULL AND (
                   lower(r.provider_type)=lower(dctx.canonical_provider_type)
                   OR EXISTS(
                     SELECT 1 FROM jsonb_array_elements_text(dctx.provider_aliases) alias(value)
                     WHERE lower(alias.value)=lower(r.provider_type)
                   )
                 ))
               ) THEN 'OFFICIAL_EXACT'
             WHEN s.adapter_code='BUNDLED_REFERENCE' THEN 'BUNDLED_EXACT'
             WHEN lower(r.provider_type)=lower(dctx.target_provider_type) THEN 'VENDOR_EXACT'
             WHEN dctx.canonical_provider_type IS NOT NULL AND (
               lower(r.provider_type)=lower(dctx.canonical_provider_type)
               OR EXISTS(
                 SELECT 1 FROM jsonb_array_elements_text(dctx.provider_aliases) alias(value)
                 WHERE lower(alias.value)=lower(r.provider_type)
               )
             ) THEN 'VENDOR_EXACT'
             WHEN dctx.canonical_provider_type IS NOT NULL THEN 'AGGREGATOR_EXACT'
             ELSE NULL
           END match_type,
           CASE
             WHEN s.trust_level IN ('OFFICIAL_PUBLIC','OFFICIAL_ACCOUNT')
               AND (
                 lower(r.provider_type)=lower(dctx.target_provider_type)
                 OR (dctx.canonical_provider_type IS NOT NULL AND (
                   lower(r.provider_type)=lower(dctx.canonical_provider_type)
                   OR EXISTS(
                     SELECT 1 FROM jsonb_array_elements_text(dctx.provider_aliases) alias(value)
                     WHERE lower(alias.value)=lower(r.provider_type)
                   )
                 ))
               ) THEN least(r.source_confidence,1.0000)
             WHEN s.adapter_code='BUNDLED_REFERENCE' THEN least(r.source_confidence,0.6000)
             WHEN lower(r.provider_type)=lower(dctx.target_provider_type) THEN least(r.source_confidence,0.9000)
             WHEN dctx.canonical_provider_type IS NOT NULL AND (
               lower(r.provider_type)=lower(dctx.canonical_provider_type)
               OR EXISTS(
                 SELECT 1 FROM jsonb_array_elements_text(dctx.provider_aliases) alias(value)
                 WHERE lower(alias.value)=lower(r.provider_type)
               )
             ) THEN least(r.source_confidence,0.8500)
             ELSE least(r.source_confidence,0.7000)
           END match_confidence,
           CASE
             WHEN s.trust_level IN ('OFFICIAL_PUBLIC','OFFICIAL_ACCOUNT')
               AND (
                 lower(r.provider_type)=lower(dctx.target_provider_type)
                 OR (dctx.canonical_provider_type IS NOT NULL AND (
                   lower(r.provider_type)=lower(dctx.canonical_provider_type)
                   OR EXISTS(
                     SELECT 1 FROM jsonb_array_elements_text(dctx.provider_aliases) alias(value)
                     WHERE lower(alias.value)=lower(r.provider_type)
                   )
                 ))
               ) THEN '完整模型名精确一致，价格来源具有官方可信等级'
             WHEN s.adapter_code='BUNDLED_REFERENCE'
               THEN '完整模型名精确一致，使用 TokenSea 随版本发布的内置参考快照'
             WHEN lower(r.provider_type)=lower(dctx.target_provider_type)
               THEN '完整模型名与供应商类型均精确一致'
             WHEN dctx.canonical_provider_type IS NOT NULL AND (
               lower(r.provider_type)=lower(dctx.canonical_provider_type)
               OR EXISTS(
                 SELECT 1 FROM jsonb_array_elements_text(dctx.provider_aliases) alias(value)
                 WHERE lower(alias.value)=lower(r.provider_type)
               )
             ) THEN '完整模型名精确一致，来源供应商属于同一模型原始厂商'
             ELSE '完整模型名精确一致，依据模型原始厂商规则使用聚合渠道公开参考价'
           END match_reason
    FROM deployment_origin dctx
    JOIN public_model_price_reference r
      ON lower(r.provider_model_name)=lower(dctx.target_model_name)
     AND (lower(r.region)=lower(dctx.target_region) OR lower(r.region)='global')
    JOIN provider_price_source s ON s.id=r.price_source_id
    WHERE r.is_current=true AND r.status='ACTIVE' AND r.price_status='CURRENT'
      AND (r.stale_at IS NULL OR r.stale_at>now())
      AND (coalesce(r.input_unit_price,0)>0 OR coalesce(r.output_unit_price,0)>0
        OR coalesce(r.cache_read_unit_price,0)>0 OR coalesce(r.cache_write_unit_price,0)>0)
      AND (
        lower(r.provider_type)=lower(dctx.target_provider_type)
        OR dctx.canonical_provider_type IS NOT NULL
      )
  ), eligible AS (
    SELECT * FROM candidates WHERE match_type IS NOT NULL
  )
  INSERT INTO reference_price_binding(
    id,deployment_id,reference_price_id,target_provider_type,target_model_name,target_region,
    origin_provider_type,source_provider_type,source_model_name,source_region,match_type,
    match_confidence,match_reason,generated_by,status)
  SELECT md5(deployment_id||':'||reference_price_id),deployment_id,reference_price_id,
         target_provider_type,target_model_name,target_region,origin_provider_type,
         source_provider_type,source_model_name,source_region,match_type,
         match_confidence,match_reason,'SYSTEM_RULE','ACTIVE'
  FROM eligible
  ON CONFLICT(deployment_id,reference_price_id) DO UPDATE SET
    target_provider_type=excluded.target_provider_type,
    target_model_name=excluded.target_model_name,
    target_region=excluded.target_region,
    origin_provider_type=excluded.origin_provider_type,
    source_provider_type=excluded.source_provider_type,
    source_model_name=excluded.source_model_name,
    source_region=excluded.source_region,
    match_type=excluded.match_type,
    match_confidence=excluded.match_confidence,
    match_reason=excluded.match_reason,
    status='ACTIVE',
    updated_at=now()
  WHERE reference_price_binding.status<>'REJECTED';

  SELECT count(*) INTO v_active_count
  FROM reference_price_binding
  WHERE status='ACTIVE';
  RETURN v_active_count;
END
$$;

CREATE OR REPLACE VIEW v_effective_deployment_reference_price AS
WITH ranked AS (
  SELECT b.id binding_id,b.deployment_id,b.target_provider_type,b.target_model_name,b.target_region,
         b.origin_provider_type,b.source_provider_type,b.source_model_name,b.source_region,
         b.match_type,b.match_confidence,b.match_reason,
         r.*,s.name source_name,s.adapter_code,s.trust_level,s.data_scope,
         row_number() OVER (
           PARTITION BY b.deployment_id
           ORDER BY
             CASE b.match_type
               WHEN 'OFFICIAL_EXACT' THEN 0
               WHEN 'VENDOR_EXACT' THEN 1
               WHEN 'AGGREGATOR_EXACT' THEN 2
               WHEN 'BUNDLED_EXACT' THEN 3
               ELSE 9
             END,
             CASE WHEN lower(r.region)=lower(b.target_region) THEN 0 ELSE 1 END,
             CASE WHEN upper(r.request_mode) IN ('STANDARD','DEFAULT') THEN 0 ELSE 1 END,
             CASE WHEN upper(r.service_tier)='DEFAULT' THEN 0 ELSE 1 END,
             CASE WHEN upper(r.context_tier)='DEFAULT' THEN 0 ELSE 1 END,
             r.source_rank DESC,r.source_priority DESC,r.source_confidence DESC,
             r.observed_at DESC,r.id
         ) reference_rank
  FROM reference_price_binding b
  JOIN public_model_price_reference r ON r.id=b.reference_price_id
  JOIN provider_price_source s ON s.id=r.price_source_id
  WHERE b.status='ACTIVE'
    AND r.is_current=true AND r.status='ACTIVE' AND r.price_status='CURRENT'
    AND (r.stale_at IS NULL OR r.stale_at>now())
)
SELECT * FROM ranked WHERE reference_rank=1;

SELECT tokensea_refresh_reference_price_bindings();
