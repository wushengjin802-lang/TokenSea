# 国内模型供应商适配模板说明

本项目不复制 New API 代码。本方案只参考其“渠道化 / 供应商化 / 模型价格模板化”的产品思路，采用 TokenSea 自研数据结构：

- provider_template：供应商模板
- provider_instance：供应商实例
- model_template：供应商真实模型模板
- platform_model：业务侧调用的平台模型别名
- model_price：价格版本
- route_policy：路由策略

## 已内置的国内供应商模板

| provider_type | 默认 Base URL | 模型示例 |
|---|---|---|
| deepseek | https://api.deepseek.com | deepseek-v4-flash, deepseek-v4-pro |
| qwen | https://dashscope.aliyuncs.com/compatible-mode/v1 | qwen3.7-max, qwen3.7-plus, qwen3.6-flash, text-embedding-v4, qwen3-rerank |
| zhipu | https://open.bigmodel.cn/api/paas/v4 | glm-5.2, glm-5, glm-5-turbo, glm-4.7 |
| volcengine_ark | https://ark.cn-beijing.volces.com/api/v3 | doubao-seed-2.1-pro, doubao-seed-2-0-lite-260428 |
| baidu_qianfan | https://qianfan.baidubce.com/v2 | ernie-5.0, deepseek-v4-pro, deepseek-v4-flash |
| iflytek_spark | https://spark-api-open.xf-yun.com/v1 | spark-x, 4.0Ultra, generalv3.5, generalv3, lite |
| tencent_hunyuan | https://api.hunyuan.cloud.tencent.com/v1 | hunyuan-a13b, hunyuan-T1-latest, hunyuan-turbos-latest |
| moonshot | https://api.moonshot.cn/v1 | kimi-k2.6, kimi-k2.7-code |
| minimax | https://api.minimax.chat/v1 | MiniMax-M3, MiniMax-M2.7 |
| siliconflow | https://api.siliconflow.cn/v1 | 由供应商控制台选择托管模型 |
| vllm | http://localhost:8000/v1 | 本地私有模型 |
| ollama | http://localhost:11434/v1 | 本地私有模型 |

## 适配原则

1. 不在代码中写死供应商密钥。
2. 供应商密钥进入 provider_secret，并加密保存。
3. provider_type 只决定默认模板，不决定业务路由。
4. 价格表必须版本化。
5. 健康检查与错误码映射由 TokenSea 侧维护。
6. 生产启用前必须以供应商官方控制台和合同开通情况为准。
