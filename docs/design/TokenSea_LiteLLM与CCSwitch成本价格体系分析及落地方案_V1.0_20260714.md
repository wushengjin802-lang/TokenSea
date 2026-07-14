# TokenSea 基于 LiteLLM 与 CC Switch 的成本、价格体系分析及落地方案

版本：V1.0  
日期：2026-07-14  
适用范围：TokenSea MVP 后期、内部试点及企业版演进  
分析对象：LiteLLM、CC Switch、TokenSea 当前价格治理实现

---

## 1. 结论摘要

LiteLLM 和 CC Switch 对“价格与成本”的处理目标不同：

- **LiteLLM** 面向多供应商 AI Gateway，重点是覆盖尽可能多的模型计费规则，并在请求链路中自动计算 `response_cost`、Spend、预算和用量。
- **CC Switch** 面向本地桌面代理与开发者使用统计，重点是让用户直观看到请求量、Token、成本趋势和单请求明细，并能在本地直接维护模型价格。
- **TokenSea** 面向企业级统一 LLM API Gateway，不能照搬其中任何一个。它既需要 LiteLLM 的广泛计费维度和 Provider 语义，也需要 CC Switch 的清晰页面与请求级成本明细，还必须补上两者都不足的官方来源留证、价格版本、生效时间、差异审核、权限、审计和供应商对账。

推荐 TokenSea 采用以下组合方案：

```text
官方供应商价格源
+ LiteLLM Model Cost Map 参考源
+ models.dev 等公共参考源
+ 管理员受控修正
               │
               ▼
       价格源与原始快照层
               │
               ▼
       标准化价格目录层
               │
               ▼
       差异审核与版本发布
               │
               ▼
       模型部署生效价格层
               │
               ▼
Gateway 按实际路由与响应 usage 计算成本
               │
               ▼
        请求级不可变成本快照
               │
               ▼
   用量看板、成本分析、预算与对账
```

关键原则：

1. 数据库是 TokenSea 唯一运行时价格权威源。
2. LiteLLM 的成本表只作为数据面参考和补充，不直接等同于供应商官方价格。
3. CC Switch 的本地模型价格表适合个人工具，不足以直接支撑企业价格治理。
4. 官方价格优先，第三方目录仅作参考。
5. 价格变化必须新增版本，不覆盖历史价格。
6. 每次请求必须固化实际使用的模型部署、价格版本、Token 分类和计算结果。
7. 未知价格与明确零价格必须严格区分。
8. Gateway 不在请求过程中联网拉取价格。

---

## 2. 分析范围与方法

本次分析重点检查以下内容：

- 成本和价格页面展示；
- 前端数据结构；
- 数据库表结构；
- 请求成本计算逻辑；
- 原始价格数据来源；
- 数据加载、同步和更新方法；
- 未知价格、缓存 Token 和模型名称差异的处理；
- 对 TokenSea 的可复用点与不适用点。

主要代码与文档路径：

### LiteLLM

```text
BerriAI/litellm
model_prices_and_context_window.json
LiteLLM Spend Tracking 文档
LiteLLM Completion Token Usage & Cost 文档
LiteLLM Custom LLM Pricing 文档
LiteLLM Custom Model Cost Map 文档
LiteLLM Auto Sync New Models 文档
```

### CC Switch

```text
farion1231/cc-switch
src/components/usage/
src/types/usage.ts
src/lib/query/usage.ts
src-tauri/src/proxy/usage/calculator.rs
src-tauri/src/proxy/usage/logger.rs
src-tauri/src/commands/usage.rs
src-tauri/src/database/schema.rs
```

---

## 3. LiteLLM 成本与价格体系分析

## 3.1 产品定位

LiteLLM 是面向 100+ LLM Provider 的统一 SDK 和 AI Gateway。价格体系不是独立财务系统，而是服务于：

- 请求成本计算；
- Spend Tracking；
- Key、User、Team 预算；
- 模型路由与成本优化；
- 管理台 Usage 展示；
- 请求日志和响应头中的成本输出。

它的核心优势是“广覆盖的模型成本规则 + Provider 特殊计费语义”，而不是企业级价格审批和财务结算。

## 3.2 页面展示方式

LiteLLM 的价格和成本展示主要分为三类。

### 3.2.1 Models & Pricing

通过模型与价格目录展示模型信息，底层对应 `model_prices_and_context_window.json`。常见信息包括：

