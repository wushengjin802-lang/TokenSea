# TokenSea 公共参考价格自动初始化与每日更新实施方案

- **版本**：V1.0
- **日期**：2026-07-29
- **适用项目**：TokenSea 企业级统一 LLM API Gateway
- **文档性质**：产品范围调整与技术实施方案
- **适用阶段**：现有价格治理流程简化、编码前评审、迭代排期
- **最新需求口径**：价格仅用于模型选型、成本趋势和用量估算参考，不作为供应商结算、租户收费或财务对账的权威依据

---

## 0. 实施结果回填

截至 2026-07-29，本方案核心闭环已完成代码实现：

- 新增 Flyway V41，区分系统/用户来源、参考/正式价格用途和发布目标；
- LiteLLM Cost Map、models.dev 在升级后自动转为系统管理、启用状态和每日同步；
- 新增随发布包携带的离线参考价格快照，Control Plane 启动时幂等导入；
- 启动完成后自动将在线参考源加入同步队列，并同步前移 `next_run_at`，避免启动初始化与 15 秒调度轮询重复入队；系统参考源每日执行时间增加最多 15 分钟随机抖动；
- 公共参考价格直接更新 `public_model_price_reference` 和公共模型参考目录，不生成逐模型价格差异审核；
- 新增当前参考价选择视图，按是否过期、来源优先级、置信度和观测时间自动选择；在线高优先级来源过期后自动回退到发布包快照；
- 新增参考模型身份规范化，保留聚合供应商语义，`openrouter/openai/gpt-4o` 与直连 `openai/gpt-4o` 不会错误归并；
- 在线同步失败时保留最近一次成功价格，并按 30 分钟、2 小时、次日进行退避；
- 系统参考源在旧价格源 CRUD 中只读；
- Console 新增“参考价格状态”页面，隐藏日常人工价格源、提取审核、映射规则、价格差异和逐模型生效价格入口；
- 新增自动参考价格环境变量、API 文档和回归测试；显式关闭 `TOKENSEA_REFERENCE_PRICE_ENABLED` 时，全部系统参考源自动暂停，且总览准确返回关闭状态。

验证结果：Control Plane 普通全量回归 170 项，0 失败、0 错误、21 项按外部环境条件跳过；PostgreSQL 16 上 V1→V41、历史 V6→V41、价格源字段持久化、JSONB 抽取审核和离线快照幂等/多来源回退 5 条实库路径逐项通过；在线公共参考写入、每日调度前移和并发入队幂等集成测试通过；Console 资源契约、TypeScript 检查和生产构建通过；Egress Proxy 26 项测试通过；Compose 配置解析和 Git 差异格式检查通过。

当前内置离线快照仅提供经过项目文档确认的少量启动样例；平台在线运行后主要由 LiteLLM 与 models.dev 自动扩充。真实公网同步结果仍取决于部署环境网络、出口策略和上游数据可用性，本次未使用真实公网数据执行端到端同步，也不将其作为应用启动条件。

---

## 1. 结论

当前“管理员新建价格源 → 填写供应商、URL、域名和解析参数 → 测试获取 → 测试解析 → 人工启用 → 每个模型逐条审核”的流程不符合 TokenSea 当前产品定位。

推荐将公共价格能力调整为：

```text
平台发布包内置参考价格快照
        ↓
首次部署自动导入，无需联网也有基础价格
        ↓
系统自动创建并启用内置参考价格源
        ↓
启动后立即执行一次在线更新
        ↓
此后每天自动同步 LiteLLM、models.dev 等结构化来源
        ↓
自动标准化、去重、合并并生成当前参考价格
        ↓
异常时保留最近一次成功价格，只产生运维告警
        ↓
管理员只查看状态，不参与日常配置和审核
```

本方案的核心变化是：

1. **公共价格从“治理对象”降级为“系统参考数据”**；
2. **平台部署后自动有价格，不依赖管理员配置**；
3. **每天自动更新，不要求人工测试、启用和批准**；
4. **公共参考价格与实际结算价格彻底隔离**；
5. **供应商官网解析器不再是主链路，只作为平台内置补充源**；
6. **来源失败、价格缺失或短期异常不影响模型调用和平台启动**。

---

## 2. 编制依据与产品口径调整

### 2.1 原始文档中的既定能力

原 PRD 将模型目录、价格版本、成本计算、用量记录和账单能力列为模型资产及计费领域的重要组成部分，并提出价格应支持币种、生效时间和版本管理。LiteLLM 分析报告同时指出，LiteLLM 的模型成本表和 Spend Tracking 适合用作成本追踪参考，但完整商业计费、合同、账单和财务结算需要平台自研。

### 2.2 本次最新需求的优先级

本次需求进一步明确：

```text
价格是次要信息
只作为公开参考价和估算依据
不用于实际供应商结算
不用于对客收费
不值得要求部署管理员逐个供应商、逐个模型配置
```

因此，本方案以本次最新需求为准，对原价格治理范围进行收缩：

| 原设计 | 调整后设计 |
|---|---|
| 公共价格也走完整审核发布流程 | 公共参考价自动导入、自动更新、自动生效 |
| 管理员逐个配置价格源 | 系统内置并自动维护价格源 |
| 每个供应商页面建设专用解析器 | 结构化公共数据源为主，官网解析为补充 |
| 价格差异需要人工逐条审核 | 参考价变化自动记录版本，不阻塞更新 |
| 价格可能参与正式成本结算 | 仅产生“参考估算成本”，明确非结算金额 |
| 新建价格源是正常业务操作 | 仅保留为高级运维或二次开发入口 |

