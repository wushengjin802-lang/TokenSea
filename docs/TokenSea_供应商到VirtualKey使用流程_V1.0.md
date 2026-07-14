# TokenSea DeepSeek-v4-pro 从供应商配置到 Virtual Key 使用全流程

> 版本：V1.0  
> 日期：2026-07-13  
> 适用对象：平台管理员、模型资产管理员、租户管理员、应用负责人、开发者  
> 示例模型：`deepseek-v4-pro`  
> 说明：本文以 `deepseek-v4-pro` 为示例说明 TokenSea 的完整业务闭环。实际模型是否可用，以企业接入的 DeepSeek 账号通过模型发现接口返回结果为准。

---

## 1. 总体流程

完整流程可以概括为：

```text
平台管理员配置 DeepSeek 供应商
→ 创建 DeepSeek 供应商渠道
→ 托管 DeepSeek 官方 API Key
→ 执行连接测试
→ 自动发现 deepseek-v4-pro
→ 匹配公共模型参考库
→ 执行能力探测
→ 审核模型部署
→ 配置渠道实际成本
→ 创建企业服务模型
→ 配置路由策略
→ 发布企业服务模型
→ 创建租户、项目、应用
→ 创建 TokenSea Virtual Key
→ 开发者使用 TokenSea Gateway 调用模型
→ TokenSea 记录用量、成本、日志、审计和预算
```

TokenSea 的核心设计是：

```text
业务用户只拿 TokenSea Virtual Key
业务用户不接触 DeepSeek 官方 Key
业务系统只调用 TokenSea Gateway
TokenSea 在后台代替业务系统调用 DeepSeek
```

---

## 2. 角色分工

| 角色 | 主要职责 |
|---|---|
| 平台管理员 | 配置供应商、渠道、模型、路由、租户、Key 和全局策略 |
| 模型资产管理员 | 负责模型发现、能力探测、部署审核和服务模型发布 |
| 成本管理员 | 配置价格版本、预算规则、成本单和供应商对账 |
| 租户管理员 | 管理本租户项目、应用、成员、预算和 Key |
| 应用负责人 | 为具体业务应用申请或管理 Virtual Key |
| 开发者 | 使用 TokenSea Gateway、Virtual Key 和服务模型名完成接入 |
| 安全审计员 | 查看审计、敏感访问、Key 操作、模型发布和预算变更记录 |

---

## 3. 步骤一：启用 DeepSeek 供应商模板

进入菜单：

```text
模型资产 → 供应商模板
```

选择内置模板：

```text
DeepSeek
```

执行操作：

```text
启用为渠道
```

填写渠道基础信息：

| 字段 | 示例值 | 说明 |
|---|---|---|
| 渠道名称 | DeepSeek 生产账号 | 企业内部可识别的渠道名称 |
| 供应商模板 | DeepSeek | 来源于系统内置供应商模板 |
| API 地址 | https://api.deepseek.com | DeepSeek 官方或企业代理后的 API Base |
| 环境 | 生产 | 可选生产、测试、开发等 |
| 区域 | 中国 | 按企业部署和合规要求填写 |
| 负责人 | 平台管理员 | 渠道责任人 |

注意：供应商模板只是连接规范，不保存企业真实 Key，也不代表该供应商已经被企业实际采购。

---

## 4. 步骤二：托管 DeepSeek 官方 API Key

进入菜单：

```text
模型资产 → 供应商渠道 → DeepSeek 生产账号
```

执行操作：

```text
托管或轮换密钥
```

填写内容：

| 字段 | 示例值 | 说明 |
|---|---|---|
| 密钥名称 | deepseek-prod-key | 企业内部密钥标识 |
| 密钥值 | DeepSeek 官方 API Key | 只输入一次，后续页面不明文展示 |

托管后，TokenSea 应当只保存密文或密钥引用：

```text
provider_secret / secret_ref
```

业务用户不会看到 DeepSeek 官方 Key，也不能直接使用 DeepSeek 官方 Key。

---

## 5. 步骤三：执行连接测试

进入菜单：

```text
模型资产 → 供应商渠道
```

找到：

```text
DeepSeek 生产账号
```

执行操作：

```text
连接测试
```

连接测试应验证：