- 模型名称；
- Provider；
- 输入和输出价格；
- 上下文窗口；
- 输出长度；
- 模型模式；
- 能力参数；
- 缓存、长上下文、多模态等扩展计费字段。

这类页面本质上是“公共模型参考目录”，适合查询，不是企业内部的价格发布页面。

### 3.2.2 Usage / Spend 页面

管理台 Usage 页面主要展示：

- 总 Spend；
- 请求数；
- Token；
- 按日期趋势；
- 按模型、Provider、Key、User、Team 拆分；
- Spend Log 明细。

LiteLLM 会在响应头返回 `x-litellm-response-cost`，并把请求成本写入 `LiteLLM_SpendLogs`。Spend Log 包含 Key、User、Team、模型组、API Base、Spend、输入/输出 Token 等信息。

### 3.2.3 Pricing Calculator

Pricing Calculator 用于输入模型、Token 数量或调用参数后估算成本，适合开发者在调用前理解费用。

### 3.2.4 页面设计评价

优点：

- 用量与成本结合紧密；
- 支持按多业务维度统计；
- 模型成本字段覆盖广；
- 适合网关运营和预算治理。

不足：

- 公共模型目录、部署实际成本和商业售价边界不够突出；
- 缺少面向企业价格治理的“来源证据、版本、生效区间、审核状态”；
- UI 更偏 Gateway 工程管理，不是完整财务成本管理后台。

## 3.3 底层价格数据构造

LiteLLM 的核心价格数据是根目录中的：

```text
model_prices_and_context_window.json
```

它是以模型标识为 Key 的大规模 JSON 映射。每个模型记录不仅可以包含：

```text
input_cost_per_token
output_cost_per_token
max_input_tokens
max_output_tokens
litellm_provider
mode
```

还可以包含：

```text
cache_read_input_token_cost
cache_creation_input_token_cost
input_cost_per_token_above_200k_tokens
output_cost_per_token_above_200k_tokens
input_cost_per_image
input_cost_per_audio_token
output_cost_per_audio_token
input_cost_per_video_per_second
input_cost_per_character
input_cost_per_token_priority
output_cost_per_token_priority
input_cost_per_token_flex
output_cost_per_token_flex
```

这说明 LiteLLM 采用的是“可扩展字段式模型成本卡”，能够快速支持新计费维度。

### 3.3.1 优点

- 结构直观；
- 新模型可通过 GitHub PR 快速补充；
- 覆盖大量 Provider；
- 计费引擎可根据 Provider 响应元数据选择 Priority、Flex、Service Tier 等价格；
- 对长上下文、缓存、多模态等复杂场景支持较好。

### 3.3.2 局限

- JSON 是全局公共价格表，不是某企业的真实合同价格；
- 来源证据通常不与每条模型价格一一绑定；
- 缺少严格的价格版本和生效区间治理；
- GitHub 最新值发生变化后，运行时成本口径可能改变；
- 不适合直接作为企业财务对账的唯一依据。

## 3.4 原始数据来源

LiteLLM 文档明确把该价格表描述为社区维护的模型清单。通常由贡献者根据供应商公开价格、文档和 Provider 行为更新，再提交至 GitHub。

因此其价格来源应理解为：

```text
供应商公开价格
→ 社区或维护者整理
→ GitHub Model Cost Map
→ LiteLLM 加载
```

它不是逐个供应商实时调用官方价格 API 得到的统一数据，也不是企业客户账单数据。

## 3.5 数据加载和更新方法

LiteLLM 支持四种主要方式。

### 3.5.1 启动时拉取最新 Cost Map

默认情况下，Proxy 启动时从远程地址加载最新模型成本表。这样可以在不升级 LiteLLM 包的情况下获得新模型和新价格。

### 3.5.2 使用本地备份表

设置：

```text
LITELLM_LOCAL_MODEL_COST_MAP=True
```

后使用包内本地备份，适合离线环境，但更新新模型和价格需要升级包或重新构建镜像。

### 3.5.3 自定义远程 Cost Map

设置：

```text
LITELLM_MODEL_COST_MAP_URL=<企业托管的 JSON 地址>
```

可以替换整个全局成本表。LiteLLM 对远程表设置最小模型数量和最大缩减比例等校验，拉取失败时回退本地备份。

风险是：

- 整张表由企业自行维护；
- 拉取失败可能静默回退旧价格；
- 自定义表和上游新模型容易逐渐分叉。