### 2.3 不改变的产品边界

以下能力继续保留：

- 模型目录显示输入、输出、缓存等参考价格；
- 用量分析可显示参考估算成本；
- 路由策略可将参考价格作为“低成本优先”的弱权重指标；
- 价格来源、更新时间和版本可追踪；
- 历史参考价格可查看；
- 企业后续如确需合同价、账单价，可单独建设“实际成本价格”体系。

以下行为明确禁止：

- 公共参考价格自动覆盖合同价、渠道实际成本或供应商账单；
- 将公共价格估算金额标记为“应付金额”“结算金额”或“真实成本”；
- 因公共价格同步失败阻断 Gateway 调用；
- 因某模型缺少公开价格而禁止模型发布或使用；
- 部署管理员必须逐个供应商维护官方价格 URL。

---

## 3. 当前实现问题

### 3.1 内置参考源已经存在，但默认不会运行

当前数据库迁移已经内置：

```text
builtin_litellm_cost_map
builtin_models_dev
builtin_deepseek_official_price
以及 Qwen、Kimi、Xiaomi MiMo、智谱等官方价格源
```

其中 LiteLLM 和 models.dev 已配置：

```text
schedule_expression = P1D
referenceOnly = true
```

但初始化状态是：

```text
PAUSED
```

当前 `ProviderPriceSyncService` 的定时调度只处理：

```text
ACTIVE
DEGRADED
```

因此干净部署后，价格源虽然存在，却不会自动执行。

### 3.2 公共参考价错误复用了正式价格治理流程

当前页面要求管理员执行：

```text
新建价格源
→ 测试获取
→ 测试解析
→ 启用
→ 同步
→ 价格文档提取审核
→ 价格差异审核
```

该流程适合合同价、渠道实际成本和高风险正式价格，不适合仅供参考的公共价格数据。

### 3.3 官网解析器成为了主要接入方式

当前已经建设多个官方网页适配器及通用 HTML/CSV/JSON/PDF 提取能力。这些能力技术上可用，但存在：

- 页面结构变化导致解析失效；
- 动态渲染需要 Headless Fetcher；
- 官方域名及子资源域名需要出口白名单；
- LLM Schema Mapper 需要额外 Virtual Key；
- 每家供应商都需要测试和维护；
- 对“仅作参考”的价格信息而言，维护成本过高。

### 3.4 “测试获取”和“测试解析”仍是人工流程

当前两个按钮面向管理员，且后端都复用了完整预览逻辑。即使后续拆分，也不应成为平台部署后的必经流程。

### 3.5 价格缺失与模型接入耦合过强

新模型接入不应因为暂时没有参考价格而中断。价格应是模型目录的可选增强字段，而不是模型发现、模型部署和企业服务模型发布的前置条件。

---

## 4. 目标产品形态

## 4.1 用户看到的价格功能

普通平台管理员不再管理价格抓取规则，只看到：

```text
参考价格状态
├── 数据更新时间
├── 覆盖模型数量
├── 主要数据来源
├── 最近同步结果
├── 过期模型数量
└── 当前是否使用本地快照
```

模型目录中显示：

```text
输入参考价：¥x.xx / 百万 Token
输出参考价：¥x.xx / 百万 Token
来源：LiteLLM / models.dev / 官方公开页
更新时间：2026-07-29
性质：公开参考价，非结算价格
```

价格未知时显示：

```text
暂无公开参考价格
```

而不是要求管理员立即配置。

## 4.2 页面调整建议

### 普通管理菜单

将当前：

```text
高级治理
├── 价格源管理
├── 价格文档提取审核
├── 价格映射规则
├── 未映射价格记录
└── 价格差异审核
```

调整为：

```text
模型配置
└── 参考价格状态
```

默认页面仅显示系统自动同步状态。

### 高级运维入口

以下页面保留，但默认隐藏在“系统基础设置 → 高级价格诊断”中，仅供研发或运维人员使用：

- 价格源详情；
- 原始快照；
- 未映射记录；
- 提取诊断；
- 手工重试；
- 自定义价格源。

对于系统内置价格源：

- 不显示“新建”；
- 不允许普通管理员修改来源 URL、适配器和官方域名；
- 只允许“立即重试”和“查看诊断”；
- 系统升级时由版本化配置自动更新。

## 4.3 价格使用标识

所有公共参考价格必须显示统一标签：

```text
参考价
非结算价格
```

估算成本显示为：

```text
参考估算成本：约 ¥12.35
```

不得显示为：

```text
实际成本：¥12.35
供应商应付：¥12.35
结算金额：¥12.35
```

---

## 5. 目标总体架构

