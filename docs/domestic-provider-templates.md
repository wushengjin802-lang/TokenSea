# 国内模型供应商适配模板说明

本项目不复制 New API 代码。本方案只参考其“渠道化/供应商化/模型价格模板化”的产品思路，采用 TokenSea 自研数据结构：

- provider：供应商
- model：平台模型资产
- model_deployment：具体部署或渠道
- model_price：价格版本
- route_policy：路由策略

国内模型建议按 OpenAI-compatible 优先适配：

| provider_type | default_base_url | 模型示例 |
|---|---|---|
| deepseek | https://api.deepseek.com | deepseek-chat, deepseek-reasoner |
| qwen | https://dashscope.aliyuncs.com/compatible-mode/v1 | qwen-plus, qwen-max |
| zhipu | https://open.bigmodel.cn/api/paas/v4 | glm-4-plus |
| volcengine | https://ark.cn-beijing.volces.com/api/v3 | doubao-* |
| baidu | https://qianfan.baidubce.com/v2 | ernie-* |
| xunfei | 由企业账号配置 | spark-* |
| tencent | 由企业账号配置 | hunyuan-* |

适配原则：

1. 不在代码中写死供应商密钥。
2. 供应商密钥进入 provider_secret，加密保存。
3. provider_type 只决定默认模板，不决定业务路由。
4. 价格表必须版本化。
5. 健康检查与错误码映射在 TokenSea 侧维护。
