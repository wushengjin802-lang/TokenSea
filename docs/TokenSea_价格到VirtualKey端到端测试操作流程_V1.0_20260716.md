# TokenSea 从价格生成到 Virtual Key 可用的端到端测试操作流程

> 文档版本：V1.0  
> 编制日期：2026-07-16  
> 适用环境：TokenSea 当前研发环境（Console `39210`、Control Plane `39211`、Gateway Runtime `39212`、Runtime Core `39218`）  
> 目标模型示例：`deepseek-v4-pro`

---

## 1. 文档目标

本文用于指导测试人员或平台管理员，从 DeepSeek 官方价格获取开始，依次完成：

```text
价格源配置
→ 价格同步
→ 价格差异审核
→ 供应商官方价格目录
→ 模型生效价格
→ 模型部署审核与能力验证
→ 企业服务模型
→ 路由策略
→ 租户、项目和应用
→ Virtual Key 审批与生成
→ /v1/models 权限验证
→ /v1/chat/completions 真实调用
→ 调用日志、用量和成本核验
```

最终成功标准是：业务方仅使用 TokenSea Virtual Key 和企业服务模型名，即可通过 TokenSea Gateway 完成一次真实模型调用，并在平台中形成完整的调用、用量、成本和审计记录。

---

## 2. 当前环境状态

2026-07-16 数据库恢复后，旧卷仅恢复了 `admin` 账号；供应商、模型部署、官方价格目录、模型生效价格、路由、租户和 Virtual Key 数据均需重新创建。本流程应从第 5 章开始完整执行。

当前已确认：

| 检查项 | 当前状态 |
|---|---|
| Console、Control Plane、Gateway、Runtime Core | 健康检查通过 |
| 平台管理员账号 | 已恢复 |
| DeepSeek 官方价格源 | 需要重新配置 |
| `deepseek-v4-pro` 官方价格目录 | 尚未生成 |
| 模型生效价格 | 尚未生成 |
| 模型部署、能力验证、路由、企业服务模型 | 需要重新创建 |
| Virtual Key 验证脚本 | 已生成 PowerShell 和 Python 版本 |

价格成功生成后，DeepSeek 官方价格的预期值为：

```text
计费对象：TOKEN
计费基数：1000000
输入价格：0.435 USD / 百万 Token
输出价格：0.870 USD / 百万 Token
```

当前预算基准币种必须与价格币种保持一致；若使用 DeepSeek 官方 USD 价格，请按第 4.3 节将研发环境预算基准币种调整为 USD 后再创建并生效路由。

---

## 3. 角色与测试数据建议

建议使用平台管理员账号完成配置和审批。

建议准备一组独立测试数据：

| 对象 | 示例名称 |
|---|---|
| 供应商渠道 | DeepSeek 测试渠道 |
| 企业服务模型 | `deepseek-v4-pro` 或 `chat-pro` |
| 路由策略 | DeepSeek V4 Pro 测试路由 |
| 租户 | TokenSea 测试租户 |
| 项目 | TokenSea 联调项目 |
| 应用 | Key 验证应用 |
| Key 名称 | DeepSeek V4 Pro 测试 Key |

推荐使用稳定业务别名，例如 `chat-pro`。若本次目的是快速验证当前链路，也可以直接使用 `deepseek-v4-pro` 作为企业服务模型名。

---

## 4. 测试前环境检查

### 4.1 服务健康检查

在 PowerShell 中执行：

```powershell
Invoke-RestMethod http://localhost:39211/actuator/health
Invoke-RestMethod http://localhost:39212/health
Invoke-RestMethod http://localhost:39218/health/liveliness
```

预期结果均包含：

```json
{"status":"UP"}
```

或 Runtime Core 返回等价的健康状态。

同时浏览器访问：

```text
http://localhost:39210
```

预期 Console 可以正常登录。

### 4.2 检查供应商渠道

菜单：

```text
模型中心 → 供应商渠道
```

找到 DeepSeek 渠道，确认：