### 3.5.4 手动或定时热更新

LiteLLM 提供：

```text
POST   /reload/model_cost_map
POST   /schedule/model_cost_map_reload?hours=6
DELETE /schedule/model_cost_map_reload
GET    /schedule/model_cost_map_reload/status
```

可以不重启服务更新 Model Cost Map。

### 3.5.5 单部署价格覆盖

在模型部署配置中可以设置：

```text
input_cost_per_token
output_cost_per_token
cache_read_input_token_cost
cache_creation_input_token_cost
base_model
```

以及长上下文和 Priority/Flex 价格。

单部署覆盖适合区域价格或少量专属价格修正，但如果覆盖不完整，可能出现高价计费维度仍使用基础价或遗漏的问题。因此需要把一组价格字段视为完整 Rate Card，而不是只改其中一个字段。

## 3.6 成本计算和日志

LiteLLM 根据：

```text
解析后的实际模型
+ Provider
+ 响应 usage
+ 服务层级元数据
+ Cost Map 或部署覆盖价格
```

计算 `response_cost`。

主要特征：

- 成功请求返回成本；
- Spend 按 Key、User、Team 等归因；
- Provider 特殊计费通过适配器处理；
- Azure 等部署可通过 `base_model` 解决请求名、部署名和响应模型名不一致；
- 缓存、推理、多模态和服务层级可使用不同价格字段。

## 3.7 对 TokenSea 的启示

应复用：

- 丰富的计费维度；
- Provider 特殊计费语义；
- `base_model`/真实计费模型映射思想；
- 请求响应中的 usage 和服务层级元数据；
- Cost Map 自动同步作为参考数据源；
- 未知模型和自定义模型价格覆盖机制。

不应直接照搬：

- 用一个全局 JSON 直接作为企业权威成本表；
- 运行时直接拉取社区最新表并立即改变成本口径；
- 缺少审核和生效时间的热更新；
- 把公共参考价、合同价和内部核算价放在同一层。

---

## 4. CC Switch 成本与价格体系分析

## 4.1 产品定位

CC Switch 是本地桌面工具，负责管理 Claude Code、Codex、Gemini CLI 等应用的 Provider 配置、代理和使用统计。

它的成本功能更偏向：

```text
本机请求日志
+ 本地模型价格
→ 本地成本统计和开发者看板
```

不是企业多租户计费平台。

## 4.2 页面展示方式

CC Switch 的 Usage 模块包含：

```text
UsageSummaryCards
UsageTrendChart
RequestLogTable
ProviderStatsTable
ModelStatsTable
PricingConfigPanel
RequestDetailPanel
```

页面提供：

- 时间范围筛选；
- Claude、Codex、Gemini 应用筛选；
- 总请求、Token、总成本等汇总；
- 成本和 Token 趋势；
- 请求日志；
- Provider 统计；
- 模型统计；
- 模型价格配置；
- 5、10、30、60 秒自动刷新或关闭刷新。

模型统计表包含：

```text
模型
请求数
Token
总成本
平均每请求成本
```

### 4.2.1 定价配置页面

`PricingConfigPanel.tsx` 提供两个层次。

第一层是应用级默认计费设置：

- Claude、Codex、Gemini；
- 默认成本倍率；
- 计价模型来源：请求模型或响应模型。

第二层是模型价格表：

- 模型 ID；
- 显示名称；
- 输入价格/百万 Token；
- 输出价格/百万 Token；
- 缓存读取价格/百万 Token；
- 缓存写入价格/百万 Token；
- 新增、编辑、删除。

这套页面的优点是非常直观，适合开发者快速理解和修正价格。

## 4.3 前端数据结构

`src/types/usage.ts` 中，模型价格被定义为固定四项：

```text
inputCostPerMillion
outputCostPerMillion
cacheReadCostPerMillion
cacheCreationCostPerMillion
```

请求日志记录：

```text
requestId
providerId
appType
model
requestModel
costMultiplier
inputTokens
outputTokens
cacheReadTokens
cacheCreationTokens
inputCostUsd
outputCostUsd
cacheReadCostUsd
cacheCreationCostUsd
totalCostUsd
isStreaming
latencyMs
firstTokenMs
durationMs
statusCode
errorMessage
createdAt
dataSource
```