| 检查项 | 说明 |
|---|---|
| API Base 可访问 | 网络、DNS、出口代理、端口是否可用 |
| Key 有效性 | DeepSeek 官方 Key 是否有效 |
| 鉴权成功 | Authorization Header 是否正确 |
| Chat 最小请求 | 能否完成最小对话请求 |
| Streaming 支持 | 如启用流式响应，需要验证流式能力 |
| 出口安全 | 是否符合企业 egress allowlist 和 SSRF 防护规则 |

测试通过后，渠道状态可进入：

```text
已启用 / 健康
```

如果失败，需要检查：

```text
DeepSeek Key 是否正确
API Base 是否正确
出口白名单是否包含 api.deepseek.com
企业网络是否允许访问 DeepSeek
供应商账号是否开通对应模型
```

---

## 6. 步骤四：自动发现 deepseek-v4-pro

进入菜单：

```text
模型资产 → 供应商渠道 → DeepSeek 生产账号
```

执行操作：

```text
发现模型
```

TokenSea 会尝试调用 DeepSeek 的模型列表接口，或兼容 OpenAI 的：

```text
/v1/models
```

如果账号可用模型中包含 `deepseek-v4-pro`，系统会生成一条模型部署记录。

示例：

| 字段 | 示例值 |
|---|---|
| 渠道 | DeepSeek 生产账号 |
| 渠道模型名 | deepseek-v4-pro |
| 展示名称 | DeepSeek V4 Pro |
| 审核状态 | 待审核 |
| 路由资格 | 不可路由 |
| 首次发现时间 | 系统自动记录 |
| 最后发现时间 | 系统自动记录 |

重要规则：

```text
模型被发现 ≠ 业务已经可用
```

发现只说明该渠道账号中存在候选模型，还需要经过审核、能力探测、价格配置和服务模型发布。

---

## 7. 步骤五：匹配公共模型参考库

进入菜单：

```text
模型资产 → 公共模型参考库
```

TokenSea 会尝试将渠道发现到的 `deepseek-v4-pro` 与公共模型参考库进行匹配。

可能补全字段：

| 字段 | 示例 |
|---|---|
| 供应商 | DeepSeek |
| 模型家族 | DeepSeek V4 |
| 能力声明 | Chat、Reasoning、Streaming、JSON 等 |
| 上下文窗口 | 以官方或可信来源为准 |
| 来源类型 | 供应商接口 / 公共参考来源 / 文件导入 / 人工确认 |
| 来源依据 | 官方 API、官方文档、内部确认记录等 |
| 可信度 | 0.8 / 0.9 / 1.0 |

公共模型参考库的定位是：

```text
用于参考、匹配、补全和变更发现
不直接参与生产路由
不代表企业账号一定可调用该模型
```

---

## 8. 步骤六：执行能力探测

进入菜单：

```text
模型资产 → 能力验证
```

选择：

```text
DeepSeek 生产账号 / deepseek-v4-pro
```

执行能力探测，例如：

| 探测能力 | 说明 |
|---|---|
| Chat | 基础对话能力 |
| Streaming | 流式响应能力 |
| JSON | 结构化输出能力 |
| Tools / Function Calling | 工具调用能力，如供应商支持 |
| Reasoning | 推理能力，如模型支持 |
| Embedding | 仅当该模型支持向量能力时验证 |

探测结果示例：

| 能力 | 结果 |
|---|---|
| Chat | 通过 |
| Streaming | 通过 |
| JSON | 部分通过 |
| Tools | 未验证 |
| Embedding | 不适用 |

TokenSea 应区分：

```text
声明支持
自动验证通过
人工确认
验证失败
```

生产路由应优先使用已经验证通过的能力，而不是只依赖公共文档声明。

---

## 9. 步骤七：审核模型部署

进入菜单：

```text
模型资产 → 模型部署
```

找到：

```text
DeepSeek 生产账号 / deepseek-v4-pro
```

审核内容包括：

```text
模型名称是否正确
渠道是否正确
能力探测是否通过
上下文窗口是否可信
是否符合企业合规要求
是否允许进入生产路由
是否存在 Deprecated / Retired / 消失风险
```

审核通过后，模型部署状态应变为：

```text
审核状态：已通过
路由资格：可路由
```

如果审核不通过，应保留原因，例如：

```text
能力验证失败
价格未配置
合规属性未确认
账号未实际开通该模型
```

---

## 10. 步骤八：配置渠道实际成本

进入菜单：