- 状态为“启用”；
- Key 状态为“已托管”或渠道为“无需 Key”；
- 最近连接测试结果为“成功”；
- API Base 正确；
- 最近连接测试未过期；
- 渠道目标域名和 DNS 快照未变化；
- 渠道健康状态正常。

若连接测试失败，优先检查：

```text
DeepSeek 原始 Key
API Base
出口代理
api.deepseek.com 域名白名单
DNS 快照
供应商账号是否具备该模型权限
```

### 4.3 统一预算币种与官方价格币种

当前环境：

```text
TOKENSEA_BUDGET_CURRENCY=CNY
DeepSeek 官方价格币种=USD
```

当前路由生效校验要求供应商价格币种与预算基准币种一致。若不处理，路由草稿可以创建，但提交或生效时可能提示：

```text
候选渠道缺少当前生效的供应商官方价格
```

研发验证阶段推荐方案：

1. 打开：

```text
deploy/compose/.env
```

2. 将：

```text
TOKENSEA_BUDGET_CURRENCY=CNY
```

临时调整为：

```text
TOKENSEA_BUDGET_CURRENCY=USD
```

3. 重启本地 Control Plane 和 Gateway Runtime。

生产环境不建议简单修改币种。生产应采用以下一种正式方案：

- 建立受审批的汇率与币种转换机制；
- 按供应商合同或账单维护 `CHANNEL_ACTUAL` 的 CNY 价格版本；
- 将整个平台预算和账务基准统一为 USD。

禁止在没有来源、日期和审批记录的情况下人工填写临时汇率价格。

---

## 5. 阶段一：生成 DeepSeek 官方价格

### 5.1 检查或创建价格源

菜单：

```text
同步中心 → 价格源管理
```

DeepSeek 官方价格源建议配置：

| 字段 | 建议值 |
|---|---|
| 价格源名称 | DeepSeek 官方价格页 |
| 价格源类别 | 供应商官方价格 |
| 适配器 | DeepSeek 官方价格页 |
| 供应商类型 | deepseek |
| 认证方式 | 无需认证 |
| 官方来源地址 | `https://api-docs.deepseek.com/quick_start/pricing/` |
| 官方域名 | `["api-docs.deepseek.com"]` |
| 价格区域 | 全球 |
| 默认币种 | USD |
| 同步周期 | 每天或按测试需要设置 |
| 低风险自动发布 | 首次联调建议关闭 |
| 连续确认次数 | 1 或按治理规则设置 |
| 状态 | 启用 |

先点击：

```text
测试获取
```

预期：

- HTTP 获取成功；
- 能识别 `deepseek-v4-pro`；
- 输入、输出和缓存价格非空；
- 不出现“官方价格页结构发生变化，无法安全解析”。

### 5.2 执行价格同步

在价格源记录上点击：

```text
立即同步
```

然后进入：

```text
同步中心 → 价格同步任务
```

正常状态可能为：

- 执行成功；
- 无价格变化；
- 待审核。

首次发现新价格时，通常应看到：

```text
获取记录 > 0
标准化记录 > 0
变更记录 > 0
待审核 > 0
```

点击“查看执行详情”，检查：

- 执行日志；
- 原始快照；
- 差异记录；
- HTTP 状态；
- 解析器版本；
- 错误码和错误信息。

### 5.3 核验原始快照

菜单：

```text
同步中心 → 价格原始快照
```

确认：

- 来源地址为 DeepSeek 官方价格页；
- HTTP 状态为 200；
- Content-Type 合理；
- SHA-256 非空；
- 解析器版本非空；
- 原始内容能够回溯；
- 获取时间为本次同步时间。

### 5.4 审核价格差异

菜单：

```text
同步中心 → 价格差异审核
```

找到：

```text
deepseek / deepseek-v4-pro
```

点击“查看差异”，检查新价格：

```text
币种：USD
计费对象：TOKEN
计费基数：1000000
输入单位价格：0.435
输出单位价格：0.870
缓存命中输入单位价格：0.003625
```

确认来源、币种、单位和模型名正确后，点击：

```text
批准发布
```