```text
┌──────────────────────────────────────────────────────────────┐
│                  TokenSea 发布包                              │
│  reference-price-bootstrap.json                              │
│  内置模型参考价快照、来源版本、生成时间                       │
└───────────────────────────┬──────────────────────────────────┘
                            │ 首次部署自动导入
                            ▼
┌──────────────────────────────────────────────────────────────┐
│              Reference Price Bootstrap                       │
│  幂等初始化系统价格源                                         │
│  导入本地参考快照                                             │
│  注册启动后首次同步                                           │
└───────────────────────────┬──────────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────────┐
│              System-managed Price Sources                    │
├──────────────────────────────────────────────────────────────┤
│ 一级来源：LiteLLM Cost Map                                   │
│ 二级来源：models.dev                                         │
│ 三级来源：Azure / AWS / Google 官方结构化目录                 │
│ 补充来源：DeepSeek / Qwen / Kimi / MiMo / 智谱等官方公开页    │
└───────────────────────────┬──────────────────────────────────┘
                            │ 每日自动同步
                            ▼
┌──────────────────────────────────────────────────────────────┐
│              Reference Price Normalize & Merge               │
│  模型名规范化                                                 │
│  供应商识别                                                   │
│  计费单位统一                                                 │
│  输入/输出/缓存组件标准化                                     │
│  多来源优先级与置信度合并                                     │
└───────────────────────────┬──────────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────────┐
│           public_model_price_reference                       │
│  当前参考价格 + 历史版本 + 来源证据 + 更新时间                │
└───────────────┬───────────────────────────┬──────────────────┘
                │                           │
                ▼                           ▼
        模型目录参考展示              用量参考成本估算
        模型选型/成本趋势              不进入正式结算
```

---

## 6. 数据来源策略

## 6.1 来源分层

### 一级来源：LiteLLM Cost Map

定位：平台默认主数据源。

优点：

- 模型覆盖广；
- JSON 结构化；
- 与 LiteLLM Runtime Core 的模型命名和成本计算逻辑接近；
- 当前代码已经具备 `LITELLM_COST_MAP` 适配器；
- 不需要为每个供应商单独配置。

使用规则：

- 部署后自动启用；
- 每天同步一次；
- 自动导入新模型；
- 自动更新输入、输出、缓存等公开价格；
- 标记为 `COMMUNITY_REFERENCE`；
- 仅用于参考，不进入正式成本价格表。

### 二级来源：models.dev

定位：模型信息和价格的交叉验证来源。

优点：

- JSON 结构化；
- 提供模型能力、上下文和价格信息；
- 当前代码已经具备 `MODELS_DEV` 适配器；
- 可补充 LiteLLM 中缺少的模型或字段。

使用规则：

- 部署后自动启用；
- 每天同步一次；
- 同一模型多来源冲突时，不阻塞更新；
- 记录来源差异，按来源优先级选取当前展示值。

### 三级来源：官方结构化目录

包括：

- Azure Retail Prices；
- AWS Price List；
- Google Cloud Billing Catalog。

定位：云平台模型的补充参考来源。

使用规则：

- 无需凭据的公共目录自动启用；
- 需要账号凭据的目录默认关闭，不影响基础参考价格能力；
- 外部 SKU 无法映射时保存为未映射记录，但不要求管理员逐条处理；
- 映射规则由 TokenSea 产品版本统一维护。

### 补充来源：供应商官方公开页

包括现有：

- DeepSeek；
- Qwen；
- Kimi；
- Xiaomi MiMo；
- 智谱；
- 后续可能增加的豆包等国内模型。

定位：补充国内主流模型公开价，不作为部署必配项。

处理原则：

- 由 TokenSea 发布版本统一维护 URL、域名、解析器和规则；
- 部署管理员不需要新建；
- 解析成功则提高覆盖率；
- 解析失败则继续使用 LiteLLM、models.dev 或本地快照；
- 失败不会阻断整体每日同步；
- 不要求部署管理员配置价格文档 LLM Virtual Key。

## 6.2 首次部署快照

发布包必须携带：

```text
configs/reference-prices/reference-price-bootstrap.json
```

建议结构：

```json
{
  "schemaVersion": "reference-price-bundle-v1",
  "bundleVersion": "2026.07.29.1",
  "generatedAt": "2026-07-29T00:00:00Z",
  "sources": ["litellm", "models.dev"],
  "prices": [
    {
      "providerType": "deepseek",
      "providerModelName": "deepseek-chat",
      "currency": "USD",
      "region": "global",
      "billingBasis": "TOKEN",
      "billingQuantity": 1000000,
      "inputUnitPrice": 0,
      "outputUnitPrice": 0,
      "source": "LITELLM_COST_MAP",
      "sourceObservedAt": "2026-07-28T00:00:00Z"
    }
  ]
}
```

要求：

- 快照随 TokenSea 版本发布；
- 可离线导入；
- 导入幂等；
- 不覆盖更新时间更新的在线数据；
- 每条记录保留来源和观察时间；
- 快照超过一定时间后显示“本地快照可能过期”，但不删除。

---

## 7. 自动初始化流程

## 7.1 部署启动流程

```text
Flyway 数据库升级完成
        ↓
BuiltInReferencePriceSourceReconciler 启动
        ↓
幂等创建/修正系统内置价格源
        ↓
所有公共参考源状态设为 ACTIVE
        ↓
导入 reference-price-bootstrap.json
        ↓
若数据库无在线成功记录，快照设为当前参考价
        ↓
设置 next_run_at = now()
        ↓
Control Plane 正常启动，不等待公网同步完成
        ↓
后台异步执行首次在线同步
```

### 关键原则

- 不能在 Flyway 迁移中访问公网；
- 不能因为外网不可达导致 Control Plane 启动失败；
- 不能要求管理员先进入页面点“启用”；
- 初始化服务必须幂等；
- 系统内置源被误暂停或删除后，下次启动可按策略恢复；
- 手工创建的自定义价格源不被自动修改。

## 7.2 内置源状态

系统内置公共参考源默认：

```text
managedBy = SYSTEM
purpose = REFERENCE
status = ACTIVE
scheduleExpression = P1D
autoPublish = true
publishTarget = PUBLIC_REFERENCE_ONLY
```

