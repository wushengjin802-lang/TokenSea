# 供应商与模型内置目录更新记录

更新时间：2026-07-09

## 更新原则

- 内置供应商和模型属于系统模板，不是客户业务假数据。
- TokenSea 只维护模型接入模板、能力标签、端点类型和默认路由候选；真实 API Key、价格、可用区域、模型开通状态由管理员在供应商实例和价格管理中配置。
- 不复制 New API 代码，仅参考“供应商渠道化、模型模板化、价格模板化”的产品思路。
- 对 OpenAI、Anthropic、Google、DeepSeek、Qwen、Z.AI、百度、腾讯、讯飞、火山方舟、Moonshot、MiniMax 等供应商进行了模板补充。
- 模型目录保留部分兼容模型，但对已被官方明确迁移或即将废弃的模型标记为“已废弃”或“观察”。

## 新增供应商模板

| 供应商 | provider_type | 模型模板数 | 说明 |
|---|---:|---:|---|
| OpenAI | `openai` | 9 | OpenAI 官方 API；文本、视觉、音频、图像与向量模型。 |
| Azure OpenAI | `azure_openai` | 0 | Azure / Microsoft Foundry 托管的 OpenAI 模型。 |
| Anthropic Claude | `anthropic` | 5 | Claude 系列长上下文、代码、智能体与企业任务模型。 |
| Google Gemini | `gemini` | 6 | Google Gemini API；多模态、长上下文、图像、音频、视频与 Embedding。 |
| DeepSeek | `deepseek` | 4 | DeepSeek V4 系列，兼容 OpenAI 与 Anthropic 格式。 |
| Qwen / 阿里云百炼 | `qwen` | 9 | 阿里云百炼 Model Studio / 通义千问模型。 |
| Z.AI / 智谱 GLM | `zhipu` | 6 | Z.AI / 智谱 GLM 系列模型与智能体能力。 |
| 火山方舟 / 豆包 | `volcengine_ark` | 5 | 火山方舟 Doubao / Seed / 多模态模型。 |
| 百度千帆 | `baidu_qianfan` | 4 | 百度千帆 ERNIE 与托管模型服务。 |
| 讯飞星火 | `iflytek_spark` | 5 | 讯飞星火通用语言模型与批处理能力。 |
| 腾讯混元 / TokenHub | `tencent_hunyuan` | 6 | 腾讯混元与 TokenHub 模型入口。 |
| Moonshot / Kimi | `moonshot` | 2 | Moonshot Kimi 长上下文、多模态与代码模型。 |
| MiniMax | `minimax` | 3 | MiniMax 语言、音频、视频、图像与音乐生成模型。 |
| SiliconFlow | `siliconflow` | 0 | 硅基流动模型云；常用于国产开源模型托管。 |
| Mistral AI | `mistral` | 2 | Mistral AI 官方模型 API。 |
| Cohere | `cohere` | 3 | Cohere Command、Embed 与 Rerank 能力。 |
| Groq | `groq` | 0 | Groq 高速推理平台。 |
| Together AI | `together` | 0 | Together AI 开源模型托管平台。 |
| Perplexity | `perplexity` | 2 | Perplexity Sonar / Agent 搜索增强模型。 |
| xAI / Grok | `xai` | 1 | xAI Grok 系列模型。 |
| vLLM | `vllm` | 1 | 企业私有模型部署，OpenAI 兼容。 |
| Ollama | `ollama` | 1 | 本地与内网模型服务，OpenAI 兼容。 |
| Custom Provider | `custom` | 1 | 自定义 OpenAI-compatible 供应商。 |


## 重点新增模型

| 供应商 | 模型 |
|---|---|
| OpenAI | `gpt-5.5`, `gpt-5.5-pro`, `gpt-5.4`, `gpt-5.4-mini`, `gpt-5.4-nano`, `text-embedding-3-large`, `text-embedding-3-small`, `gpt-image-2` |
| Anthropic Claude | `claude-fable-5`, `claude-opus-4-8`, `claude-sonnet-5`, `claude-haiku-4-5`, `claude-mythos-5` |
| Google Gemini | `gemini-3.5-flash`, `gemini-3.1-pro`, `gemini-3.1-flash-lite`, `gemini-embedding-2`, `nano-banana-2`, `gemini-3.1-flash-live` |
| DeepSeek | `deepseek-v4-flash`, `deepseek-v4-pro`, `deepseek-chat`, `deepseek-reasoner` |
| Qwen / 阿里云百炼 | `qwen3.7-max`, `qwen3.7-plus`, `qwen3.6-flash`, `qwen3.5-omni-plus`, `qwen3.5-omni-plus-realtime`, `text-embedding-v4`, `qwen3-rerank`, `qwen-image-2.0-pro` |
| Z.AI / 智谱 GLM | `glm-5.2`, `glm-5`, `glm-5-turbo`, `glm-4.7`, `glm-4.7-flash`, `glm-4.6v` |
| 火山方舟 / 豆包 | `doubao-seed-2.1-pro`, `doubao-seed-2-0-lite-260428`, `doubao-seed-1.6-thinking`, `doubao-seed-1.6-flash`, `doubao-embedding-vision-251215` |
| 百度千帆 | `ernie-5.0`, `deepseek-v4-pro`, `deepseek-v4-flash`, `deepseek-v3.2` |
| 讯飞星火 | `spark-x`, `4.0Ultra`, `generalv3.5`, `generalv3`, `lite` |
| 腾讯混元 / TokenHub | `hunyuan-a13b`, `hunyuan-T1-latest`, `hunyuan-turbos-latest`, `hunyuan-2.0-think`, `hunyuan-2.0-instruct`, `hy-image-v3.0` |
| Moonshot / Kimi | `kimi-k2.6`, `kimi-k2.7-code` |
| MiniMax | `MiniMax-M3`, `MiniMax-M2.7`, `MiniMax-M2.5` |
| Mistral AI | `mistral-large-3`, `mistral-small-3` |
| Cohere | `command-a-plus-05-2026`, `embed-v4.0`, `rerank-v4.0` |
| Perplexity | `sonar`, `sonar-pro` |
| xAI / Grok | `grok-4.5` |
| vLLM | `local-model` |
| Ollama | `local-ollama-model` |
| Custom Provider | `custom-openai-compatible` |


## 运行说明

新数据库会通过 `V2__model_asset_governance.sql` 直接初始化最新模板；已有数据库会通过 `V4__refresh_provider_model_catalog.sql` 增量刷新模板。刷新不会删除客户自定义模板、供应商实例、API Key 或价格配置。

## 注意事项

1. 模型名称更新频繁，生产环境上线前必须由运维或平台管理员在供应商控制台复核。
2. 供应商价格、模型上下文、区域、限流和可用端点可能随账号、区域或合同变化，TokenSea 内置值仅作模板参考。
3. DeepSeek 旧模型名 `deepseek-chat` 与 `deepseek-reasoner` 已保留为兼容模板，并标记为“已废弃”。
4. 平台模型优先映射到较新的模型模板，例如 `chat-enterprise`、`chat-reasoning`、`embedding-standard`。