预期：

- 差异状态变为“已批准”；
- 审核人显示管理员名称；
- 审核时间已记录；
- 发布目录记录 ID 非空；
- 不再出现 403；
- 不出现 SQL、价格版本或匹配异常。

若数据不可信，应点击“驳回”并填写原因，不要批准错误价格。

---

## 6. 阶段二：核验价格目录和模型生效价格

### 6.1 供应商官方价格目录

菜单：

```text
模型中心 → 供应商官方价格目录
```

确认 `deepseek-v4-pro` 记录：

| 字段 | 预期值 |
|---|---|
| 供应商类型 | deepseek |
| 供应商模型名 | deepseek-v4-pro |
| 币种 | USD |
| 计费对象 | Token |
| 计费基数 | 1000000 |
| 输入单位价格 | 0.435 |
| 输出单位价格 | 0.870 |
| 来源类型 | 供应商官方公开价或供应商价格接口 |
| 来源依据 | DeepSeek 官方价格页 |
| 状态 | ACTIVE / 启用 |
| 已匹配部署 | 大于 0 |

如“已匹配部署”为 0，可点击“重新匹配”。

### 6.2 模型生效价格

菜单：

```text
模型中心 → 模型生效价格
```

确认存在：

```text
供应商模型：deepseek-v4-pro
价格层级：PROVIDER_OFFICIAL
状态：ACTIVE
币种：USD
计费对象：TOKEN
计费基数：1000000
输入单位价格：0.435
输出单位价格：0.870
自动生成：是
匹配方式：精确匹配
```

若价格目录有金额，但模型生效价格没有记录，检查：

- 模型部署是否存在；
- 模型名是否完全一致；
- 供应商类型是否一致；
- 区域是否可匹配；
- 模型部署是否已审核；
- 是否执行过“重新匹配”。

若记录存在但金额显示为空，先强制刷新页面；当前接口返回 `billingBasis`、`billingQuantity`、`inputUnitPrice` 和 `outputUnitPrice`。

---

## 7. 阶段三：确认模型部署可用于生产路由

### 7.1 模型发现

菜单：

```text
同步中心 → 模型发现
```

对 DeepSeek 渠道执行模型发现，确保能够发现：

```text
deepseek-v4-pro
```

模型被发现只表示供应商账号返回了该模型，不表示业务已经可以调用。

### 7.2 审核模型部署

菜单：

```text
模型中心 → 模型部署
```

确认：

| 字段 | 预期状态 |
|---|---|
| 供应商渠道 | 正确的 DeepSeek 渠道 |
| 供应商模型 | deepseek-v4-pro |
| 审核状态 | 已通过 / APPROVED |
| 路由资格 | 可路由 / ELIGIBLE |
| 价格状态 | 已匹配 |
| 价格匹配方式 | EXACT 或可信的 ALIAS |

未审核时执行“审核通过”。

### 7.3 能力验证

菜单：

```text
模型中心 → 能力验证
```

找到 `deepseek-v4-pro`，点击：

```text
发起探测
```

至少完成一次真实 `LIVE_PROBE`，预期状态：

```text
PASSED
```

当前平台发布路由时会检查：

- 模型部署已经审核；
- 路由资格为 ELIGIBLE；
- 至少存在一次主动能力验证通过记录。

只有公共文档声明、没有真实探测记录，不足以进入生产路由。

---

## 8. 阶段四：创建企业服务模型和路由策略

企业服务模型是业务方最终调用的模型名。供应商实际模型 `deepseek-v4-pro` 与企业服务模型可以同名，也可以使用稳定别名。

推荐关系：

```text
企业服务模型：chat-pro
供应商渠道：DeepSeek 测试渠道
供应商实际模型：deepseek-v4-pro
```

快速验证也可使用：

```text
企业服务模型：deepseek-v4-pro
供应商实际模型：deepseek-v4-pro
```

### 8.1 先创建企业服务模型草稿

菜单：

```text
模型中心 → 企业服务模型
```

点击“新建草稿”，填写：