这里的 `autoPublish=true` 仅表示自动更新：

```text
public_model_price_reference
```

绝不表示自动更新：

```text
provider_model_price_catalog
price_version
contract_price
provider_actual_cost
customer_price
```

---

## 8. 每日自动更新流程

## 8.1 调度策略

建议默认时区：

```text
Asia/Shanghai
```

每日调度：

```text
03:20  LiteLLM Cost Map
03:30  models.dev
03:40  Azure/AWS/Google 公共目录
04:00  国内供应商官方页补充源
```

失败重试：

```text
首次失败：30 分钟后重试
第二次失败：2 小时后重试
第三次失败：次日正常周期重试
```

多实例部署时：

- 使用数据库任务抢占或分布式锁；
- 同一价格源只允许一个 `PENDING/RUNNING` 任务；
- 保留现有任务幂等约束；
- 每次同步记录 `sync_run_id` 和原始快照。

## 8.2 同步主流程

```text
读取系统内置价格源
        ↓
HTTP 条件请求：ETag / Last-Modified
        ↓
内容未变化
  └─ 更新最近检查时间，不生成新版本
        ↓
内容变化
        ↓
保存原始快照和 Checksum
        ↓
结构化解析和标准化
        ↓
模型名称规范化
        ↓
多来源合并
        ↓
更新当前参考价格
        ↓
保留历史版本
        ↓
更新覆盖率和健康状态
```

## 8.3 同步失败策略

单一来源失败时：

```text
不清空旧价格
不将模型标记为不可用
不阻断模型发布
不阻断 Gateway 调用
不要求立即人工处理
```

系统继续使用最近一次成功价格，并标记：

```text
STALE
```

建议过期阈值：

| 状态 | 条件 |
|---|---|
| 正常 | 最近成功更新不超过 48 小时 |
| 轻度过期 | 48 小时～7 天 |
| 严重过期 | 7～30 天 |
| 无有效在线数据 | 超过 30 天，仅剩发布包快照 |

只有以下情况产生告警：

- 所有一级、二级来源连续 3 天失败；
- 当前模型参考价格覆盖率下降超过 20%；
- 数据记录数量突然减少超过 30%；
- 同一模型价格单日变化超过 100 倍；
- 来源返回非预期格式或疑似安全页面。

---

## 9. 模型自动匹配与新模型自动导入

## 9.1 不要求模型逐条配置映射

参考价格采用独立键：

```text
provider_type + provider_model_name + region + request_mode + service_tier + context_tier
```

匹配顺序：

1. 精确供应商类型 + 精确模型 ID；
2. 供应商类型 + 已知模型别名；
3. 去除 LiteLLM Provider 前缀后的精确模型 ID；
4. 标准化大小写、空格、下划线和连字符；
5. 版本后缀兼容匹配；
6. 仍未匹配时，以来源模型 ID 创建公共模型参考记录。

## 9.2 新模型自动发现

当 LiteLLM 或 models.dev 出现新模型时：

```text
发现新模型记录
        ↓
创建/更新 public_model_reference
        ↓
创建 public_model_price_reference
        ↓
标记来源、能力、上下文和参考价格
        ↓
模型目录可检索
```

注意：

- 自动发现公共模型不等于自动创建供应商渠道；
- 不自动托管供应商 Key；
- 不自动创建生产模型部署；
- 不自动发布企业服务模型；
- 仅扩充公共参考目录。

当后续真正接入供应商渠道并发现模型时，系统按模型 ID 和别名自动关联已有参考价格。

## 9.3 未映射记录处理

未映射记录不再作为必须处理的待办。

处理方式：

- 自动保存；
- 纳入覆盖率指标；
- 后续 TokenSea 版本补充映射规则；
- 对高频使用模型可由研发统一补规则；
- 不要求每个私有化部署项目单独维护。

---

## 10. 多来源合并规则

## 10.1 当前展示价选择

建议来源优先级：

| 优先级 | 来源 | 用途 |
|---:|---|---|
| 100 | 供应商官方结构化 API | 公开参考价 |
| 90 | 供应商官方公开页面 | 公开参考价 |
| 80 | LiteLLM Cost Map | 默认主参考源 |
| 70 | models.dev | 补充和交叉核对 |
| 60 | TokenSea 发布包快照 | 离线和兜底 |

这里的优先级只决定“当前展示参考价”，不代表财务可信等级。

## 10.2 冲突处理

同一模型多来源价格不一致时：

- 不进入人工审批；
- 保存所有来源记录；
- 选择优先级最高且未过期的来源；
- 页面可查看其他来源；
- 价格差异超过阈值时显示“来源价格存在差异”；
- 不影响模型调用。

## 10.3 计费单位统一

统一为：

```text
输入 Token：每 1,000,000 Token
输出 Token：每 1,000,000 Token
缓存读取：每 1,000,000 Token
缓存写入：每 1,000,000 Token
图片：按张或统一原单位
音频：按秒或分钟并保留 billingBasis
请求：按次
```

不强行将无法可靠换算的单位转换成 Token 价格。

## 10.4 币种处理

公共参考表保留来源原币种。

模型目录可同时显示：

```text
原币种价格
参考人民币换算价
```

人民币换算仅用于展示，必须标注汇率日期，不写回原始参考价格。

---

## 11. 参考价格与实际成本隔离

## 11.1 三类价格概念