这套结构对代码 Agent 常见的文本与缓存计费很实用，但不足以覆盖图像、音频、视频、字符、秒、请求次数、长上下文阶梯和服务层级等复杂价格。

## 4.4 数据库存储

CC Switch 使用 SQLite 作为本地单一事实源，`model_pricing` 表结构为：

```sql
model_id TEXT PRIMARY KEY,
display_name TEXT NOT NULL,
input_cost_per_million TEXT NOT NULL,
output_cost_per_million TEXT NOT NULL,
cache_read_cost_per_million TEXT NOT NULL DEFAULT '0',
cache_creation_cost_per_million TEXT NOT NULL DEFAULT '0'
```

金额使用字符串保存，计算时转换为 Rust `Decimal`，避免浮点精度问题。

请求成本则直接固化在 `proxy_request_logs`：

```text
input_cost_usd
output_cost_usd
cache_read_cost_usd
cache_creation_cost_usd
total_cost_usd
cost_multiplier
```

同时保存请求模型、真实模型、Provider、Token、延迟和状态。

日聚合表 `usage_daily_rollups` 保存按日期、应用、Provider 和模型的请求数、Token、总成本和平均延迟，减少长期明细查询压力。

## 4.5 原始价格数据来源

CC Switch 的核心权威数据是本地 SQLite `model_pricing` 表。

价格数据进入方式主要包括：

1. 数据库初始化或迁移时写入内置种子价格；
2. 用户在定价配置页面新增或修改；
3. 使用 `INSERT OR REPLACE` 更新本地价格；
4. 仓库中存在 `ModelsDevPickerDialog.tsx`，说明 UI 还预留了从 models.dev 一类公共模型目录选择模型的入口，但最终生效价格仍需落入本地 `model_pricing`。

因此其数据来源更适合描述为：

```text
项目内置种子值
+ 公共模型目录辅助选择
+ 用户手工修正
→ 本地 SQLite 权威价格
```

它没有形成供应商官方来源证据、抓取快照、价格版本和审核链路。

## 4.6 数据更新方法

CC Switch 的价格更新路径非常直接：

```text
页面编辑
→ Tauri command
→ Decimal 非负校验
→ SQLite INSERT OR REPLACE
→ TanStack Query 失效并刷新页面
```

更新价格后，后端还会尝试回填该模型此前缺失的使用成本。

Usage 页面默认每 30 秒刷新，也支持用户切换刷新周期。请求数据通过本地代理日志和 Claude、Codex、Gemini、OpenCode 等会话日志同步进入数据库。

### 4.6.1 更新方式的优势

- 本地修改即时生效；
- 无需修改 YAML；
- 页面清晰；
- 使用 Decimal；
- 支持回填未知成本；
- 请求明细和统计保持一致；
- 本地 SQLite 便于备份和迁移。

### 4.6.2 更新方式的风险

- `INSERT OR REPLACE` 覆盖当前值，没有价格版本；
- 修改价格后回填历史成本，会改变历史口径，不适合企业对账；
- 未找到价格时成本记录为 0，容易把“未知”误认为“免费”；
- 固定四项价格不支持复杂计费；
- 无来源证据；
- 无生效时间；
- 无审批；
- 无多币种和区域；
- 无租户和模型部署维度。

## 4.7 成本计算逻辑

CC Switch 使用 Rust `Decimal` 计算：

```text
输入成本 = 可计费输入 Token × 输入单价 / 1,000,000
输出成本 = 输出 Token × 输出单价 / 1,000,000
缓存读取成本 = 缓存读取 Token × 缓存读取单价 / 1,000,000
缓存写入成本 = 缓存写入 Token × 缓存写入单价 / 1,000,000
总成本 = 四项之和 × 成本倍率
```

它区分不同协议的输入 Token 语义：

- Claude/Anthropic：输入 Token 已经是 Fresh Input，不再扣缓存；
- Codex/OpenAI Responses 和 Gemini：输入 Token 可能包含缓存读取和缓存写入，需要先扣除后计算 Fresh Input。

还支持：

- 请求模型与响应模型二选一作为计价模型；
- Provider 级倍率覆盖应用默认倍率；
- 找不到价格时返回无成本，并将日志成本写为 0；
- 记录请求级成本分项。

## 4.8 对 TokenSea 的启示

应复用：