```text
模型资产 → 成本价格
```

新增价格版本：

| 字段 | 示例值 | 说明 |
|---|---|---|
| 价格层级 | 渠道实际成本 | 参与正式成本计算 |
| 归属对象 | DeepSeek 生产账号 / deepseek-v4-pro | 绑定具体渠道模型部署 |
| 币种 | CNY 或 USD | 以合同或账单为准 |
| 输入单价 | 按实际价格填写 | 通常按 1K tokens 或 1M tokens 换算 |
| 输出单价 | 按实际价格填写 | 通常输出单价高于输入单价 |
| 来源类型 | 合同 / 供应商账单 / 官方价格 / 人工确认 | 标明来源 |
| 来源依据 | 合同编号、账单编号、价格页记录等 | 便于审计 |
| 生效时间 | 例如 2026-07-01 | 用于请求级成本快照 |

价格版本需要经过：

```text
创建草稿
→ 提交审批
→ 审批通过
→ 激活生效
```

关键规则：

```text
公共参考价格不能自动覆盖渠道实际成本
已生效价格不能原地修改
调价必须创建新价格版本
历史请求成本不因后续价格变化而改变
```

---

## 11. 步骤九：创建企业服务模型

业务系统不建议直接调用供应商模型 ID，而应调用 TokenSea 发布的企业服务模型名。

进入菜单：

```text
模型资产 → 企业服务模型
```

新建服务模型。

方案一：直接沿用供应商模型名：

| 字段 | 示例值 |
|---|---|
| 服务模型名 | deepseek-v4-pro |
| 展示名称 | DeepSeek V4 Pro |
| 供应商渠道 | DeepSeek 生产账号 |
| 已审核部署模型 | deepseek-v4-pro |
| 路由策略 | DeepSeek Pro 默认路由 |
| 价格版本 | deepseek-v4-pro 渠道实际成本 |
| 可见范围 | 指定租户或全部内部租户 |
| 是否需要审批 | 是 / 否 |

方案二：使用业务稳定别名：

| 服务模型名 | 适用场景 |
|---|---|
| chat-pro | 高质量通用对话 |
| reasoning-pro | 推理类任务 |
| code-pro | 代码生成与分析 |
| deepseek-pro | DeepSeek 专用服务模型 |

推荐使用业务稳定别名，例如：

```text
chat-pro
reasoning-pro
code-pro
```

这样后续底层模型从 `deepseek-v4-pro` 切换到其他模型时，业务系统无需修改代码。

---

## 12. 步骤十：配置路由策略

进入菜单：

```text
路由与观测 → Fallback 与负载均衡
```

配置服务模型的路由。

MVP 简单路由示例：

```text
deepseek-v4-pro → DeepSeek 生产账号 / deepseek-v4-pro
```

完整路由配置示例：

| 字段 | 示例值 |
|---|---|
| 服务模型 | deepseek-v4-pro |
| 主路由 | DeepSeek 生产账号 / deepseek-v4-pro |
| Fallback | 可选，如 Qwen、GLM、OpenAI 同能力模型 |
| 最大重试次数 | 1 或 2 |
| 超时时间 | 60 秒 |
| Fallback 条件 | 超时、429、500、502、503、504 |
| 负载均衡 | 单渠道可不配置，多渠道按权重配置 |

路由候选应满足：

```text
部署已审核
部署具备可路由资格
能力探测通过
价格版本已生效
渠道健康
符合租户和合规范围
```

---

## 13. 步骤十一：发布企业服务模型

企业服务模型发布流程：

```text
草稿
→ 提交审批
→ 审批通过
→ 发布
```

发布后，业务方才能在以下位置看到该模型：

```text
开发者中心 → 可访问服务模型
开发者中心 → Playground
Gateway /v1/models
```

此时业务可用的模型名可能是：

```text
deepseek-v4-pro
```

也可能是企业定义的稳定别名：

```text
chat-pro
reasoning-pro
code-pro
```

---

## 14. 步骤十二：创建租户、项目和应用

进入菜单：

```text
租户体系 → 租户
```

创建或启用租户：

| 字段 | 示例值 |
|---|---|
| 租户名称 | 研发中心 |
| 租户类型 | 内部租户 |
| 模型范围 | deepseek-v4-pro 或 chat-pro |
| 月预算 | 10000 元 |
| 状态 | 启用 |