| 价格类型 | 定位 | 是否自动更新 | 是否用于结算 |
|---|---|---:|---:|
| 公共参考价 | 模型选型和趋势参考 | 是 | 否 |
| 参考估算成本 | usage × 公共参考价 | 是 | 否 |
| 实际成本价 | 合同、账单、供应商实际扣费 | 否/单独建设 | 是 |

本阶段只建设前两类。

## 11.2 用量记录处理

每次调用仍记录真实用量：

- 输入 Token；
- 输出 Token；
- 缓存 Token；
- 请求次数；
- 图片/音频等计费量。

若能匹配公共参考价，可生成：

```text
estimated_reference_cost
estimated_reference_currency
reference_price_id
reference_price_observed_at
cost_nature = REFERENCE_ESTIMATE
```

不得把该金额写成：

```text
actual_provider_cost
settlement_amount
invoice_amount
```

## 11.3 预算策略

建议默认预算优先使用：

- Token 预算；
- 请求数预算；
- RPM/TPM 限流。

基于参考价格的金额预算只能作为：

```text
软预警
```

不建议默认作为强制阻断条件。若租户明确选择“参考金额预算也可阻断”，需要在页面明确提示其估算属性。

## 11.4 路由策略

参考价格可用于成本优先路由，但只作为弱权重：

```text
健康度 > 模型可用性 > 合规 > 能力匹配 > 延迟 > 参考价格
```

不能因为参考价格缺失就排除一个健康模型。

---

## 12. 后端改造方案

## 12.1 新增模块

建议新增：

```text
services/control-plane/src/main/java/com/tokensea/governance/pricing/reference/
├── ReferencePriceBootstrapService.java
├── ReferencePriceBundleLoader.java
├── BuiltInReferenceSourceCatalog.java
├── BuiltInReferenceSourceReconciler.java
├── ReferencePriceMergeService.java
├── ReferenceModelMatcher.java
├── ReferencePriceCurrentResolver.java
├── ReferencePriceHealthService.java
└── ReferencePriceOverviewController.java
```

### `ReferencePriceBootstrapService`

职责：

- 应用启动后执行幂等初始化；
- 导入随发布包携带的参考价格快照；
- 确保系统价格源存在且处于自动运行状态；
- 触发首次异步同步；
- 不阻塞应用启动。

### `BuiltInReferenceSourceCatalog`

职责：

- 用代码或 YAML 统一维护系统内置价格源；
- 维护来源名称、URL、域名、适配器、优先级和周期；
- 版本升级时自动修正旧配置；
- 避免每个部署项目手工维护。

建议配置文件：

```text
configs/reference-prices/builtin-sources.yml
```

### `ReferencePriceBundleLoader`

职责：

- 读取 `reference-price-bootstrap.json`；
- 验证 Schema 和哈希；
- 幂等导入；
- 仅在无更新在线数据时作为当前价；
- 保留 bundleVersion。

### `ReferencePriceMergeService`

职责：

- 合并 LiteLLM、models.dev、官方目录和发布包快照；
- 计算来源优先级；
- 维护当前展示价格；
- 保存来源冲突；
- 不生成必须人工处理的价格差异任务。

### `ReferenceModelMatcher`

职责：

- 模型 ID 标准化；
- 供应商前缀解析；
- 模型别名匹配；
- 新公共模型参考自动创建；
- 与已接入模型自动关联。

### `ReferencePriceHealthService`

职责：

- 统计覆盖率；
- 标记过期；
- 检测记录数异常下降；
- 检测价格数量级异常；
- 产生运维告警。

## 12.2 复用现有组件

继续复用：

```text
ProviderPriceSyncService
PriceSourceAdapterRegistry
LITELLM_COST_MAP Adapter
MODELS_DEV Adapter
AzureRetailPriceAdapter
AwsPriceListBulkAdapter
GoogleCloudCatalogPriceAdapter
provider_price_sync_run
provider_price_raw_snapshot
public_model_price_reference
public_model_reference
```

不需要重写获取、快照和适配器基础能力。

## 12.3 修改 `ProviderPriceSyncService`

建议增加：

```text
sourcePurpose = REFERENCE
managedBy = SYSTEM
publishTarget = PUBLIC_REFERENCE_ONLY
```

执行逻辑：

- `REFERENCE + SYSTEM` 来源解析成功后直接更新公共参考表；
- 不生成 `provider_price_diff` 人工审核任务；
- 价格变化自动生成参考价格历史版本；
- 同步失败保留最近成功数据；
- 单个补充源失败不把整体状态置为不可用。

## 12.4 修改 `ProviderPriceSyncController`

普通管理 API 改为：

```text
GET  /api/reference-prices/overview
GET  /api/reference-prices/models
GET  /api/reference-prices/sources
POST /api/reference-prices/sources/{id}/retry
GET  /api/reference-prices/sources/{id}/runs
```

系统内置来源：

- 默认只读；
- 不允许删除；
- 不允许修改关键连接器参数；
- 不要求测试后启用；
- 可手工触发重试。

原有价格源 CRUD 保留给高级运维和扩展开发，但不作为普通用户流程。

## 12.5 定时任务

现有 `ProviderPriceSyncService` 已有轮询调度，可继续复用。

改造点：

- 内置参考源初始化为 `ACTIVE`；
- `next_run_at` 初始化为当前时间；
- 每次成功后按 `P1D` 更新；
- 失败按退避策略更新重试时间；
- 增加随机抖动，避免所有私有化部署同时访问外部源；
- 多实例继续使用现有任务状态抢占。