| 字段 | 示例值 |
|---|---|
| 服务模型名 | `deepseek-v4-pro` 或 `chat-pro` |
| 展示名称 | DeepSeek V4 Pro |
| 供应商渠道 | DeepSeek 测试渠道 |
| 已审核部署模型 | deepseek-v4-pro |
| 路由策略 | 此时可暂不选择 |
| 可见范围 | 测试租户或全部内部租户 |
| 需要审批 | 是 |

保存后状态应为“草稿”。

重要说明：必须先存在企业服务模型草稿，路由策略页面才能选择对应的服务模型名。

### 8.2 创建路由策略

菜单：

```text
路由中心 → 路由策略
```

新建路由策略：

| 字段 | 示例值 |
|---|---|
| 策略名称 | DeepSeek V4 Pro 测试路由 |
| 服务模型 | deepseek-v4-pro 或 chat-pro |
| 策略类型 | 优先级 |
| 启用 Fallback | 单渠道测试可关闭或保持开启 |
| 供应商渠道 | DeepSeek 测试渠道 |
| 实际模型 | deepseek-v4-pro |
| 价格版本 | 供应商官方价 · V1 · USD · 输入 0.435 / 输出 0.870 · 每 1000000 Token |
| 优先级 | 1 |

价格版本下拉框现在应同时显示：

- `PROVIDER_OFFICIAL`；
- `CHANNEL_ACTUAL`。

并且只显示与当前“渠道 + 实际模型”匹配的价格版本。

若价格版本仍为空，依次检查：

1. 实际模型是否已选择；
2. 模型生效价格是否为 `ACTIVE`；
3. 价格层级是否为 `PROVIDER_OFFICIAL` 或 `CHANNEL_ACTUAL`；
4. 价格记录是否绑定当前 deployment；
5. 页面是否加载了最新前端代码；
6. Control Plane 是否已经重启；
7. 浏览器是否需要强制刷新。

保存后路由状态为“草稿”。

### 8.3 提交、审批和生效路由策略

执行：

```text
提交审批
```

进入：

```text
Key 中心 → 申请审批
```

找到资源类型为路由策略的申请，执行批准。

返回路由策略页面，执行：

```text
生效
```

路由生效时平台会校验：

- 服务模型存在；
- 候选属于服务模型配置的渠道和实际模型；
- 模型部署已审核；
- 模型部署可路由；
- 主动能力验证通过；
- 当前存在有效价格；
- 价格币种与预算基准币种一致。

预期路由状态：

```text
ACTIVE / 生效
```

### 8.4 回填企业服务模型的路由策略

返回：

```text
模型中心 → 企业服务模型
```

编辑刚才的企业服务模型草稿，将“路由策略”设置为刚刚生效的路由策略。

确认：

- 服务模型名与路由策略中的服务模型完全一致；
- 供应商渠道和实际模型映射完整；
- 可见范围包含测试租户；
- 路由策略状态为 ACTIVE。

### 8.5 提交审批并发布企业服务模型

在企业服务模型页面点击：

```text
提交审批
```

进入：

```text
Key 中心 → 申请审批
```

批准资源类型为企业服务模型的申请。

返回企业服务模型页面点击：

```text
发布
```

预期状态：

```text
已发布
```

发布时平台还会检查：

- 渠道已启用；
- 渠道连接测试成功且未过期；
- 渠道密钥已托管；
- DNS 和目标主机未变化；
- 路由策略已生效；
- 价格和能力验证满足要求。

---

## 9. 阶段五：创建租户、项目和应用

### 9.1 创建租户

菜单：

```text
租户中心 → 租户管理
```

示例：

| 字段 | 示例值 |
|---|---|
| 租户名称 | TokenSea 测试租户 |
| 类型 | 内部租户 |
| 模型范围 | deepseek-v4-pro 或 chat-pro |
| 状态 | 启用 |

### 9.2 创建项目

菜单：

```text
租户中心 → 项目管理
```

示例：

```text
项目名称：TokenSea 联调项目
所属租户：TokenSea 测试租户
```

### 9.3 创建应用