进入菜单：

```text
租户体系 → 项目
```

创建项目：

| 字段 | 示例值 |
|---|---|
| 项目名称 | 智能客服项目 |
| 所属租户 | 研发中心 |
| 负责人 | 项目负责人 |
| 月预算 | 3000 元 |

进入菜单：

```text
租户体系 → 应用
```

创建应用：

| 字段 | 示例值 |
|---|---|
| 应用名称 | 客服机器人后端 |
| 所属租户 | 研发中心 |
| 所属项目 | 智能客服项目 |
| 环境 | 生产 |
| 负责人 | 应用负责人 |

租户、项目、应用用于后续：

```text
权限范围
Key 归属
用量归因
预算控制
成本统计
审计追踪
```

---

## 15. 步骤十三：创建 TokenSea Virtual Key

进入菜单：

```text
访问治理 → Virtual Key
```

创建 Key：

| 字段 | 示例值 | 说明 |
|---|---|---|
| Key 名称 | 客服机器人生产 Key | 便于管理 |
| 所属租户 | 研发中心 | 必填 |
| 所属项目 | 智能客服项目 | 推荐填写 |
| 所属应用 | 客服机器人后端 | 推荐填写 |
| 可访问模型 | deepseek-v4-pro / chat-pro | 不能超过租户模型范围 |
| RPM 限制 | 60 | 每分钟请求数 |
| TPM 限制 | 100000 | 每分钟 Token 数 |
| QPS 限制 | 5 | 每秒请求数 |
| 月预算 | 3000 元 | 可选，按预算策略控制 |
| IP 白名单 | 业务服务器出口 IP | 生产建议必填 |
| 有效期 | 1 年 | 到期后自动失效 |
| 状态 | 启用 | 允许调用 |

高风险场景建议进入审批：

```text
生产环境 Key
高预算 Key
无 IP 白名单 Key
可访问高成本模型的 Key
跨租户或跨项目范围 Key
```

审批通过后，系统生成 TokenSea Virtual Key，例如：

```text
sk-ts-xxxxxxxxxxxxxxxx
```

关键规则：

```text
TokenSea Virtual Key 只展示一次
业务用户必须复制并妥善保存
平台不再明文展示该 Key
```

---

## 16. 步骤十四：开发者使用 TokenSea Gateway

开发者只需要三个信息：

| 信息 | 示例 |
|---|---|
| API Base | http://localhost:39212/v1 |
| API Key | sk-ts-xxxxxxxxxxxxxxxx |
| Model | deepseek-v4-pro 或 chat-pro |

### 16.1 Python 示例

```python
from openai import OpenAI

client = OpenAI(
    api_key="sk-ts-xxxxxxxxxxxxxxxx",
    base_url="http://localhost:39212/v1"
)

resp = client.chat.completions.create(
    model="deepseek-v4-pro",
    messages=[
        {"role": "user", "content": "请介绍 TokenSea 的作用"}
    ]
)

print(resp.choices[0].message.content)
```

如果企业发布的是稳定别名，则使用：

```python
resp = client.chat.completions.create(
    model="chat-pro",
    messages=[
        {"role": "user", "content": "请介绍 TokenSea 的作用"}
    ]
)
```

### 16.2 curl 示例

```bash
curl http://localhost:39212/v1/chat/completions \
  -H "Authorization: Bearer sk-ts-xxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-v4-pro",
    "messages": [
      {"role": "user", "content": "请介绍 TokenSea 的作用"}
    ]
  }'
```

---

## 17. 调用时 TokenSea 内部链路

用户请求进入 TokenSea Gateway 后，内部处理链路如下：

```text
接收用户请求
→ 校验 TokenSea Virtual Key
→ 解析租户、项目、应用和 Key
→ 校验 Key 状态、有效期和 IP 白名单
→ 校验模型访问范围
→ 执行预算预检查
→ 执行 RPM / TPM / QPS 限流
→ 解析企业服务模型 deepseek-v4-pro 或 chat-pro
→ 根据路由策略选择 DeepSeek 生产账号 / deepseek-v4-pro
→ 获取托管的 DeepSeek Key 或 secret_ref
→ 转发请求到 DeepSeek
→ 接收 DeepSeek 响应
→ 记录 request_attempt
→ 记录 usage_record
→ 生成 usage_cost_snapshot
→ 更新预算消耗
→ 写入审计和指标
→ 返回响应给业务用户
```