---

## 13. 数据库改造方案

数据库迁移必须新增更高版本迁移，不修改已发布的 V14、V23、V39、V40。

建议新增：

```text
V41__automatic_reference_price_bootstrap.sql
```

## 13.1 `provider_price_source` 扩展

建议新增字段：

```sql
managed_by varchar(20) not null default 'USER';
source_purpose varchar(30) not null default 'FORMAL_PRICE';
publish_target varchar(40) not null default 'PRICE_DIFF';
bootstrap_version varchar(80);
stale_after_hours integer not null default 168;
last_checked_at timestamptz;
last_good_sync_at timestamptz;
```

枚举建议：

```text
managed_by：SYSTEM / USER
source_purpose：REFERENCE / FORMAL_PRICE / BILLING
publish_target：PUBLIC_REFERENCE_ONLY / PRICE_DIFF / BILLING_RECONCILIATION
```

## 13.2 激活内置公共源

V41 将现有：

```text
builtin_litellm_cost_map
builtin_models_dev
```

调整为：

```text
managed_by = SYSTEM
source_purpose = REFERENCE
publish_target = PUBLIC_REFERENCE_ONLY
status = ACTIVE
next_run_at = now()
schedule_expression = P1D
```

其他官方补充源也标记为系统管理，但可按稳定性决定是否默认启用。

## 13.3 `public_model_price_reference` 扩展

建议新增：

```sql
bundle_version varchar(80);
source_rank integer not null default 0;
is_current boolean not null default true;
last_seen_at timestamptz;
stale_at timestamptz;
price_status varchar(20) not null default 'CURRENT';
```

状态：

```text
CURRENT
STALE
MISSING
DISPUTED
```

## 13.4 当前价视图

建议新增视图：

```text
v_current_public_model_price_reference
```

按以下条件选取当前参考价：

1. 未过期；
2. 来源优先级最高；
3. `observed_at` 最新；
4. 同优先级时证据完整度最高。

## 13.5 历史兼容

- 现有手工价格源保持 `managed_by=USER`；
- 现有正式价格差异和已发布价格不删除；
- 公共参考源后续不再生成新的人工价格差异；
- 已产生的历史差异保留审计，不要求清理；
- Gateway 现有真实成本快照字段不被公共参考价覆盖。

---

## 14. 前端改造方案

## 14.1 新增“参考价格状态”页面

建议新增：

```text
apps/console/src/pages/ReferencePriceOverview.vue
```

页面卡片：

- 当前参考模型数量；
- 有价格模型数量；
- 价格覆盖率；
- 最近成功更新时间；
- 当前来源数量；
- 过期记录数量；
- 是否使用离线快照；
- 最近一次同步结果。

来源表格：

| 来源 | 管理方式 | 最近成功 | 下次同步 | 模型数 | 状态 | 操作 |
|---|---|---|---|---:|---|---|
| LiteLLM | 系统自动 | 2026-07-29 | 2026-07-30 | 1,200 | 正常 | 查看、重试 |
| models.dev | 系统自动 | 2026-07-29 | 2026-07-30 | 800 | 正常 | 查看、重试 |

## 14.2 简化“价格源管理”

普通用户：

- 不显示“新建”；
- 不显示“编辑”；
- 不显示“测试获取”；
- 不显示“测试解析”；
- 不显示“启用”；
- 只显示系统状态和“立即重试”。

高级运维模式下才显示现有完整操作。

## 14.3 模型目录展示

新增字段：

```text
参考输入价格
参考输出价格
价格来源
价格更新时间
价格状态
```

统一提示：

```text
公开参考价，仅用于模型选型和成本估算，不作为实际结算依据。
```

## 14.4 用量分析展示

将现有“成本”明确区分为：

```text
参考估算成本
```

若后续同时接入供应商账单，则显示：

```text
参考估算成本
实际账单成本
```

两者不混用。

## 14.5 菜单建议

```text
模型配置
├── 供应商渠道
├── 模型部署
├── 模型候选
├── 模型目录审核
├── 模型生效价格（可后置/高级）
├── 企业服务模型
└── 参考价格状态
```

当前“价格文档提取审核”“价格差异审核”等页面移入高级运维菜单，避免让普通管理员误以为必须处理。

---

## 15. 配置与部署改造

## 15.1 新增配置

建议在 `application.yml`：

```yaml
tokensea:
  reference-price:
    enabled: true
    bootstrap-enabled: true
    bootstrap-resource: classpath:reference-prices/reference-price-bootstrap.json
    immediate-sync-on-startup: true
    default-schedule: P1D
    zone: Asia/Shanghai
    stale-after-hours: 168
    hard-stale-after-hours: 720
    retry-delays: PT30M,PT2H
```

Compose 环境变量：

```text
TOKENSEA_REFERENCE_PRICE_ENABLED=true
TOKENSEA_REFERENCE_PRICE_BOOTSTRAP_ENABLED=true
TOKENSEA_REFERENCE_PRICE_IMMEDIATE_SYNC=true
TOKENSEA_REFERENCE_PRICE_SCHEDULE=P1D
TOKENSEA_REFERENCE_PRICE_ZONE=Asia/Shanghai
```

默认值应开箱即用，部署人员无需填写。

## 15.2 出口网络

系统内置结构化来源的精确域名随发布包预置：

```text
raw.githubusercontent.com
models.dev
prices.azure.com
pricing.us-east-1.amazonaws.com
cloudbilling.googleapis.com
```

