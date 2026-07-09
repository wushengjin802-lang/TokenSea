-- Refresh built-in provider and model catalog.
-- Built-in templates are metadata, not customer business data.
INSERT INTO provider_template (id, provider_name, provider_type, protocol, default_api_base, auth_type, supported_endpoints, error_mapping, health_check_path, default_rate_limit_rpm, default_rate_limit_tpm, model_template_count, built_in, status, description)
VALUES
('tpl_openai','OpenAI','openai','OpenAI-compatible','https://api.openai.com/v1','Bearer Token','["Responses", "Chat", "Embeddings", "Images", "Audio", "Realtime"]','{}','/models',500,300000,9,'是','未启用','OpenAI 官方 API；文本、视觉、音频、图像与向量模型。'),
('tpl_azure_openai','Azure OpenAI','azure_openai','Azure OpenAI',NULL,'Azure Key','["Responses", "Chat", "Embeddings", "Images", "Audio"]','{}','/models',500,300000,0,'是','未启用','Azure / Microsoft Foundry 托管的 OpenAI 模型。'),
('tpl_anthropic','Anthropic Claude','anthropic','Anthropic Messages','https://api.anthropic.com','Bearer Token','["Messages", "Chat", "Vision"]','{}','/v1/messages',500,200000,5,'是','未启用','Claude 系列长上下文、代码、智能体与企业任务模型。'),
('tpl_gemini','Google Gemini','gemini','Gemini','https://generativelanguage.googleapis.com/v1beta','API Key','["Chat", "Embeddings", "Images", "Audio", "Video", "Live"]','{}','/models',500,200000,6,'是','未启用','Google Gemini API；多模态、长上下文、图像、音频、视频与 Embedding。'),
('tpl_deepseek','DeepSeek','deepseek','OpenAI-compatible','https://api.deepseek.com','Bearer Token','["Chat", "Anthropic", "FIM"]','{}','/models',600,300000,4,'是','未启用','DeepSeek V4 系列，兼容 OpenAI 与 Anthropic 格式。'),
('tpl_qwen','Qwen / 阿里云百炼','qwen','OpenAI-compatible','https://dashscope.aliyuncs.com/compatible-mode/v1','Bearer Token','["Chat", "Responses", "Embeddings", "Rerank", "Images", "Audio", "Video"]','{}','/models',600,300000,9,'是','未启用','阿里云百炼 Model Studio / 通义千问模型。'),
('tpl_zhipu','Z.AI / 智谱 GLM','zhipu','OpenAI-compatible','https://open.bigmodel.cn/api/paas/v4','Bearer Token','["Chat", "Responses", "Embeddings", "Images", "Video", "Agents"]','{}','/models',600,300000,6,'是','未启用','Z.AI / 智谱 GLM 系列模型与智能体能力。'),
('tpl_volcengine_ark','火山方舟 / 豆包','volcengine_ark','OpenAI-compatible','https://ark.cn-beijing.volces.com/api/v3','Bearer Token','["Chat", "Responses", "Embeddings", "Images", "Video", "3D"]','{}','/models',600,300000,5,'是','未启用','火山方舟 Doubao / Seed / 多模态模型。'),
('tpl_baidu_qianfan','百度千帆','baidu_qianfan','OpenAI-compatible','https://qianfan.baidubce.com/v2','Bearer Token','["Chat", "Responses", "Embeddings", "Images"]','{}','/models',600,300000,4,'是','未启用','百度千帆 ERNIE 与托管模型服务。'),
('tpl_iflytek_spark','讯飞星火','iflytek_spark','OpenAI-compatible','https://spark-api-open.xf-yun.com/v1','Bearer Token','["Chat", "Batch"]','{}','/chat/completions',600,300000,5,'是','未启用','讯飞星火通用语言模型与批处理能力。'),
('tpl_tencent_hunyuan','腾讯混元 / TokenHub','tencent_hunyuan','OpenAI-compatible','https://api.hunyuan.cloud.tencent.com/v1','Bearer Token','["Chat", "Embeddings", "Images", "3D"]','{}','/models',600,300000,6,'是','未启用','腾讯混元与 TokenHub 模型入口。'),
('tpl_moonshot','Moonshot / Kimi','moonshot','OpenAI-compatible','https://api.moonshot.cn/v1','Bearer Token','["Chat", "Responses", "Vision"]','{}','/models',600,300000,2,'是','未启用','Moonshot Kimi 长上下文、多模态与代码模型。'),
('tpl_minimax','MiniMax','minimax','OpenAI-compatible','https://api.minimax.chat/v1','Bearer Token','["Chat", "Images", "Audio", "Video", "Music"]','{}','/models',600,300000,3,'是','未启用','MiniMax 语言、音频、视频、图像与音乐生成模型。'),
('tpl_siliconflow','SiliconFlow','siliconflow','OpenAI-compatible','https://api.siliconflow.cn/v1','Bearer Token','["Chat", "Embeddings", "Rerank", "Images"]','{}','/models',1000,500000,0,'是','未启用','硅基流动模型云；常用于国产开源模型托管。'),
('tpl_mistral','Mistral AI','mistral','OpenAI-compatible','https://api.mistral.ai/v1','Bearer Token','["Chat", "Embeddings", "Moderation"]','{}','/models',500,300000,2,'是','未启用','Mistral AI 官方模型 API。'),
('tpl_cohere','Cohere','cohere','Cohere','https://api.cohere.com/v2','Bearer Token','["Chat", "Embeddings", "Rerank"]','{}','/models',500,300000,3,'是','未启用','Cohere Command、Embed 与 Rerank 能力。'),
('tpl_groq','Groq','groq','OpenAI-compatible','https://api.groq.com/openai/v1','Bearer Token','["Chat"]','{}','/models',1000,500000,0,'是','未启用','Groq 高速推理平台。'),
('tpl_together','Together AI','together','OpenAI-compatible','https://api.together.xyz/v1','Bearer Token','["Chat", "Embeddings", "Images"]','{}','/models',1000,500000,0,'是','未启用','Together AI 开源模型托管平台。'),
('tpl_perplexity','Perplexity','perplexity','OpenAI-compatible','https://api.perplexity.ai','Bearer Token','["Chat", "Search", "Agents"]','{}','/models',500,300000,2,'是','未启用','Perplexity Sonar / Agent 搜索增强模型。'),
('tpl_xai','xAI / Grok','xai','OpenAI-compatible','https://api.x.ai/v1','Bearer Token','["Chat", "Images", "Audio"]','{}','/models',500,300000,1,'是','未启用','xAI Grok 系列模型。'),
('tpl_vllm','vLLM','vllm','OpenAI-compatible','http://localhost:8000/v1','None','["Chat", "Embeddings"]','{}','/models',1000,500000,1,'是','未启用','企业私有模型部署，OpenAI 兼容。'),
('tpl_ollama','Ollama','ollama','OpenAI-compatible','http://localhost:11434/v1','None','["Chat", "Embeddings"]','{}','/models',1000,500000,1,'是','未启用','本地与内网模型服务，OpenAI 兼容。'),
('tpl_custom','Custom Provider','custom','OpenAI-compatible',NULL,'Bearer Token','["Chat", "Responses", "Embeddings", "Rerank", "Images", "Audio"]','{}','/models',NULL,NULL,1,'否','未启用','自定义 OpenAI-compatible 供应商。')
ON CONFLICT (id) DO UPDATE SET
  provider_name = EXCLUDED.provider_name,
  provider_type = EXCLUDED.provider_type,
  protocol = EXCLUDED.protocol,
  default_api_base = EXCLUDED.default_api_base,
  auth_type = EXCLUDED.auth_type,
  supported_endpoints = EXCLUDED.supported_endpoints,
  error_mapping = EXCLUDED.error_mapping,
  health_check_path = EXCLUDED.health_check_path,
  default_rate_limit_rpm = EXCLUDED.default_rate_limit_rpm,
  default_rate_limit_tpm = EXCLUDED.default_rate_limit_tpm,
  model_template_count = EXCLUDED.model_template_count,
  built_in = EXCLUDED.built_in,
  description = EXCLUDED.description;