- 定价页面的直观表格；
- 输入、输出、缓存读取、缓存写入分栏；
- 应用/Provider/模型/请求日志多视角统计；
- 请求模型与响应模型同时保存；
- 协议级 Token 语义修正；
- Decimal 高精度计算；
- 请求级分项成本快照；
- 定价缺失提醒；
- 实时或准实时看板刷新。

不应照搬：

- 单表覆盖式价格维护；
- 修改价格后重算历史成本；
- 未知价格记录为零；
- 固定四个价格字段；
- 缺少来源、版本、区域、生效区间和审核；
- 本地 SQLite 单用户模型。

---

## 5. LiteLLM 与 CC Switch 对比

| 维度 | LiteLLM | CC Switch | TokenSea 选择 |
|---|---|---|---|
| 产品定位 | 多 Provider AI Gateway | 本地桌面代理和统计 | 企业统一 LLM Gateway |
| 价格权威源 | GitHub Cost Map + 部署覆盖 | SQLite `model_pricing` | PostgreSQL 版本化价格目录 |
| 原始来源 | 社区整理的公开价格 | 内置种子、公共目录、手工配置 | 官方源优先，公共源参考 |
| 价格字段 | 高度扩展，支持多模态和阶梯 | 固定输入/输出/缓存读写 | 标准价格组件表 + 扩展字段 |
| 更新方式 | 启动拉取、热同步、自定义 Map | 页面 CRUD、SQLite 覆盖 | 定时同步、差异审核、版本发布 |
| 页面重点 | Spend、Usage、预算 | 开发者成本看板和价格编辑 | 官方目录、生效价格、成本分析、对账 |
| 精度 | Provider 适配器和 Cost Map | Rust Decimal | Decimal/Numeric，高精度 |
| 缓存语义 | Provider 级处理 | App 协议级处理 | Provider Adapter + Usage Normalizer |
| 价格版本 | 公共表当前值，部署覆盖 | 无 | 必须有 |
| 生效时间 | 支持有限 | 无 | 必须有 |
| 来源证据 | 不完整 | 无 | 必须有 |
| 审批审计 | 不以价格治理为核心 | 无 | 必须有 |
| 历史成本 | Spend Log | 可回填历史 | 请求快照不可变，禁止随意回填 |
| 未知价格 | 可自定义或报差异 | 写为 0 | 标记 UNKNOWN，生产发布受控 |
| 多租户 | Gateway 层支持 | 不支持 | 租户/项目/应用/Key 全归因 |

---

## 6. TokenSea 推荐价格分层

## 6.1 第一层：公共模型价格参考

实体：

```text
public_model_reference
```

来源：

- LiteLLM Model Cost Map；
- models.dev；
- 其他公开模型目录。

用途：

- 新模型识别；
- 模型名称和能力参考；
- 价格交叉核验；
- 价格缺失提醒；
- 发现官方价格可能已变化。

限制：

- 不直接参与生产成本计算；
- 不自动覆盖官方价格；
- 明确显示“公共参考”。

## 6.2 第二层：供应商官方价格目录

实体：

```text
provider_model_price_catalog
```

来源优先级：

```text
官方机器可读价格 API
> 官方价格文档或页面
> 官方控制台导出
> 管理员基于官方依据录入
```

关键字段：

```text
provider_type
provider_model_name
region
currency
billing_unit
request_mode
service_tier
context_tier
price_component
unit_price
source_type
source_ref
source_snapshot_id
source_updated_at
fetched_at
effective_from
effective_to
confidence
status
```

这是 TokenSea 官方公开成本口径的权威目录。

## 6.3 第三层：模型部署生效价格

实体：

```text
price_version
```

作用：

- 把官方模型价格绑定到具体模型部署；
- 支持模型名映射、区域、服务层级和上下文阶梯；
- 记录发布人、生效时间和审核状态；
- 保证任一时刻只有一个符合条件的生效版本。

当前阶段：

```text
price_layer = PROVIDER_OFFICIAL
```

后续可以扩展：

```text
CONTRACT_PRICE
INTERNAL_COST
SALES_PRICE
```

## 6.4 第四层：请求级成本快照

实体：

```text
usage_cost_snapshot
```

每次请求固化：

- request_id / call_id；
- 请求服务模型；
- 实际模型部署；
- Provider；
- 请求模型和响应模型；
- 价格解析模型；
- price_version_id；
- 输入、输出、缓存、推理、多模态等用量；
- 各分项价格；
- 各分项成本；
- 币种；
- 汇率版本；
- 总成本；
- Fallback 链路；
- 成本计算器版本。