官方网页补充源域名继续通过动态出口策略管理。

离线环境：

- 不要求访问公网；
- 使用发布包快照；
- 页面显示“离线快照”；
- 不产生持续高频错误告警；
- 网络恢复后可自动同步。

## 15.3 LLM 提取器定位调整

价格文档 LLM Schema Mapper 不再是公共参考价格主链必备配置。

默认：

```text
TOKENSEA_PRICE_DOCUMENT_LLM_ENABLED=false
```

只有以下情况才启用：

- TokenSea 产品团队维护某个重要官方补充源；
- 私有部署客户明确需要自定义文档价格导入；
- 高级运维人员主动配置专用 Virtual Key。

普通部署不配置 LLM Virtual Key，也能完成公共价格自动导入和每日更新。

---

## 16. API 设计

## 16.1 总览

```http
GET /api/reference-prices/overview
```

响应示例：

```json
{
  "enabled": true,
  "mode": "AUTO_REFERENCE",
  "modelCount": 1280,
  "pricedModelCount": 1096,
  "coverageRatio": 0.8563,
  "lastSuccessAt": "2026-07-29T03:31:00+08:00",
  "staleCount": 12,
  "usingBootstrapSnapshot": false,
  "notice": "公开参考价，仅用于估算，不作为结算依据"
}
```

## 16.2 来源状态

```http
GET /api/reference-prices/sources
```

仅返回脱敏后的系统来源状态。

## 16.3 模型参考价格

```http
GET /api/reference-prices/models?page=1&size=20&keyword=doubao
```

## 16.4 手工重试

```http
POST /api/reference-prices/sources/{id}/retry
```

该操作仅供运维处理异常，不是正常流程。

## 16.5 历史版本

```http
GET /api/reference-prices/models/{providerType}/{modelName}/history
```

---

## 17. 自动化测试方案

## 17.1 启动初始化测试

验证：

- 干净数据库启动后自动创建系统参考源；
- LiteLLM 和 models.dev 状态为 `ACTIVE`；
- `next_run_at` 非空；
- 本地快照自动导入；
- 重启不会重复生成记录；
- 用户自定义价格源不被修改。

## 17.2 每日同步测试

验证：

- 到期源自动入队；
- 同一源不会重复创建并发任务；
- 内容未变化时不生成重复版本；
- 内容变化时更新当前参考价；
- 失败后正确计算重试时间；
- 失败不删除最近成功价格。

## 17.3 多来源合并测试

验证：

- 官方结构化来源优先于公共参考库；
- LiteLLM 优先于发布包快照；
- 高优先级来源过期后自动回退；
- 来源冲突保留多份证据；
- 当前展示价选择稳定且可解释。

## 17.4 模型匹配测试

样例：

```text
openrouter/openai/gpt-4o
openai/gpt-4o
gpt-4o
```

验证能够按供应商语义正确区分，避免错误归并。

同时测试：

- 大小写；
- 连字符和下划线；
- 日期版本后缀；
- Provider 前缀；
- 同名不同供应商模型；
- 新模型自动创建公共参考记录。

## 17.5 隔离测试

验证公共参考价：

- 不写入合同价；
- 不写入供应商实际成本；
- 不写入结算金额；
- 不覆盖正式价格版本；
- 同步失败不影响 Gateway；
- 价格缺失不影响模型发布。

## 17.6 前端测试

验证：

- 普通页面无“新建价格源”必填流程；
- 参考价格状态页面可正常展示；
- 系统内置源只读；
- 价格均标识“参考价”；
- 过期价格有明确提示；
- 离线快照状态可识别。

---

## 18. 验收标准

## 18.1 部署验收

1. 全新部署无需管理员进入价格源页面；
2. 数据库初始化完成后自动出现公共参考价格；
3. 无公网环境下也可从发布包快照展示基础价格；
4. 有公网环境时启动后自动执行首次更新；
5. 内置参考源自动处于运行状态；
6. 不需要配置价格文档 LLM Virtual Key。

## 18.2 更新验收

1. 每天自动同步一次；
2. 新模型在上游参考库出现后 24 小时内自动进入公共模型参考目录；
3. 已有模型价格变化后 24 小时内更新；
4. 同步失败保留最近一次成功价格；
5. 连续失败产生运维告警；
6. 日常流程无人工审核待办。

## 18.3 覆盖率验收

建议 MVP 指标：

- LiteLLM 可识别模型价格导入成功率 ≥ 95%；
- TokenSea 公共模型目录参考价格覆盖率 ≥ 80%；
- 已实际接入并启用的主流文本模型参考价格覆盖率 ≥ 90%；
- 未覆盖模型不影响调用，只显示“暂无参考价格”。

## 18.4 安全与隔离验收

- 仅访问内置精确域名；
- 不支持通配符出口；
- 不存储公共来源凭据；
- 参考价格不可成为实际结算金额；
- 自动同步不接触供应商推理 Key；
- 系统源配置变更写操作审计。

---

## 19. 实施阶段与排期

## 第一阶段：自动初始化与自动启用（2～3 个工作日）

实施内容：

- 新增 V41 迁移；
- 将 LiteLLM、models.dev 标记为系统参考源并自动启用；
- 新增 `BuiltInReferenceSourceReconciler`；
- 新增本地参考价格快照导入；
- 部署后立即安排首次同步；
- 补初始化和幂等测试。

完成标志：

```text
干净部署后无需人工操作即可看到参考价格
```

## 第二阶段：自动合并与新模型导入（3～4 个工作日）