INSERT INTO model_capability_tag (id, name, description)
VALUES
('tag_text','文本生成','通用对话、问答、总结、写作'),
('tag_code','代码生成','代码补全、解释、重构、智能体编码'),
('tag_reasoning','推理','数学、逻辑、复杂规划、深度思考'),
('tag_agent','智能体','工具调用、长任务、Agent 工作流'),
('tag_long_context','长上下文','文档分析、合同审核、代码仓库理解'),
('tag_multimodal','多模态','图片、音频、视频理解或生成'),
('tag_vision','视觉理解','图像输入、OCR、视觉问答'),
('tag_image','图像生成','文生图、图像编辑'),
('tag_video','视频生成','文生视频、图生视频、视频编辑'),
('tag_audio','语音','ASR、TTS、实时语音、翻译'),
('tag_realtime','实时语音','低延迟实时对话或翻译'),
('tag_embedding','Embedding','向量检索、RAG'),
('tag_rerank','Rerank','检索排序'),
('tag_low_latency','低延迟','对响应速度敏感'),
('tag_low_cost','低成本','批量、客服、低价值任务'),
('tag_high_quality','高质量','高价值、复杂任务'),
('tag_cn','国产化','政企和国产化场景'),
('tag_private','私有部署','内网模型、私有 GPU 集群'),
('tag_search','搜索增强','联网搜索、引用和检索增强'),
('tag_tools','工具调用','Function calling / tool use')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description;