菜单：

```text
租户中心 → 应用管理
```

示例：

```text
应用名称：Key 验证应用
所属租户：TokenSea 测试租户
所属项目：TokenSea 联调项目
```

项目和应用不是生成 Key 的绝对必填项，但建议填写，以便后续进行用量归因、预算控制、调用日志检索和成本统计。

---

## 10. 阶段六：创建并生成 Virtual Key

菜单：

```text
Key 中心 → Key 列表
```

### 10.1 创建 Key

建议填写：

| 字段 | 示例值 |
|---|---|
| 租户 | TokenSea 测试租户 |
| 项目 | TokenSea 联调项目 |
| 应用 | Key 验证应用 |
| Key 名称 | DeepSeek V4 Pro 测试 Key |
| 模型范围 | deepseek-v4-pro 或 chat-pro |
| 预算 | 测试值或留空 |
| RPM | 60 |
| TPM | 100000 |
| QPS | 5 |
| IP 白名单 | 本机测试可留空；生产必须配置可信出口 IP |
| 有效期 | 设置为未来时间 |

保存后预期：

```text
状态：PENDING / 待处理
审批状态：PENDING / 待审批
Key 前缀：pending
```

### 10.2 审批 Key

点击：

```text
审批通过
```

预期：

```text
状态：ACTIVE
审批状态：APPROVED
```

### 10.3 生成明文 Key

点击：

```text
生成密钥
```

当前代码生成的 Key 形式为：

```text
ts_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

关键规则：

- 明文 Key 仅在生成时返回一次；
- 平台数据库仅保存哈希；
- 后续页面只显示 Key 前缀；
- 必须立即复制到密码管理器或安全的开发环境变量；
- 禁止把明文 Key 写入 Git、截图、日志或测试报告正文。

---

## 11. 阶段七：验证 Key 和模型权限

项目已提供两个验证脚本：

```text
scripts/dev/verify-virtual-key.ps1
scripts/dev/verify-virtual-key.py
```

脚本不会内置或打印 Key，会依次验证：

1. Gateway 健康状态；
2. Virtual Key 鉴权；
3. `/v1/models` 可访问模型范围；
4. 指定企业服务模型是否可见；
5. 可选的真实对话调用。

### 11.1 PowerShell：只验证 Key 和模型范围

进入项目目录：

```powershell
cd D:\12_其他项目\30_APIGateway\tokensea
```

执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\dev\verify-virtual-key.ps1 `
  -Model deepseek-v4-pro `
  -SkipChat
```

若企业服务模型名为 `chat-pro`，将模型参数改为：

```powershell
-Model chat-pro
```

脚本会提示安全输入 Virtual Key。

成功标准：

```text
Gateway 健康
Key 鉴权通过
/v1/models 返回目标企业服务模型
当前 Key 有权访问目标模型
```

### 11.2 PowerShell：执行真实模型调用

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\dev\verify-virtual-key.ps1 `
  -Model deepseek-v4-pro
```

成功时应输出：

- 模型回复；
- prompt token；
- completion token；
- total token；
- 请求 ID；
- “Verification passed”或等价成功提示。

### 11.3 Python：只验证权限

```powershell
python .\scripts\dev\verify-virtual-key.py `
  --model deepseek-v4-pro `
  --skip-chat
```

### 11.4 Python：真实调用

```powershell
python .\scripts\dev\verify-virtual-key.py `
  --model deepseek-v4-pro