实施内容：

- 新增 `ReferencePriceMergeService`；
- 新增 `ReferenceModelMatcher`；
- 公共参考来源自动生效，不进入人工价格差异审核；
- 新模型自动进入公共模型参考目录；
- 当前参考价视图和历史版本；
- 失败保留最近成功数据。

完成标志：

```text
每天自动更新，新增模型和价格变化无需管理员处理
```

## 第三阶段：前端简化与运行监控（2～3 个工作日）

实施内容：

- 新增“参考价格状态”页面；
- 隐藏普通用户的新建、测试、启用和审核流程；
- 模型目录显示来源和更新时间；
- 用量成本改为“参考估算成本”；
- 增加覆盖率、过期和同步失败告警。

完成标志：

```text
价格功能从配置型后台变成自动运行状态页
```

## 第四阶段：官方补充源集中维护（按需迭代）

实施内容：

- 将 DeepSeek、Qwen、Kimi、MiMo、智谱、豆包等来源转成系统内置补充源；
- 由 TokenSea 产品版本统一维护；
- 失败仅降低覆盖率，不影响基础价格库；
- 不要求每个部署项目单独配置。

该阶段不是公共参考价格自动化上线的前置条件。

---

## 20. 文件级修改清单

### 后端新增

```text
services/control-plane/src/main/java/com/tokensea/governance/pricing/reference/
├── ReferencePriceBootstrapService.java
├── ReferencePriceBundleLoader.java
├── BuiltInReferenceSourceCatalog.java
├── BuiltInReferenceSourceReconciler.java
├── ReferencePriceMergeService.java
├── ReferenceModelMatcher.java
├── ReferencePriceCurrentResolver.java
├── ReferencePriceHealthService.java
└── ReferencePriceOverviewController.java
```

### 后端修改

```text
services/control-plane/src/main/java/com/tokensea/governance/
├── ProviderPriceSyncService.java
├── ProviderPriceSyncController.java
└── ProviderPriceCatalogService.java

services/control-plane/src/main/resources/application.yml
```

### 数据库

```text
services/control-plane/src/main/resources/db/migration/
└── V41__automatic_reference_price_bootstrap.sql
```

### 发布包数据

```text
services/control-plane/src/main/resources/reference-prices/
└── reference-price-bootstrap.json

configs/reference-prices/
└── builtin-sources.yml
```

### 前端

```text
apps/console/src/
├── pages/ReferencePriceOverview.vue
├── pages/DataPage.vue
├── config/resources.ts
├── config/menu.ts
└── router.ts
```

### 测试

```text
services/control-plane/src/test/java/com/tokensea/governance/pricing/reference/
├── ReferencePriceBootstrapServiceTests.java
├── BuiltInReferenceSourceReconcilerTests.java
├── ReferencePriceMergeServiceTests.java
├── ReferenceModelMatcherTests.java
└── ReferencePriceIsolationTests.java
```

---

## 21. 需要废止或降级的现有流程

### 不再作为正常业务流程

```text
逐个供应商新建价格源
逐个填写官方来源地址和官方域名
逐个点击测试获取
逐个点击测试解析
逐个点击启用
逐个模型审核公共价格差异
部署项目自行维护网页解析器
```

### 继续保留但降级为高级能力

```text
自定义价格源
HTML/CSV/JSON/PDF 通用提取
LLM Schema Mapper
原始快照
未映射记录
解析诊断
正式价格差异审核
合同价和供应商账单对账
```

正式价格差异审核只服务于未来可能存在的：

- 合同价格；
- 渠道实际成本；
- 供应商账单；
- 对客销售价。

不再服务于日常公共参考价更新。

---

## 22. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| LiteLLM 或 models.dev 更新延迟 | 参考价不是最新 | 展示更新时间，多来源交叉验证，保留官方补充源 |
| 上游字段结构变化 | 同步失败 | 原始快照、Schema 校验、最近成功数据兜底 |
| 上游删除大量模型 | 覆盖率骤降 | 数量异常保护，不立即批量删除当前参考价 |
| 同名模型归属错误 | 错误展示价格 | 供应商 + 模型 ID 联合键，禁止仅按模型短名合并 |
| 离线部署无法更新 | 数据逐渐过期 | 随发布包提供快照，升级包更新快照 |
| 参考价格被误认为实际成本 | 业务决策错误 | 全链路标记 `REFERENCE_ESTIMATE`，页面明确非结算 |
| 自动价格用于强制预算阻断 | 误阻断调用 | 默认仅作软预警，Token 和请求预算优先 |
| 官方网页解析维护成本高 | 研发负担 | 官网解析降级为补充源，失败不影响主链路 |

---

## 23. 最终推荐

TokenSea 当前不应继续投入大量资源，要求每个私有化部署项目逐个供应商、逐个模型维护公共价格。

推荐最终产品口径：

> TokenSea 自带一份可离线使用的公共模型参考价格库；部署后自动导入，联网后每天自动从 LiteLLM、models.dev 和少量系统内置官方来源更新。公共价格仅用于模型目录展示、成本趋势和参考估算，不参与正式结算。管理员正常情况下不需要配置、测试、启用或审核价格源，只在系统连续同步失败时查看运维告警。

该方案可最大程度复用当前已经建设的适配器、同步任务、快照和公共参考价格表，同时显著降低部署复杂度和后续运维成本，也更符合 TokenSea“统一模型接入和治理优先，价格只是辅助信息”的当前产品定位。