---

## 18. 管理员可查看的数据

调用完成后，平台管理员可在调用日志中看到：

| 字段 | 说明 |
|---|---|
| request_id | 请求唯一标识 |
| 租户 | 研发中心 |
| 项目 | 智能客服项目 |
| 应用 | 客服机器人后端 |
| Virtual Key | 客服机器人生产 Key |
| 服务模型 | deepseek-v4-pro / chat-pro |
| 实际渠道 | DeepSeek 生产账号 |
| 实际模型 | deepseek-v4-pro |
| 输入 Token | prompt tokens |
| 输出 Token | completion tokens |
| 总 Token | total tokens |
| 成本 | 按价格版本计算 |
| 延迟 | 请求耗时 |
| 状态 | 成功 / 失败 |
| 错误码 | TokenSea 标准错误码 |
| Attempt 链 | Retry / Fallback 详情 |

成本管理员可按以下维度统计：

```text
租户
项目
应用
Virtual Key
服务模型
供应商渠道
价格版本
日期 / 月份
```

安全审计员可查看：

```text
谁创建了供应商渠道
谁托管或轮换了供应商 Key
谁审核了模型部署
谁发布了企业服务模型
谁创建了 Virtual Key
谁修改了预算
谁查看了敏感字段
```

---

## 19. 对象关系总结

以 `deepseek-v4-pro` 为例，对象关系如下：

```text
DeepSeek 供应商模板
        ↓ 启用
DeepSeek 生产账号，供应商渠道
        ↓ 模型发现
DeepSeek 生产账号 / deepseek-v4-pro，模型部署
        ↓ 审核、能力验证、价格绑定
企业服务模型 deepseek-v4-pro 或 chat-pro
        ↓ 授权
租户：研发中心
        ↓
项目：智能客服项目
        ↓
应用：客服机器人后端
        ↓
TokenSea Virtual Key：sk-ts-xxxxxxxxxxxxxxxx
        ↓
业务系统调用 TokenSea Gateway
        ↓
TokenSea 调用 DeepSeek deepseek-v4-pro
```

---

## 20. 常见问题

### 20.1 用户拿到的是 DeepSeek Key 还是 TokenSea Key？

用户拿到的是 TokenSea Virtual Key，例如：

```text
sk-ts-xxxxxxxxxxxxxxxx
```

不是 DeepSeek 官方 Key。

### 20.2 用户是否需要知道 DeepSeek 的 API Base？

不需要。

用户只需要知道 TokenSea Gateway 的地址，例如：

```text
http://localhost:39212/v1
```

### 20.3 业务系统是否必须调用 deepseek-v4-pro 这个名字？

不一定。

企业可以直接发布：

```text
deepseek-v4-pro
```

也可以发布稳定别名：

```text
chat-pro
reasoning-pro
code-pro
```

推荐业务系统优先使用稳定别名，避免供应商模型升级时频繁改代码。

### 20.4 模型发现后为什么还要审核？

因为模型发现只说明供应商账号返回了该模型，不代表它已经满足企业生产要求。

还需要确认：

```text
能力是否真实可用
价格是否已配置
是否符合合规要求
是否允许业务使用
是否已经配置路由和预算
```

### 20.5 成本是按什么计算的？

正式成本应按渠道实际成本版本计算，而不是公共参考价格。

成本计算应固化为请求级成本快照：

```text
请求发生时间
价格版本
输入 Token
输出 Token
币种
汇率
应计成本
```

### 20.6 DeepSeek 故障时怎么办？

如果配置了 Fallback，TokenSea 可以在超时、限流或上游错误时切换到备用模型部署。

Fallback 每一次尝试都应记录在：

```text
request_attempt
```

便于追踪实际成本和错误原因。

---

## 21. 最终用户视角

最终用户只需要关心：

```text
base_url = TokenSea Gateway 地址
api_key = TokenSea Virtual Key
model = 企业服务模型名
```

最终用户不需要关心：

```text
DeepSeek 官方 Key
DeepSeek 合同价格
DeepSeek API Base
DeepSeek 账号和区域
底层是否 Fallback
真实成本如何计算
供应商账单如何对账
```

这就是 TokenSea 的核心价值：

> 业务调用足够简单，平台治理足够完整。