INSERT INTO model_template (id, provider_template_id, provider_name, provider_model_name, default_display_name, context_length, supported_endpoints, supports_streaming, supports_tools, capability_tags, default_cost_level, default_quality_level, compliance_tags, built_in, status)
VALUES
('mt_openai_gpt_5_5','tpl_openai','OpenAI','gpt-5.5','GPT-5.5',1000000,'["Responses", "Chat"]',true,true,'["文本生成", "代码生成", "长上下文", "多模态", "高质量", "工具调用"]','高','旗舰','["境外"]','是','可发布'),
('mt_openai_gpt_5_5_pro','tpl_openai','OpenAI','gpt-5.5-pro','GPT-5.5 Pro',1000000,'["Responses", "Chat"]',true,true,'["文本生成", "代码生成", "长上下文", "多模态", "旗舰", "工具调用"]','高','旗舰','["境外"]','是','观察'),
('mt_openai_gpt_5_4','tpl_openai','OpenAI','gpt-5.4','GPT-5.4',1000000,'["Responses", "Chat"]',true,true,'["文本生成", "代码生成", "长上下文", "多模态", "高质量"]','中','高质量','["境外"]','是','可发布'),
('mt_openai_gpt_5_4_mini','tpl_openai','OpenAI','gpt-5.4-mini','GPT-5.4 mini',400000,'["Responses", "Chat"]',true,true,'["文本生成", "代码生成", "多模态", "低成本"]','中','高质量','["境外"]','是','可发布'),
('mt_openai_gpt_5_4_nano','tpl_openai','OpenAI','gpt-5.4-nano','GPT-5.4 nano',400000,'["Responses", "Chat"]',true,true,'["文本生成", "低成本", "低延迟"]','低','普通','["境外"]','是','可发布'),
('mt_openai_embedding_3_large','tpl_openai','OpenAI','text-embedding-3-large','Text Embedding 3 Large',8192,'["Embeddings"]',false,false,'["Embedding"]','低','高质量','["境外"]','是','可发布'),
('mt_openai_embedding_3_small','tpl_openai','OpenAI','text-embedding-3-small','Text Embedding 3 Small',8192,'["Embeddings"]',false,false,'["Embedding", "低成本"]','低','普通','["境外"]','是','可发布'),
('mt_openai_gpt_image_2','tpl_openai','OpenAI','gpt-image-2','GPT Image 2',NULL,'["Images"]',false,false,'["图像生成", "多模态"]','高','高质量','["境外"]','是','可发布'),
('mt_openai_realtime_2_1','tpl_openai','OpenAI','gpt-realtime-2.1','GPT Realtime 2.1',NULL,'["Audio", "Realtime"]',true,true,'["实时语音", "多模态", "工具调用"]','高','高质量','["境外"]','是','可发布'),
('mt_claude_fable_5','tpl_anthropic','Anthropic Claude','claude-fable-5','Claude Fable 5',1000000,'["Messages", "Chat"]',true,true,'["文本生成", "代码生成", "长上下文", "多模态", "高质量", "工具调用"]','高','旗舰','["境外"]','是','可发布'),
('mt_claude_opus_4_8','tpl_anthropic','Anthropic Claude','claude-opus-4-8','Claude Opus 4.8',1000000,'["Messages", "Chat"]',true,true,'["文本生成", "代码生成", "长上下文", "多模态", "高质量", "工具调用"]','高','旗舰','["境外"]','是','可发布'),
('mt_claude_sonnet_5','tpl_anthropic','Anthropic Claude','claude-sonnet-5','Claude Sonnet 5',1000000,'["Messages", "Chat"]',true,true,'["文本生成", "代码生成", "长上下文", "多模态", "高质量"]','中','高质量','["境外"]','是','可发布'),
('mt_claude_haiku_4_5','tpl_anthropic','Anthropic Claude','claude-haiku-4-5','Claude Haiku 4.5',200000,'["Messages", "Chat"]',true,true,'["文本生成", "低延迟", "多模态"]','低','普通','["境外"]','是','可发布'),
('mt_claude_mythos_5','tpl_anthropic','Anthropic Claude','claude-mythos-5','Claude Mythos 5',1000000,'["Messages", "Chat"]',true,true,'["文本生成", "代码生成", "长上下文", "多模态", "高质量"]','高','旗舰','["境外"]','是','观察'),
('mt_gemini_3_5_flash','tpl_gemini','Google Gemini','gemini-3.5-flash','Gemini 3.5 Flash',1000000,'["Chat", "Responses"]',true,true,'["文本生成", "代码生成", "长上下文", "多模态", "低延迟"]','中','高质量','["境外"]','是','可发布'),
('mt_gemini_3_1_pro','tpl_gemini','Google Gemini','gemini-3.1-pro','Gemini 3.1 Pro',1000000,'["Chat", "Responses"]',true,true,'["文本生成", "代码生成", "长上下文", "多模态", "高质量"]','高','旗舰','["境外"]','是','可发布'),
('mt_gemini_3_1_flash_lite','tpl_gemini','Google Gemini','gemini-3.1-flash-lite','Gemini 3.1 Flash-Lite',1000000,'["Chat", "Responses"]',true,true,'["文本生成", "多模态", "低成本", "低延迟"]','低','普通','["境外"]','是','可发布'),
('mt_gemini_embedding_2','tpl_gemini','Google Gemini','gemini-embedding-2','Gemini Embedding 2',NULL,'["Embeddings"]',false,false,'["Embedding", "多模态"]','中','高质量','["境外"]','是','可发布'),
('mt_gemini_nano_banana_2','tpl_gemini','Google Gemini','nano-banana-2','Nano Banana 2',NULL,'["Images"]',false,false,'["图像生成", "多模态", "低成本"]','中','高质量','["境外"]','是','可发布'),
('mt_gemini_3_1_flash_live','tpl_gemini','Google Gemini','gemini-3.1-flash-live','Gemini 3.1 Flash Live',NULL,'["Audio", "Live"]',true,true,'["实时语音", "多模态", "低延迟"]','中','高质量','["境外"]','是','可发布'),
('mt_deepseek_v4_flash','tpl_deepseek','DeepSeek','deepseek-v4-flash','DeepSeek V4 Flash',1000000,'["Chat"]',true,true,'["文本生成", "代码生成", "长上下文", "低成本", "国产化", "工具调用"]','低','高质量','["国产化"]','是','可发布'),
('mt_deepseek_v4_pro','tpl_deepseek','DeepSeek','deepseek-v4-pro','DeepSeek V4 Pro',1000000,'["Chat"]',true,true,'["文本生成", "代码生成", "长上下文", "高质量", "国产化", "工具调用"]','中','旗舰','["国产化"]','是','可发布'),
('mt_deepseek_chat_legacy','tpl_deepseek','DeepSeek','deepseek-chat','DeepSeek Chat（兼容名）',1000000,'["Chat"]',true,true,'["文本生成", "国产化", "兼容"]','低','普通','["国产化"]','是','已废弃'),
('mt_deepseek_reasoner_legacy','tpl_deepseek','DeepSeek','deepseek-reasoner','DeepSeek Reasoner（兼容名）',1000000,'["Chat"]',true,true,'["推理", "国产化", "兼容"]','低','普通','["国产化"]','是','已废弃'),
('mt_qwen_3_7_max','tpl_qwen','Qwen / 阿里云百炼','qwen3.7-max','Qwen3.7 Max',NULL,'["Chat", "Responses"]',true,true,'["文本生成", "代码生成", "智能体", "高质量", "国产化"]','高','旗舰','["国产化"]','是','可发布'),
('mt_qwen_3_7_plus','tpl_qwen','Qwen / 阿里云百炼','qwen3.7-plus','Qwen3.7 Plus',NULL,'["Chat", "Responses", "Images"]',true,true,'["文本生成", "长上下文", "多模态", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_qwen_3_6_flash','tpl_qwen','Qwen / 阿里云百炼','qwen3.6-flash','Qwen3.6 Flash',NULL,'["Chat", "Responses"]',true,true,'["文本生成", "低成本", "低延迟", "国产化"]','低','普通','["国产化"]','是','可发布'),
('mt_qwen_3_5_omni_plus','tpl_qwen','Qwen / 阿里云百炼','qwen3.5-omni-plus','Qwen3.5 Omni Plus',NULL,'["Chat", "Audio", "Images", "Video"]',true,true,'["多模态", "文本生成", "视觉理解", "语音", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_qwen_3_5_omni_plus_realtime','tpl_qwen','Qwen / 阿里云百炼','qwen3.5-omni-plus-realtime','Qwen3.5 Omni Plus Realtime',NULL,'["Audio", "Realtime"]',true,true,'["实时语音", "多模态", "低延迟", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_qwen_text_embedding_v4','tpl_qwen','Qwen / 阿里云百炼','text-embedding-v4','Text Embedding V4',NULL,'["Embeddings"]',false,false,'["Embedding", "国产化"]','低','高质量','["国产化"]','是','可发布'),
('mt_qwen_3_rerank','tpl_qwen','Qwen / 阿里云百炼','qwen3-rerank','Qwen3 Rerank',NULL,'["Rerank"]',false,false,'["Rerank", "国产化"]','低','高质量','["国产化"]','是','可发布'),
('mt_qwen_image_2_pro','tpl_qwen','Qwen / 阿里云百炼','qwen-image-2.0-pro','Qwen Image 2.0 Pro',NULL,'["Images"]',false,false,'["图像生成", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_wan_2_7_image_pro','tpl_qwen','Qwen / 阿里云百炼','wan2.7-image-pro','Wan2.7 Image Pro',NULL,'["Images", "Video"]',false,false,'["图像生成", "视频生成", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_glm_5_2','tpl_zhipu','Z.AI / 智谱 GLM','glm-5.2','GLM-5.2',1000000,'["Chat", "Responses"]',true,true,'["文本生成", "代码生成", "长上下文", "智能体", "国产化", "高质量"]','高','旗舰','["国产化"]','是','可发布'),
('mt_glm_5','tpl_zhipu','Z.AI / 智谱 GLM','glm-5','GLM-5',NULL,'["Chat", "Responses"]',true,true,'["文本生成", "代码生成", "智能体", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_glm_5_turbo','tpl_zhipu','Z.AI / 智谱 GLM','glm-5-turbo','GLM-5 Turbo',NULL,'["Chat", "Responses"]',true,true,'["文本生成", "代码生成", "低延迟", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_glm_4_7','tpl_zhipu','Z.AI / 智谱 GLM','glm-4.7','GLM-4.7',200000,'["Chat", "Responses"]',true,true,'["文本生成", "代码生成", "智能体", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_glm_4_7_flash','tpl_zhipu','Z.AI / 智谱 GLM','glm-4.7-flash','GLM-4.7 Flash',200000,'["Chat", "Responses"]',true,true,'["文本生成", "低成本", "低延迟", "国产化"]','低','普通','["国产化"]','是','可发布'),
('mt_glm_4_6v','tpl_zhipu','Z.AI / 智谱 GLM','glm-4.6v','GLM-4.6V',128000,'["Chat", "Images"]',true,true,'["多模态", "视觉理解", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_doubao_seed_2_1_pro','tpl_volcengine_ark','火山方舟 / 豆包','doubao-seed-2.1-pro','Doubao Seed 2.1 Pro',NULL,'["Chat", "Responses", "Images"]',true,true,'["文本生成", "代码生成", "智能体", "多模态", "国产化"]','中','旗舰','["国产化"]','是','可发布'),
('mt_doubao_seed_2_0_lite','tpl_volcengine_ark','火山方舟 / 豆包','doubao-seed-2-0-lite-260428','Doubao Seed 2.0 Lite',NULL,'["Chat", "Responses"]',true,true,'["文本生成", "低成本", "低延迟", "国产化"]','低','普通','["国产化"]','是','可发布'),
('mt_doubao_seed_1_6_thinking','tpl_volcengine_ark','火山方舟 / 豆包','doubao-seed-1.6-thinking','Doubao Seed 1.6 Thinking',NULL,'["Chat", "Responses"]',true,true,'["推理", "文本生成", "国产化"]','中','高质量','["国产化"]','是','观察'),
('mt_doubao_seed_1_6_flash','tpl_volcengine_ark','火山方舟 / 豆包','doubao-seed-1.6-flash','Doubao Seed 1.6 Flash',NULL,'["Chat", "Responses"]',true,true,'["文本生成", "低成本", "低延迟", "国产化"]','低','普通','["国产化"]','是','观察'),
('mt_doubao_embedding_vision','tpl_volcengine_ark','火山方舟 / 豆包','doubao-embedding-vision-251215','Doubao Embedding Vision',NULL,'["Embeddings"]',false,false,'["Embedding", "多模态", "国产化"]','低','高质量','["国产化"]','是','可发布'),
('mt_baidu_ernie_5_0','tpl_baidu_qianfan','百度千帆','ernie-5.0','ERNIE 5.0',128000,'["Chat", "Responses"]',true,true,'["文本生成", "推理", "国产化"]','中','旗舰','["国产化"]','是','可发布'),
('mt_baidu_deepseek_v4_pro','tpl_baidu_qianfan','百度千帆','deepseek-v4-pro','DeepSeek V4 Pro（千帆托管）',1000000,'["Chat", "Responses"]',true,true,'["文本生成", "推理", "国产化"]','中','旗舰','["国产化"]','是','可发布'),
('mt_baidu_deepseek_v4_flash','tpl_baidu_qianfan','百度千帆','deepseek-v4-flash','DeepSeek V4 Flash（千帆托管）',1000000,'["Chat", "Responses"]',true,true,'["文本生成", "低成本", "国产化"]','低','高质量','["国产化"]','是','可发布'),
('mt_baidu_deepseek_v3_2','tpl_baidu_qianfan','百度千帆','deepseek-v3.2','DeepSeek V3.2（千帆托管）',128000,'["Chat", "Responses"]',true,true,'["文本生成", "国产化"]','低','高质量','["国产化"]','是','可发布'),
('mt_spark_x','tpl_iflytek_spark','讯飞星火','spark-x','Spark X1.5',NULL,'["Chat", "Batch"]',true,true,'["推理", "文本生成", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_spark_4_0_ultra','tpl_iflytek_spark','讯飞星火','4.0Ultra','Spark 4.0 Ultra',32768,'["Chat", "Batch"]',true,true,'["文本生成", "国产化", "高质量"]','中','高质量','["国产化"]','是','可发布'),
('mt_spark_generalv3_5','tpl_iflytek_spark','讯飞星火','generalv3.5','Spark Max',32000,'["Chat", "Batch"]',true,true,'["文本生成", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_spark_generalv3','tpl_iflytek_spark','讯飞星火','generalv3','Spark Pro',128000,'["Chat"]',true,true,'["文本生成", "国产化"]','低','普通','["国产化"]','是','可发布'),
('mt_spark_lite','tpl_iflytek_spark','讯飞星火','lite','Spark Lite',8000,'["Chat"]',true,false,'["文本生成", "低成本", "国产化"]','低','普通','["国产化"]','是','可发布'),
('mt_hunyuan_a13b','tpl_tencent_hunyuan','腾讯混元 / TokenHub','hunyuan-a13b','Hunyuan A13B',224000,'["Chat"]',true,true,'["文本生成", "推理", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_hunyuan_t1_latest','tpl_tencent_hunyuan','腾讯混元 / TokenHub','hunyuan-T1-latest','Hunyuan T1 Latest',32000,'["Chat"]',true,true,'["推理", "文本生成", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_hunyuan_turbos_latest','tpl_tencent_hunyuan','腾讯混元 / TokenHub','hunyuan-turbos-latest','Hunyuan TurboS Latest',32000,'["Chat"]',true,true,'["文本生成", "低延迟", "国产化"]','低','高质量','["国产化"]','是','可发布'),
('mt_hunyuan_2_0_think','tpl_tencent_hunyuan','腾讯混元 / TokenHub','hunyuan-2.0-think','Tencent HY 2.0 Think',128000,'["Chat"]',true,true,'["推理", "长上下文", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_hunyuan_2_0_instruct','tpl_tencent_hunyuan','腾讯混元 / TokenHub','hunyuan-2.0-instruct','Tencent HY 2.0 Instruct',128000,'["Chat"]',true,true,'["文本生成", "长上下文", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_hy_image_v3','tpl_tencent_hunyuan','腾讯混元 / TokenHub','hy-image-v3.0','HY-Image-V3.0',NULL,'["Images"]',false,false,'["图像生成", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_kimi_k2_6','tpl_moonshot','Moonshot / Kimi','kimi-k2.6','Kimi K2.6',NULL,'["Chat", "Responses", "Images"]',true,true,'["文本生成", "代码生成", "多模态", "智能体", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_kimi_k2_7_code','tpl_moonshot','Moonshot / Kimi','kimi-k2.7-code','Kimi K2.7 Code',NULL,'["Chat", "Responses", "Images"]',true,true,'["代码生成", "智能体", "多模态", "国产化"]','中','高质量','["国产化"]','是','可发布'),
('mt_minimax_m3','tpl_minimax','MiniMax','MiniMax-M3','MiniMax M3',NULL,'["Chat", "Images"]',true,true,'["文本生成", "多模态", "智能体", "代码生成"]','中','高质量','["国产化"]','是','可发布'),
('mt_minimax_m2_7','tpl_minimax','MiniMax','MiniMax-M2.7','MiniMax M2.7',NULL,'["Chat"]',true,true,'["文本生成", "代码生成", "智能体"]','中','高质量','["国产化"]','是','观察'),
('mt_minimax_m2_5','tpl_minimax','MiniMax','MiniMax-M2.5','MiniMax M2.5',NULL,'["Chat"]',true,true,'["文本生成", "智能体"]','低','普通','["国产化"]','是','观察'),
('mt_mistral_large_3','tpl_mistral','Mistral AI','mistral-large-3','Mistral Large 3',NULL,'["Chat"]',true,true,'["文本生成", "代码生成", "高质量"]','中','旗舰','["境外"]','是','可发布'),
('mt_mistral_small_3','tpl_mistral','Mistral AI','mistral-small-3','Mistral Small 3',NULL,'["Chat"]',true,true,'["文本生成", "低成本"]','低','普通','["境外"]','是','可发布'),
('mt_cohere_command_a_plus','tpl_cohere','Cohere','command-a-plus-05-2026','Command A+ 05-2026',128000,'["Chat"]',true,true,'["文本生成", "多模态", "企业级"]','中','高质量','["境外"]','是','可发布'),
('mt_cohere_embed_v4','tpl_cohere','Cohere','embed-v4.0','Cohere Embed v4.0',NULL,'["Embeddings"]',false,false,'["Embedding"]','低','高质量','["境外"]','是','可发布'),
('mt_cohere_rerank_v4','tpl_cohere','Cohere','rerank-v4.0','Cohere Rerank v4.0',NULL,'["Rerank"]',false,false,'["Rerank"]','低','高质量','["境外"]','是','可发布'),
('mt_xai_grok_4_5','tpl_xai','xAI / Grok','grok-4.5','Grok 4.5',NULL,'["Chat"]',true,true,'["文本生成", "代码生成", "智能体"]','中','高质量','["境外"]','是','可发布'),
('mt_perplexity_sonar','tpl_perplexity','Perplexity','sonar','Sonar',NULL,'["Chat", "Search"]',true,true,'["搜索增强", "文本生成"]','中','高质量','["境外"]','是','可发布'),
('mt_perplexity_sonar_pro','tpl_perplexity','Perplexity','sonar-pro','Sonar Pro',NULL,'["Chat", "Search"]',true,true,'["搜索增强", "高质量"]','中','高质量','["境外"]','是','可发布'),
('mt_private_openai_compatible','tpl_custom','Custom Provider','custom-openai-compatible','Custom OpenAI-compatible Model',NULL,'["Chat", "Embeddings", "Rerank"]',true,true,'["自定义", "私有部署"]','中','自定义','[]','是','观察'),
('mt_private_vllm','tpl_vllm','vLLM','local-model','vLLM Local Model',NULL,'["Chat", "Embeddings"]',true,true,'["私有部署", "自定义"]','低','自定义','["私有部署"]','是','观察'),
('mt_private_ollama','tpl_ollama','Ollama','local-ollama-model','Ollama Local Model',NULL,'["Chat", "Embeddings"]',true,false,'["私有部署", "自定义"]','低','自定义','["私有部署"]','是','观察')
ON CONFLICT (id) DO UPDATE SET
  provider_template_id = EXCLUDED.provider_template_id,
  provider_name = EXCLUDED.provider_name,
  provider_model_name = EXCLUDED.provider_model_name,
  default_display_name = EXCLUDED.default_display_name,
  context_length = EXCLUDED.context_length,
  supported_endpoints = EXCLUDED.supported_endpoints,
  supports_streaming = EXCLUDED.supports_streaming,
  supports_tools = EXCLUDED.supports_tools,
  capability_tags = EXCLUDED.capability_tags,
  default_cost_level = EXCLUDED.default_cost_level,
  default_quality_level = EXCLUDED.default_quality_level,
  compliance_tags = EXCLUDED.compliance_tags,
  built_in = EXCLUDED.built_in,
  status = EXCLUDED.status;

