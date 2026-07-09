CREATE TABLE IF NOT EXISTS provider_template (
  id varchar(64) PRIMARY KEY,
  provider_name varchar(200) NOT NULL,
  provider_type varchar(80) NOT NULL,
  protocol varchar(80) NOT NULL,
  default_api_base varchar(500),
  auth_type varchar(80) NOT NULL DEFAULT 'Bearer Token',
  supported_endpoints text NOT NULL DEFAULT '[]',
  error_mapping text NOT NULL DEFAULT '{}',
  health_check_path varchar(300),
  default_rate_limit_rpm int,
  default_rate_limit_tpm int,
  model_template_count int NOT NULL DEFAULT 0,
  built_in varchar(10) NOT NULL DEFAULT '是',
  status varchar(40) NOT NULL DEFAULT '可启用',
  description text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS provider_instance (
  id varchar(64) PRIMARY KEY,
  provider_template_id varchar(64) REFERENCES provider_template(id) ON DELETE SET NULL,
  instance_name varchar(200) NOT NULL,
  provider_type varchar(80) NOT NULL,
  api_style varchar(80) NOT NULL DEFAULT 'openai_compatible',
  api_base varchar(500),
  region varchar(100),
  credential_ref varchar(300),
  key_status varchar(40) NOT NULL DEFAULT '未配置',
  environment varchar(40) NOT NULL DEFAULT '生产',
  health_status varchar(40) NOT NULL DEFAULT '观察',
  enabled_models text NOT NULL DEFAULT '[]',
  owner varchar(100),
  status varchar(40) NOT NULL DEFAULT '暂停',
  rate_limit_rpm int,
  rate_limit_tpm int,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS model_capability_tag (
  id varchar(64) PRIMARY KEY,
  name varchar(100) UNIQUE NOT NULL,
  description text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS model_template (
  id varchar(64) PRIMARY KEY,
  provider_template_id varchar(64) REFERENCES provider_template(id) ON DELETE SET NULL,
  provider_name varchar(200),
  provider_model_name varchar(200) NOT NULL,
  default_display_name varchar(200) NOT NULL,
  context_length int,
  supported_endpoints text NOT NULL DEFAULT '[]',
  supports_streaming boolean NOT NULL DEFAULT false,
  supports_tools boolean NOT NULL DEFAULT false,
  capability_tags text NOT NULL DEFAULT '[]',
  default_cost_level varchar(40),
  default_quality_level varchar(40),
  compliance_tags text NOT NULL DEFAULT '[]',
  built_in varchar(10) NOT NULL DEFAULT '是',
  status varchar(40) NOT NULL DEFAULT '可发布',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS platform_model (
  id varchar(64) PRIMARY KEY,
  platform_model_name varchar(160) UNIQUE NOT NULL,
  display_name varchar(200) NOT NULL,
  model_template_ids text NOT NULL DEFAULT '[]',
  provider_instance_ids text NOT NULL DEFAULT '[]',
  actual_models text NOT NULL DEFAULT '[]',
  route_policy_id varchar(64),
  route_policy varchar(120),
  price_policy_id varchar(64),
  price_policy varchar(120),
  visibility_scope varchar(200) NOT NULL DEFAULT '全部租户',
  approval_required boolean NOT NULL DEFAULT false,
  status varchar(40) NOT NULL DEFAULT '草稿',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO provider_template (id, provider_name, provider_type, protocol, default_api_base, auth_type, supported_endpoints, health_check_path, default_rate_limit_rpm, default_rate_limit_tpm, model_template_count, built_in, status, description)
VALUES
('tpl_deepseek','DeepSeek','deepseek','OpenAI-compatible','https://api.deepseek.com','Bearer Token','["Chat","Responses"]','/models',600,300000,2,'是','可启用','国产文本与推理模型'),
('tpl_qwen','Qwen / 阿里云百炼','qwen','OpenAI-compatible','https://dashscope.aliyuncs.com/compatible-mode/v1','Bearer Token','["Chat","Responses","Embeddings","Rerank"]','/models',600,300000,4,'是','可启用','国产通用模型'),
('tpl_openai','OpenAI','openai','OpenAI-compatible','https://api.openai.com/v1','Bearer Token','["Chat","Responses","Embeddings","Images","Audio"]','/models',500,300000,4,'是','可启用','国际主流模型'),
('tpl_azure_openai','Azure OpenAI','azure_openai','Azure',NULL,'Azure Key','["Chat","Responses","Embeddings","Images"]','/models',500,300000,3,'是','可启用','企业云厂商模型'),
('tpl_anthropic','Anthropic Claude','anthropic','Anthropic','https://api.anthropic.com','Bearer Token','["Chat","Messages"]','/v1/messages',500,200000,3,'是','可启用','长文本与复杂推理'),
('tpl_gemini','Gemini','gemini','Gemini','https://generativelanguage.googleapis.com','Bearer Token','["Chat","Images","Audio"]','/models',500,200000,3,'是','可启用','多模态模型'),
('tpl_vllm','vLLM','vllm','OpenAI-compatible','http://localhost:8000/v1','None','["Chat","Embeddings"]','/models',1000,500000,0,'是','可配置','私有模型部署'),
('tpl_ollama','Ollama','ollama','OpenAI-compatible','http://localhost:11434/v1','None','["Chat","Embeddings"]','/models',1000,500000,0,'是','可配置','本地模型服务'),
('tpl_custom','Custom Provider','custom','OpenAI-compatible',NULL,'Bearer Token','["Chat","Embeddings","Rerank"]','/models',NULL,NULL,0,'否','可配置','自定义供应商')
ON CONFLICT (id) DO NOTHING;

INSERT INTO model_capability_tag (id, name, description)
VALUES
('tag_text','文本生成','通用对话、问答、总结'),
('tag_code','代码生成','代码补全、解释、重构'),
('tag_long_context','长上下文','文档分析、合同审核'),
('tag_multimodal','多模态','图片理解、视觉问答'),
('tag_embedding','Embedding','向量检索、RAG'),
('tag_rerank','Rerank','检索排序'),
('tag_low_cost','低成本','批量、客服、低价值任务'),
('tag_high_quality','高质量','高价值、复杂推理任务'),
('tag_cn','国产化','政企和国产化场景'),
('tag_private','私有部署','内网模型、私有 GPU 集群')
ON CONFLICT (id) DO NOTHING;

INSERT INTO model_template (id, provider_template_id, provider_name, provider_model_name, default_display_name, context_length, supported_endpoints, supports_streaming, supports_tools, capability_tags, default_cost_level, default_quality_level, compliance_tags, built_in, status)
VALUES
('mt_deepseek_chat','tpl_deepseek','DeepSeek','deepseek-chat','DeepSeek Chat',64000,'["Chat"]',true,true,'["文本生成","低成本","国产化"]','低','普通','["国产化"]','是','可发布'),
('mt_deepseek_reasoner','tpl_deepseek','DeepSeek','deepseek-reasoner','DeepSeek Reasoner',64000,'["Chat"]',true,true,'["文本生成","高质量","国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_qwen_plus','tpl_qwen','Qwen / 阿里云百炼','qwen-plus','Qwen Plus',128000,'["Chat","Responses"]',true,true,'["文本生成","长上下文","国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_qwen_max','tpl_qwen','Qwen / 阿里云百炼','qwen-max','Qwen Max',128000,'["Chat","Responses"]',true,true,'["文本生成","高质量","国产化"]','高','旗舰','["国产化"]','是','可发布'),
('mt_gpt_4o_mini','tpl_openai','OpenAI','gpt-4o-mini','GPT-4o mini',128000,'["Chat","Responses"]',true,true,'["文本生成","多模态","低成本"]','中','高质量','["境外"]','是','可发布'),
('mt_text_embedding_v3','tpl_qwen','Qwen / 阿里云百炼','text-embedding-v3','Text Embedding V3',8192,'["Embeddings"]',false,false,'["Embedding","国产化"]','低','普通','["国产化"]','是','可发布'),
('mt_private_coder','tpl_vllm','vLLM','private-coder-32b','Private Coder 32B',32768,'["Chat"]',true,true,'["代码生成","私有部署"]','低','高质量','["私有部署"]','是','观察'),
('mt_qwen_rerank','tpl_qwen','Qwen / 阿里云百炼','qwen-rerank','Qwen Rerank',8192,'["Rerank"]',false,false,'["Rerank","国产化"]','低','普通','["国产化"]','是','可发布')
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_model (id, platform_model_name, display_name, model_template_ids, actual_models, route_policy, price_policy, visibility_scope, approval_required, status)
VALUES
('pm_chat_standard','chat-standard','默认通用对话模型','["mt_qwen_plus","mt_deepseek_chat"]','["qwen-plus","deepseek-chat"]','默认 / Fallback','标准价','全部租户',false,'已发布'),
('pm_chat_lowcost','chat-lowcost','低成本对话模型','["mt_deepseek_chat"]','["deepseek-chat","qwen-turbo"]','成本优先','低成本价','全部租户',false,'已发布'),
('pm_chat_premium','chat-premium','高质量对话模型','["mt_gpt_4o_mini","mt_qwen_max"]','["gpt-4o-mini","claude","qwen-max"]','质量优先','高质量价','企业版',true,'灰度'),
('pm_code_fast','code-fast','代码助手模型','["mt_private_coder"]','["private-coder","qwen-coder"]','默认','标准价','指定项目',false,'测试'),
('pm_embedding_standard','embedding-standard','默认向量模型','["mt_text_embedding_v3"]','["text-embedding-v3"]','默认','标准价','全部租户',false,'已发布'),
('pm_rerank_standard','rerank-standard','默认重排模型','["mt_qwen_rerank"]','["bge-reranker","qwen-rerank"]','合规路由','标准价','企业版',false,'草稿')
ON CONFLICT (id) DO NOTHING;