快照一旦写入不得因后续价格变化被覆盖。

---

## 7. TokenSea 推荐价格组件模型

LiteLLM 的字段覆盖广，但将所有可能字段直接扩展为数据库列会越来越难维护。CC Switch 的固定四列又过于简单。

推荐采用“价格卡 + 价格组件”的规范化设计。

## 7.1 价格卡

```text
provider_price_card
```

字段：

```text
id
provider_model_name
region
currency
request_mode
service_tier
context_tier
effective_from
effective_to
source_id
status
```

## 7.2 价格组件

```text
provider_price_component
```

字段：

```text
price_card_id
component_type
unit_type
unit_size
unit_price
threshold_from
threshold_to
metadata_json
```

`component_type` 示例：

```text
INPUT_TOKEN
OUTPUT_TOKEN
CACHE_READ_TOKEN
CACHE_WRITE_TOKEN
REASONING_TOKEN
IMAGE_INPUT
IMAGE_OUTPUT
AUDIO_INPUT_TOKEN
AUDIO_OUTPUT_TOKEN
VIDEO_SECOND
CHARACTER
REQUEST
RERANK_UNIT
EMBEDDING_TOKEN
```

`unit_type` 示例：

```text
TOKEN
MILLION_TOKENS
THOUSAND_TOKENS
SECOND
IMAGE
REQUEST
CHARACTER
```

短期内可以继续保留当前常用字段，新增 `price_components_json` 兼容复杂模型；中期再拆成组件表。

---

## 8. 原始价格来源与更新策略

## 8.1 来源分级

| 等级 | 来源 | 可否自动生效 |
|---|---|---|
| A | 供应商官方价格 API | 低风险变化可自动发布 |
| B | 供应商官方价格页面/文档 | 连续验证后可自动发布 |
| C | 官方控制台导出或通知 | 需要审核 |
| D | LiteLLM Cost Map | 只作参考或待审核候选 |
| E | models.dev 等公共目录 | 只作参考 |
| F | 管理员手工录入 | 必须填写依据并审核 |

## 8.2 LiteLLM 数据同步方式

TokenSea 可以新增 `LITELLM_COST_MAP` 数据源适配器：

```text
定时下载固定版本或指定 Commit 的 JSON
→ 保存原始文件和 SHA-256
→ 解析模型与价格字段
→ 写入公共模型参考库
→ 与官方价格目录比较
→ 生成差异提醒
```

不建议直接跟随 `main` 分支即时改变生产价格。推荐：

- 每日获取；
- 保存 Commit SHA；
- 先进入参考库；
- 只有经官方来源确认后才进入官方价格目录；
- 新模型可以自动创建待审核参考记录；
- 生产价格版本必须独立发布。

## 8.3 CC Switch / models.dev 数据同步方式

不建议导入 CC Switch 的种子数据库作为生产价格源。

可以借鉴其 `ModelsDevPickerDialog` 思路，把 models.dev 作为公共参考数据源：

```text
models.dev 数据
→ 公共模型参考库
→ 与供应商模型发现结果匹配
→ 提示能力与参考价格
→ 不自动生效
```

CC Switch 的页面编辑体验可以复用，但管理员修改后应生成候选版本，而不是直接覆盖当前值。

## 8.4 官方价格同步

推荐最终权威流程：

```text
官方价格源
→ 受控出口代理
→ 原始快照
→ Provider Price Adapter
→ 标准化价格组件
→ 差异比较
→ 自动发布或人工审核
→ 官方价格目录
→ 模型部署价格版本
```

---

## 9. TokenSea 成本计算引擎

## 9.1 输入数据

```text
request_model
response_model
provider_type
model_deployment_id
usage 原始数据
service_tier
region
request_mode
price_version
fallback_attempt
```

## 9.2 Usage Normalizer

不同协议的 Token 语义必须先标准化。

标准结果：

```text
fresh_input_tokens
output_tokens
cache_read_tokens
cache_write_tokens
reasoning_tokens
image_count
image_units
audio_input_tokens
audio_output_tokens
video_seconds
```

适配规则由 Provider Adapter 维护，不能让通用成本公式猜测。

## 9.3 计价模型解析顺序

```text
明确指定的 pricing_model
→ 模型部署 base_model
→ 响应真实模型
→ 请求真实 Provider 模型
→ 官方别名映射
→ 无匹配则 PRICE_NOT_CONFIGURED
```