INSERT INTO platform_model (id, platform_model_name, display_name, model_template_ids, actual_models, route_policy, price_policy, visibility_scope, approval_required, status)
VALUES
('pm_chat_enterprise','chat-enterprise','企业通用对话模型','["mt_qwen_3_7_plus", "mt_deepseek_v4_flash", "mt_gemini_3_5_flash"]','["qwen3.7-plus", "deepseek-v4-flash", "gemini-3.5-flash"]','质量 / 成本均衡 + Fallback','标准价','全部租户',false,'已发布'),
('pm_chat_reasoning','chat-reasoning','复杂推理模型','["mt_deepseek_v4_pro", "mt_glm_5_2", "mt_claude_fable_5"]','["deepseek-v4-pro", "glm-5.2", "claude-fable-5"]','高质量优先 + Fallback','高质量价','企业版',true,'灰度'),
('pm_chat_lowcost','chat-lowcost','低成本对话模型','["mt_deepseek_v4_flash", "mt_qwen_3_6_flash", "mt_gemini_3_1_flash_lite"]','["deepseek-v4-flash", "qwen3.6-flash", "gemini-3.1-flash-lite"]','成本优先','低成本价','全部租户',false,'已发布'),
('pm_code_agent','code-agent','代码与智能体模型','["mt_openai_gpt_5_5", "mt_claude_opus_4_8", "mt_glm_5_2", "mt_qwen_3_7_max"]','["gpt-5.5", "claude-opus-4-8", "glm-5.2", "qwen3.7-max"]','代码质量优先','高质量价','企业版',true,'灰度'),
('pm_embedding_standard','embedding-standard','默认向量模型','["mt_qwen_text_embedding_v4", "mt_gemini_embedding_2", "mt_openai_embedding_3_large"]','["text-embedding-v4", "gemini-embedding-2", "text-embedding-3-large"]','合规优先 / 国内优先','标准价','全部租户',false,'已发布'),
('pm_rerank_standard','rerank-standard','默认重排模型','["mt_qwen_3_rerank", "mt_cohere_rerank_v4"]','["qwen3-rerank", "rerank-v4.0"]','国内优先 / 质量兜底','标准价','企业版',false,'草稿'),
('pm_image_generation','image-generation','图像生成模型','["mt_qwen_image_2_pro", "mt_gemini_nano_banana_2", "mt_openai_gpt_image_2"]','["qwen-image-2.0-pro", "nano-banana-2", "gpt-image-2"]','可用性优先','图像生成价','企业版',true,'草稿'),
('pm_realtime_voice','realtime-voice','实时语音模型','["mt_qwen_3_5_omni_plus_realtime", "mt_gemini_3_1_flash_live", "mt_openai_realtime_2_1"]','["qwen3.5-omni-plus-realtime", "gemini-3.1-flash-live", "gpt-realtime-2.1"]','低延迟优先','实时语音价','企业版',true,'草稿')
ON CONFLICT (id) DO UPDATE SET
  platform_model_name = EXCLUDED.platform_model_name,
  display_name = EXCLUDED.display_name,
  model_template_ids = EXCLUDED.model_template_ids,
  actual_models = EXCLUDED.actual_models,
  route_policy = EXCLUDED.route_policy,
  price_policy = EXCLUDED.price_policy,
  visibility_scope = EXCLUDED.visibility_scope,
  approval_required = EXCLUDED.approval_required,
  status = EXCLUDED.status;

-- Mark old compatibility templates from earlier seed data.
UPDATE model_template SET status = '已废弃', default_display_name = default_display_name || '（旧兼容）'
WHERE id IN ('mt_deepseek_chat','mt_deepseek_reasoner') AND status <> '已废弃' AND default_display_name NOT LIKE '%旧兼容%';

UPDATE model_template SET status = '观察'
WHERE id IN ('mt_qwen_plus','mt_qwen_max','mt_gpt_4o_mini','mt_text_embedding_v3','mt_qwen_rerank') AND status = '可发布';

UPDATE provider_template p
SET model_template_count = c.cnt
FROM (
  SELECT provider_template_id, COUNT(*)::int AS cnt
  FROM model_template
  WHERE provider_template_id IS NOT NULL
  GROUP BY provider_template_id
) c
WHERE p.id = c.provider_template_id;