```

### 11.5 使用环境变量执行

也可以先设置：

```powershell
$env:TOKENSEA_GATEWAY_BASE="http://localhost:39212"
$env:TOKENSEA_MODEL="deepseek-v4-pro"
$env:TOKENSEA_API_KEY="ts_这里填写刚生成的明文Key"
```

然后执行：

```powershell
python .\scripts\dev\verify-virtual-key.py
```

测试结束后清理当前 PowerShell 会话中的明文 Key：

```powershell
Remove-Item Env:TOKENSEA_API_KEY
```

---

## 12. 阶段八：使用开发者门户验证

### 12.1 服务模型列表

菜单：

```text
开发者门户 → 服务模型列表
```

输入 Virtual Key，查询可访问模型。

预期列表包含：

```text
deepseek-v4-pro
```

或实际发布的企业服务模型别名。

### 12.2 Playground

菜单：

```text
开发者门户 → Playground
```

填写：

- TokenSea Virtual Key；
- 企业服务模型名；
- 测试消息。

示例消息：

```text
请只回复：TokenSea Key 验证成功
```

点击发送后应获得正常模型回复。

### 12.3 快速开始

菜单：

```text
开发者门户 → 快速开始
```

可以检查网关连通性，并生成当前环境对应的 SDK 示例。

---

## 13. 阶段九：验证调用、用量、成本和审计闭环

真实调用成功后，必须继续核验平台内部记录，不能只看客户端返回。

### 13.1 调用日志

菜单：

```text
观测中心 → 调用日志
```

使用脚本输出的请求 ID 检索，确认：

- 请求状态成功；
- 租户、项目、应用和 Key 归属正确；
- 企业服务模型正确；
- 实际供应商渠道正确；
- 实际模型为 `deepseek-v4-pro`；
- 路由尝试记录完整；
- 延迟和错误码合理。

### 13.2 用量分析

菜单：

```text
成本与预算 → 用量分析
```

确认：

- prompt token 非空；
- completion token 非空；
- total token 正确；
- Key、应用、项目和租户归属正确。

### 13.3 成本快照

调用记录应关联：

```text
priceVersionId
priceLayer=PROVIDER_OFFICIAL
currency=USD
billingBasis=TOKEN
billingQuantity=1000000
inputUnitPrice=0.435
outputUnitPrice=0.870
```

历史请求应保存独立成本快照，后续价格调整不能回写历史请求成本。

### 13.4 操作审计

菜单：

```text
安全与审计 → 操作审计
```

确认能够看到：

- 价格差异批准发布；
- 路由策略创建、提交和生效；
- 企业服务模型发布；
- Key 创建、审批和生成；
- 关键配置变更。

### 13.5 告警事件

菜单：

```text
观测中心 → 告警事件
```

确认本次成功调用没有新产生以下告警：

- 模型价格缺失；
- 渠道不可用；
- 预算超限；
- DNS 变化；
- 凭据不可用；
- 路由策略无效。

---

## 14. 端到端验收清单

### 14.1 价格链路

- [ ] DeepSeek 官方价格源状态为启用
- [ ] 测试获取成功
- [ ] 价格同步任务执行成功或进入待审核
- [ ] 原始快照可回溯
- [ ] `deepseek-v4-pro` 价格差异已批准发布
- [ ] 官方价格目录金额非空
- [ ] 模型生效价格为 `PROVIDER_OFFICIAL + ACTIVE`
- [ ] 计费对象为 `TOKEN`，计费基数为 `1000000`
- [ ] 输入单位价格为 `0.435 USD / 百万 Token`
- [ ] 输出单位价格为 `0.870 USD / 百万 Token`

### 14.2 模型与路由链路

- [ ] DeepSeek 渠道已启用
- [ ] DeepSeek 渠道连接测试成功且未过期
- [ ] `deepseek-v4-pro` 模型部署已审核
- [ ] 模型部署路由资格为 ELIGIBLE
- [ ] 至少一次 LIVE_PROBE 为 PASSED
- [ ] 企业服务模型草稿已创建
- [ ] 路由策略价格版本下拉框可见
- [ ] 路由策略已审批并生效
- [ ] 企业服务模型已绑定路由策略
- [ ] 企业服务模型已审批并发布

### 14.3 Key 和调用链路

- [ ] 租户已启用
- [ ] 项目和应用归属正确
- [ ] Key 模型范围包含企业服务模型名
- [ ] Key 已审批
- [ ] 明文 Key 已生成并安全保存
- [ ] `/v1/models` 返回目标模型
- [ ] `/v1/chat/completions` 调用成功
- [ ] 返回内容非空
- [ ] Token 用量非空
- [ ] 调用日志存在
- [ ] 成本快照存在
- [ ] 操作审计存在
- [ ] 未出现异常告警

全部勾选后，才能认定：

```text
DeepSeek 官方价格 → 生效价格 → 路由 → 企业服务模型 → Virtual Key → Gateway 真实调用
```

形成完整可用闭环。

---

## 15. 常见失败与定位方法

| 现象 | 主要原因 | 处理方式 |
|---|---|---|
| 价格同步失败 | 官方页面不可访问、出口代理、页面结构变化 | 查看同步任务错误码、原始响应和价格源测试结果 |
| 差异批准失败 | 权限、发布 SQL、价格匹配异常 | 查看后端真实错误和 Control Plane 日志 |
| 模型生效价格为空 | 未匹配部署、模型名不一致、未重新匹配 | 检查官方目录、模型部署和重新匹配 |
| 金额显示为空 | 旧前端缓存或旧接口版本 | 重启 Control Plane、强制刷新浏览器 |
| 路由价格下拉框为空 | 未选择实际模型、没有 ACTIVE 价格、部署不匹配 | 确认 `PROVIDER_OFFICIAL/CHANNEL_ACTUAL` 和 deployment 绑定 |
| 路由生效失败，提示缺少价格 | USD 与 CNY 不一致 | 统一预算基准币种或建立正式的 CNY 实际成本版本 |
| 企业服务模型发布失败 | 路由未生效、连接测试过期、密钥未托管、DNS 变化 | 逐项修复发布前校验条件 |
| Key 无法生成 | Key 尚未审批 | 先执行“审批通过”，再“生成密钥” |
| `/v1/models` 返回 401 | Key 无效或复制错误 | 使用生成时返回的完整明文 Key |
| `/v1/models` 返回 403 | Key 停用、过期、IP 白名单不匹配 | 检查 Key 状态、有效期和客户端出口 IP |
| `/v1/models` 不含目标模型 | 企业服务模型未发布、不可见范围或 Key 模型范围错误 | 检查模型发布状态、租户范围和 Key 模型范围 |
| 返回 `TOKENSEA_ROUTE_POLICY_INVALID` | 企业服务模型未绑定 ACTIVE 路由 | 生效路由并重新发布企业服务模型 |
| 返回 `TOKENSEA_PRICE_NOT_CONFIGURED` | 当前部署没有有效价格 | 检查模型生效价格、币种和生效时间 |
| 返回 `TOKENSEA_SECRET_*` | 供应商密钥未托管或解密失败 | 重新配置渠道凭据并测试连接 |
| 返回 DNS/SSRF 错误 | 供应商主机或 DNS 记录变化 | 重新执行渠道连接测试并发布新快照 |
| 返回预算错误 | 预算不足、币种不一致、预算规则无效 | 检查预算规则、价格币种和可用额度 |
| 真实调用 502/503 | 上游供应商、Runtime Core、路由或出口异常 | 使用请求 ID 检查调用尝试、渠道健康和后端日志 |

---

## 16. 最小可执行测试路径

在当前平台数据已基本准备好的情况下，最快验证路径为：

```text
1. 确认预算基准币种与 DeepSeek USD 价格一致
2. 确认 deepseek-v4-pro 生效价格为 ACTIVE
3. 确认模型部署 APPROVED + ELIGIBLE + LIVE_PROBE PASSED
4. 新建企业服务模型草稿
5. 新建路由策略并选择 PROVIDER_OFFICIAL 价格版本
6. 审批并生效路由策略
7. 回填企业服务模型的路由策略
8. 审批并发布企业服务模型
9. 创建测试租户、项目和应用
10. 创建 Key
11. 审批 Key
12. 生成并保存明文 Key
13. 运行 verify-virtual-key.ps1 -SkipChat
14. 运行 verify-virtual-key.ps1 进行真实调用
15. 检查调用日志、用量、成本快照和审计记录
```

此路径全部成功后，Virtual Key 才可认定为真正可用，而不仅仅是“已生成”。