必须同时保存请求模型、响应模型和最终计价模型。

## 9.4 价格选择

按以下条件选择唯一价格卡：

```text
模型部署
+ Provider
+ 区域
+ 服务层级
+ 请求模式
+ 上下文阶梯
+ 请求发生时间
```

## 9.5 计算精度

- Java 使用 `BigDecimal`；
- Python 使用 `Decimal`；
- PostgreSQL 使用 `NUMERIC`；
- 禁止核心金额使用 float/double；
- 每项成本独立计算后再汇总；
- 统一规定舍入模式和保留精度。

## 9.6 Fallback 成本

Fallback 场景必须记录每次实际尝试：

```text
request_attempt
```

最终成本可以包含：

- 成功部署成本；
- 已产生可计费用量的失败尝试成本；
- 供应商返回但请求中断的部分用量；
- 重试次数。

不能只按用户请求的服务模型价格估算。

## 9.7 未知价格与零价格

必须使用三态：

```text
KNOWN_PRICE      已知付费价格
EXPLICIT_ZERO    明确免费或内部零价格
UNKNOWN_PRICE    未配置或无法匹配
```

规则：

- `UNKNOWN_PRICE` 不等于 0；
- 生产模型发布前默认要求价格完整；
- 未知价格产生告警；
- 测试环境可允许只记录用量；
- 零价格必须有明确来源和审批。

---

## 10. 页面设计方案

## 10.1 供应商官方价格目录

参考 LiteLLM 的广泛价格字段和 CC Switch 的清晰表格。

列表字段：

```text
供应商
模型
区域
调用模式
价格摘要
币种
来源类型
来源更新时间
TokenSea 同步时间
生效时间
版本
状态
风险提示
```

价格摘要默认展示：

```text
输入
输出
缓存读取
缓存写入
```

点击详情再展示长上下文、Priority、Flex、多模态等复杂组件。

操作：

- 查看详情；
- 查看来源；
- 查看历史版本；
- 立即同步；
- 重新匹配模型部署；
- 提交修正；
- 停用。

## 10.2 价格差异审核

展示：

```text
旧值
新值
变化比例
来源证据
影响的模型部署
预计成本影响
风险等级
```

操作：

- 审核通过并发布；
- 驳回；
- 暂缓；
- 修正模型映射；
- 仅更新公共参考库。

## 10.3 模型生效价格

只展示当前部署实际使用的价格版本：

```text
企业服务模型
模型部署
Provider
价格版本
价格层
生效时间
来源
当前状态
```

不要求管理员逐个手工填写；优先自动匹配官方价格目录。

## 10.4 用量与成本分析

结合 LiteLLM 与 CC Switch：

- 总请求、Token、成本；
- 成本趋势；
- 按租户、项目、应用、Key、用户、服务模型、Provider、部署拆分；
- 输入、输出、缓存成本占比；
- 未知价格请求；
- Fallback 成本；
- 平均每请求成本；
- Provider 账单偏差；
- 价格版本切换前后成本变化。

## 10.5 请求详情

展示：

```text
请求身份
路由链路
Token 分项
价格组件
成本公式
价格版本
来源快照
最终成本
```

管理员应能够解释“为什么这次请求计算为该金额”。

---

## 11. 当前 TokenSea 实现的衔接

当前已有：

```text
provider_model_price_catalog
price_version
PROVIDER_OFFICIAL
模型发现后自动匹配
价格缺失告警
usage_cost_snapshot
```

推荐保持现有主链不变：

```text
外部价格源
→ provider_model_price_catalog
→ 自动匹配模型部署
→ price_version(PROVIDER_OFFICIAL)
→ Gateway 成本计算
→ usage_cost_snapshot
```

新增能力：

```text
provider_price_source
provider_price_sync_run
provider_price_raw_snapshot
provider_price_diff
provider_price_component
```

LiteLLM 和 models.dev 进入公共参考库，不直接覆盖 `price_version`。

---

## 12. 分阶段开发计划

## 12.1 P0：完善当前价格闭环

1. 明确未知价格与零价格；
2. 请求级保存计价模型和价格版本；
3. 保存输入、输出、缓存读写成本分项；
4. 对 Request/Response 模型差异进行显式解析；
5. LiteLLM Cost Map 同步到公共参考库；
6. 价格目录增加来源、抓取时间、版本和证据哈希；
7. 价格差异审核；
8. 模型发现后自动匹配官方价格；
9. 价格缺失告警；
10. 用量与成本页面增加未知价格统计。

