-- Phase 4A forward-only delivery contracts. V5-V11 remain immutable.

CREATE TABLE error_code_registry (
  code varchar(120) PRIMARY KEY, http_status int NOT NULL, category varchar(40) NOT NULL,
  reason_zh varchar(500) NOT NULL, retry_advice_zh varchar(500) NOT NULL,
  retryable boolean NOT NULL DEFAULT false, component varchar(40) NOT NULL,
  active boolean NOT NULL DEFAULT true, updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO error_code_registry(code,http_status,category,reason_zh,retry_advice_zh,retryable,component) VALUES
 ('TOKENSEA_INVALID_JSON',400,'请求','请求体不是有效 JSON','修正请求体后重试',false,'GATEWAY'),
 ('TOKENSEA_INVALID_REQUEST',400,'请求','请求参数不符合接口契约','按错误详情修正参数后重试',false,'GATEWAY'),
 ('TOKENSEA_MODEL_REQUIRED',400,'模型','请求未指定服务模型','填写从 /v1/models 获取的模型标识',false,'GATEWAY'),
 ('TOKENSEA_REQUEST_ID_INVALID',400,'请求','请求标识格式无效','移除自定义标识或使用合法标识后重试',false,'GATEWAY'),
 ('TOKENSEA_AUTH_REQUIRED',401,'认证','缺少 Virtual Key','补充 Authorization 请求头',false,'GATEWAY'),
 ('TOKENSEA_KEY_INVALID',401,'认证','Virtual Key 无效','检查 Key 内容或重新签发',false,'GATEWAY'),
 ('TOKENSEA_BUDGET_EXCEEDED',402,'预算','已批准生效的预算规则拒绝请求','调整预算或等待新周期后重试',false,'GATEWAY'),
 ('TOKENSEA_APP_FORBIDDEN',403,'授权','Key 无权访问该应用','检查 Key 的应用归属',false,'GATEWAY'),
 ('TOKENSEA_IP_FORBIDDEN',403,'授权','来源地址不在白名单','从授权地址调用或调整白名单',false,'GATEWAY'),
 ('TOKENSEA_IP_POLICY_INVALID',403,'授权','Key 的地址策略无效','修复地址策略后重试',false,'GATEWAY'),
 ('TOKENSEA_KEY_DISABLED',403,'认证','Virtual Key 已停用','启用或重新签发 Key',false,'GATEWAY'),
 ('TOKENSEA_KEY_EXPIRED',403,'认证','Virtual Key 已过期','续期或重新签发 Key',false,'GATEWAY'),
 ('TOKENSEA_MODEL_FORBIDDEN',403,'授权','Key 无权访问该模型','调整模型范围后重试',false,'GATEWAY'),
 ('TOKENSEA_MODEL_NOT_VISIBLE',403,'授权','模型对当前租户不可见','调整租户可见范围',false,'GATEWAY'),
 ('TOKENSEA_PROJECT_FORBIDDEN',403,'授权','Key 无权访问该项目','检查项目归属',false,'GATEWAY'),
 ('TOKENSEA_SCOPE_EMPTY',403,'授权','Key 没有可用模型范围','为 Key 配置模型范围',false,'GATEWAY'),
 ('TOKENSEA_TENANT_DISABLED',403,'租户','租户已停用','启用租户后重试',false,'GATEWAY'),
 ('TOKENSEA_MODEL_NOT_FOUND',404,'模型','服务模型不存在','从 /v1/models 重新选择',false,'GATEWAY'),
 ('TOKENSEA_BUDGET_RESERVATION_CONFLICT',409,'预算','预算预留发生并发冲突','稍后重试',true,'GATEWAY'),
 ('TOKENSEA_GATEWAY_ERROR',502,'网关','上游调用失败','使用 request_id 查看调用尝试后重试',true,'GATEWAY'),
 ('TOKENSEA_UPSTREAM_AUTH_ERROR',502,'供应商','供应商认证失败','检查托管凭证后重试',false,'GATEWAY'),
 ('TOKENSEA_UPSTREAM_RATE_LIMIT',429,'供应商','供应商触发限流','按退避策略重试',true,'GATEWAY'),
 ('TOKENSEA_UPSTREAM_TIMEOUT',504,'供应商','供应商响应超时','稍后重试或切换渠道',true,'GATEWAY'),
 ('TOKENSEA_UPSTREAM_UNAVAILABLE',503,'供应商','供应商当前不可用','稍后重试或切换渠道',true,'GATEWAY'),
 ('TOKENSEA_STREAM_INTERRUPTED',502,'流式响应','上游流式响应中断','使用 request_id 排查后重试',true,'GATEWAY'),
 ('TOKENSEA_BUDGET_DEGRADE_UNAVAILABLE',503,'预算','预算降级模型不可用','修复降级模型路由后重试',false,'GATEWAY'),
 ('TOKENSEA_BUDGET_STATE_INVALID',503,'预算','预算状态数据无效','修复预算数据后重试',false,'GATEWAY'),
 ('TOKENSEA_BUDGET_UNAVAILABLE',503,'预算','预算服务不可用','稍后重试',true,'GATEWAY'),
 ('TOKENSEA_CONFIG_INVALID',503,'配置','运行配置无效','修复控制面配置后重试',false,'GATEWAY'),
 ('TOKENSEA_DNS_HOST_CHANGED',503,'网络','供应商域名解析结果变化','确认目标地址安全后重新测试',false,'GATEWAY'),
 ('TOKENSEA_DNS_RESOLUTION_FAILED',503,'网络','供应商域名解析失败','检查域名与 DNS 后重试',true,'GATEWAY'),
 ('TOKENSEA_DNS_SNAPSHOT_CHANGED',503,'网络','供应商 DNS 快照已变化','重新执行连接验证',false,'GATEWAY'),
 ('TOKENSEA_DNS_SNAPSHOT_INVALID',503,'网络','供应商 DNS 快照无效','重新执行连接验证',false,'GATEWAY'),
 ('TOKENSEA_DNS_SNAPSHOT_MISSING',503,'网络','缺少供应商 DNS 快照','先执行连接验证',false,'GATEWAY'),
 ('TOKENSEA_PRICE_INVALID',503,'价格','价格版本数据无效','修复价格版本后重试',false,'GATEWAY'),
 ('TOKENSEA_PRICE_NOT_CONFIGURED',503,'价格','渠道未配置实际价格','配置并审批渠道实际价格',false,'GATEWAY'),
 ('TOKENSEA_PRICE_NOT_EFFECTIVE',503,'价格','价格版本当前未生效','检查生效区间和审批状态',false,'GATEWAY'),
 ('TOKENSEA_RATE_LIMIT_UNAVAILABLE',503,'限流','限流服务不可用','稍后重试',true,'GATEWAY'),
 ('TOKENSEA_ROUTE_MAPPING_INVALID',503,'路由','路由模型映射无效','修复渠道与实际模型映射',false,'GATEWAY'),
 ('TOKENSEA_ROUTE_POLICY_INVALID',503,'路由','路由策略无效','修复并重新审批路由策略',false,'GATEWAY'),
 ('TOKENSEA_RUNTIME_CONFIG_FAILED',503,'运行时','运行时配置加载失败','修复配置后重试',true,'GATEWAY'),
 ('TOKENSEA_RUNTIME_CONFLICT',503,'运行时','运行时状态冲突','稍后重试',true,'GATEWAY'),
 ('TOKENSEA_RUNTIME_NOT_CONFIGURED',503,'运行时','运行时尚未配置','完成运行时配置后重试',false,'GATEWAY'),
 ('TOKENSEA_SECRET_DECRYPT_FAILED',503,'密钥','供应商密钥解密失败','重新托管供应商密钥',false,'GATEWAY'),
 ('TOKENSEA_SECRET_REF_UNSUPPORTED',503,'密钥','密钥引用类型不受支持','使用受支持的密钥托管方式',false,'GATEWAY'),
 ('TOKENSEA_SECRET_STORE_NOT_CONFIGURED',503,'密钥','密钥存储未配置','配置密钥存储后重试',false,'GATEWAY'),
 ('TOKENSEA_SSRF_TARGET_REJECTED',503,'安全','供应商目标未通过出口安全校验','修复白名单和供应商地址',false,'GATEWAY');

ALTER TABLE price_version ADD CONSTRAINT ck_price_layer_owner CHECK (
 (price_layer='PUBLIC_REFERENCE' AND public_model_reference_id IS NOT NULL AND deployment_id IS NULL AND platform_model_id IS NULL) OR
 (price_layer='CHANNEL_ACTUAL' AND public_model_reference_id IS NULL AND deployment_id IS NOT NULL AND platform_model_id IS NULL) OR
 (price_layer='INTERNAL_ACCOUNTING' AND public_model_reference_id IS NULL AND deployment_id IS NULL AND platform_model_id IS NOT NULL)) NOT VALID;
ALTER TABLE price_version ADD CONSTRAINT ck_price_source_type CHECK(source_type IN ('OFFICIAL_REFERENCE','PROVIDER_BILL','PROVIDER_API','CONTRACT','INTERNAL_POLICY','MANUAL_VERIFIED')) NOT VALID;

ALTER TABLE budget_rule ADD COLUMN approval_status varchar(30) NOT NULL DEFAULT 'APPROVED', ADD COLUMN approved_by varchar(64), ADD COLUMN approved_at timestamptz, ADD COLUMN retired_by varchar(64), ADD COLUMN retired_at timestamptz;
ALTER TABLE budget_rule ADD CONSTRAINT ck_budget_approval_status CHECK(approval_status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED'));

ALTER TABLE data_source ADD CONSTRAINT ck_data_source_schedule CHECK(sync_mode<>'SCHEDULED' OR (schedule_expression IS NOT NULL AND next_run_at IS NOT NULL)) NOT VALID;

CREATE TABLE cost_statement (
 id varchar(64) PRIMARY KEY, statement_no varchar(80) NOT NULL UNIQUE, tenant_id varchar(64) REFERENCES tenant(id),
 period_start timestamptz NOT NULL, period_end timestamptz NOT NULL, currency varchar(3) NOT NULL,
 request_count bigint NOT NULL, prompt_tokens bigint NOT NULL, completion_tokens bigint NOT NULL,
 actual_cost numeric(20,8) NOT NULL, adjustment_amount numeric(20,8) NOT NULL DEFAULT 0,
 status varchar(30) NOT NULL DEFAULT 'GENERATED', anomaly_detail jsonb NOT NULL DEFAULT '{}',
 confirmed_by varchar(64), confirmed_at timestamptz, exported_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT ck_cost_statement_status CHECK(status IN ('GENERATED','CONFIRMED','EXPORTED','ANOMALOUS','ADJUSTED')),
 CONSTRAINT ck_cost_statement_period CHECK(period_end>period_start));
CREATE TABLE cost_statement_line (
 id varchar(64) PRIMARY KEY, statement_id varchar(64) NOT NULL REFERENCES cost_statement(id) ON DELETE CASCADE,
 project_id varchar(64), app_id varchar(64), api_key_id varchar(64), model_alias varchar(200), provider_id varchar(64),
 request_count bigint NOT NULL, prompt_tokens bigint NOT NULL, completion_tokens bigint NOT NULL, actual_cost numeric(20,8) NOT NULL, created_at timestamptz NOT NULL DEFAULT now());

ALTER TABLE provider_reconciliation ADD COLUMN token_difference bigint NOT NULL DEFAULT 0, ADD COLUMN price_difference numeric(20,8) NOT NULL DEFAULT 0, ADD COLUMN exchange_rate_difference numeric(20,8) NOT NULL DEFAULT 0, ADD COLUMN tax_difference numeric(20,8) NOT NULL DEFAULT 0, ADD COLUMN difference_classification jsonb NOT NULL DEFAULT '{}', ADD COLUMN confirmed_by varchar(64), ADD COLUMN confirmed_at timestamptz, ADD COLUMN resolved_by varchar(64), ADD COLUMN resolved_at timestamptz;

ALTER TABLE sensitive_access_log ADD COLUMN tenant_id varchar(64) REFERENCES tenant(id), ADD COLUMN ip_address varchar(100), ADD COLUMN user_agent varchar(1000);