## 12.2 P1：官方价格自动同步

1. 价格源管理；
2. 官方 API 适配器；
3. 官方页面适配器；
4. 原始快照；
5. 自动差异计算；
6. 低风险自动发布；
7. 动态出口白名单；
8. 供应商价格过期告警；
9. 价格组件表；
10. 价格同步任务页面。

## 12.3 P2：企业成本治理

1. 合同价；
2. 内部核算价；
3. 销售价；
4. 汇率和税费版本；
5. 供应商账单自动对账；
6. 成本偏差归因；
7. 按成本路由；
8. 价格模拟；
9. 毛利和套餐。

---

## 13. 验收标准

### 13.1 数据来源

- 每条生产生效价格可以追溯到来源；
- 公共参考价不会自动覆盖官方价格；
- LiteLLM 同步记录 Commit 或内容哈希；
- 手工修正必须填写依据。

### 13.2 数据更新

- 同步无变化时不创建新版本；
- 价格变化创建新版本；
- 高风险变化进入审核；
- 同步失败保留旧价格；
- 不需要修改 YAML 或 `.env` 才能维护日常价格。

### 13.3 成本计算

- 使用高精度 Decimal；
- 缓存 Token 不重复计费；
- Request/Response 模型不一致时可解释；
- Fallback 按实际部署计费；
- 未知价格不记录为零；
- 历史请求成本不会因新价格发布而变化。

### 13.4 页面

- 能查看价格来源、版本和生效时间；
- 能查看新旧价格差异；
- 能查看请求级成本公式；
- 能筛选租户、项目、应用、Key、模型和 Provider；
- 能识别未知价格请求和异常价格。

### 13.5 对账

- 同一时间范围内 TokenSea 用量、成本快照和供应商账单可以关联；
- 能区分 Token 差异、模型映射差异、价格差异、汇率差异和税费差异。

---

## 14. 最终建议

TokenSea 应采用“LiteLLM 的计费知识覆盖 + CC Switch 的页面和请求成本体验 + 企业级价格治理”的组合设计。

具体落地口径：

```text
LiteLLM
用于 Provider 适配、复杂计费字段、Cost Map 参考和成本计算校验

CC Switch
用于借鉴定价编辑、请求日志、缓存分项、模型统计和 Decimal 计算体验

TokenSea 自研
负责官方价格源、数据库权威目录、价格版本、生效时间、审核、审计、租户归因、请求快照和供应商对账
```

不建议：

- 直接把 LiteLLM JSON 当成生产官方价格；
- 直接复制 CC Switch 的单表覆盖设计；
- 用 YAML 维护日常价格；
- 价格更新后批量重算历史请求；
- 未知价格按零价处理；
- 让 Gateway 在请求时访问外部价格源。

推荐最终数据链：

```text
官方价格源 / 公共参考源
→ 来源快照与标准化
→ 价格目录
→ 差异审核
→ 价格版本发布
→ 模型部署绑定
→ Gateway 实时计费
→ 请求级不可变成本快照
→ 成本分析、预算和对账
```

---

## 15. 参考资料

### LiteLLM

- GitHub：`https://github.com/BerriAI/litellm`
- Model Cost Map：`https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json`
- Completion Token Usage & Cost：`https://docs.litellm.ai/docs/completion/token_usage`
- Spend Tracking：`https://docs.litellm.ai/docs/proxy/cost_tracking`
- Custom LLM Pricing：`https://docs.litellm.ai/docs/proxy/custom_pricing`
- Custom Model Cost Map：`https://docs.litellm.ai/docs/proxy/custom_model_cost_map`
- Auto Sync New Models：`https://docs.litellm.ai/docs/proxy/sync_models_github`

### CC Switch

- GitHub：`https://github.com/farion1231/cc-switch`
- Usage UI：`src/components/usage/`
- Usage 类型：`src/types/usage.ts`
- Usage Query：`src/lib/query/usage.ts`
- Cost Calculator：`src-tauri/src/proxy/usage/calculator.rs`
- Usage Logger：`src-tauri/src/proxy/usage/logger.rs`
- Usage Commands：`src-tauri/src/commands/usage.rs`
- Database Schema：`src-tauri/src/database/schema.rs`
