# TokenSea 多源价格采集与通用文档提取实施说明

- **版本**：V1.0
- **日期**：2026-07-28
- **实施目录**：`D:\12_其他项目\30_APIGateway\tokensea`
- **实施范围**：第一阶段“减少供应商专用解析器”与第二阶段“通用价格文档提取引擎”
- **实施原则**：保留现有价格治理链路；不让第三方参考价或 LLM 抽取结果绕过价格差异审核；供应商账单只用于实际成本对账，不直接修改模型价格。
- **实施状态**：第一阶段与第二阶段代码已完成；Flyway 已升级至 V40，并在隔离 PostgreSQL 16 数据库中验证全新安装、脏 V6 升级、价格源字段持久化和 JSONB 审核恢复。

## 0. 本次最终完成范围

### 第一阶段闭环

- 价格目录认证改为独立 `PRICING_READ` 凭据，不再复用模型推理 Key；
- 供应商账单使用独立 `BILLING_READ` 凭据；
- Azure、AWS、Google 目录映射规则与未映射记录形成后台闭环；
- LiteLLM、models.dev 保持公共参考来源，数据库与后端共同禁止自动发布；
- OpenAI Costs 与通用账单 JSON 保存原始快照并进入供应商对账。

### 第二阶段闭环

- JSON、CSV/TSV、HTML、纯文本和文本型 PDF 统一进入通用文档适配器；
- JSON 字段路径实行白名单、最大深度和脚本表达式禁用；
- CSV 支持 UTF-8 BOM、逗号/制表符/分号、说明行跳过、表头行和行数上限；
- HTML、JSON、CSV 保存行级原文证据；PDF 保存页码、文本块和 PDFBox 坐标；
- 受控 LLM Schema Mapper 通过 TokenSea Gateway 专用 Virtual Key 调用批准模型；
- LLM 输出采用严格 JSON Schema、Prompt Injection 隔离、响应体上限和证据强制要求；
- 抽取运行、记录、证据、置信度、校验与人工审核全部持久化；
- 审核人员可接受、修正、驳回或标记非价格；所有待审核项处理完成后才允许提交到既有价格差异流程；
- Console 新增“价格文档提取审核”工作台。

### 明确保留到后续阶段

扫描型 PDF OCR、复杂跨页表格、多栏排版和大规模并行文档处理仍按方案保留为独立文档提取服务，不塞入 Headless Fetcher。本阶段完整支持的是可复制文本 PDF 及可由规则或受控 LLM 映射的文档。

---

## 1. 实施目标

原有价格采集主要依赖供应商官网 HTML 页面和供应商专用解析器。该方案能够保留官方证据，但存在以下维护问题：

1. 每增加一家供应商，通常需要新增一个页面解析器；
2. 官网 DOM、表格结构或动态渲染方式变化后，解析器可能失效；
3. 公开价、企业合同价与供应商实际扣费属于不同事实层，不能只靠网页公开价解决；
4. HTML、CSV、JSON、PDF 等官方价格文档缺少统一结构化入口；
5. LiteLLM、models.dev 等结构化参考源方便，但不应直接成为正式成本价。

本次实施后的总体目标是：

```text
机器可读官方目录 API
        +
公共结构化参考源
        +
供应商 Costs / 账单 API
        +
通用 HTML / CSV / JSON / PDF 提取
        +
少量供应商特殊解析器
        ↓
统一价格 Schema / 账单 Schema
        ↓
原始证据、差异判断、风险审核
        ↓
正式价格目录、价格版本、供应商对账
```

---

## 2. 实施边界

### 2.1 本次已实现

- Azure Retail Prices API 价格目录适配器；
- AWS Price List Bulk JSON 价格目录适配器；
- Google Cloud Billing Catalog SKU 价格适配器；
- LiteLLM、models.dev 原有公共参考源继续保留；
- OpenAI Costs API 账单适配器；
- 可配置的通用供应商账单 JSON 适配器；
- HTML table、CSV、JSON、PDF 通用价格文档入口；
- PDF 文本提取；
- 可选的 OpenAI-compatible LLM Schema 映射；
- 云价格目录分页聚合；
- 来源级最大响应体配置；
- 账单同步任务、账单证据、自动对账；
- Console 页面、菜单、表单预设和操作流程；
- Flyway 数据库迁移、单元测试、契约测试和实施文档。

### 2.2 本次没有取消的能力

以下现有解析器继续保留，用于供应商具有特殊定价结构、官网没有稳定机器可读接口或需要特殊语义判断的情况：

- `DEEPSEEK_OFFICIAL_PAGE`
- `QWEN_OFFICIAL_PAGE`
- `KIMI_OFFICIAL_PAGE`
- `XIAOMI_MIMO_OFFICIAL_PAGE`
- `ZHIPU_OFFICIAL_PAGE`
- `OFFICIAL_JSON`
- `OFFICIAL_CSV`

### 2.3 本次不做的事情

- 不把供应商账单金额反向推算并自动覆盖单价；
- 不把 LiteLLM 或 models.dev 的价格自动发布为正式官方价格；
- 不允许 LLM 抽取结果绕过价格差异审核；
- 不自动创建 Azure、AWS、Google 或 OpenAI 的真实凭据；
- 不在迁移中写入用户的真实账单源、API Key 或合同数据；
- 不重启或重新部署当前 Docker 服务。

---

## 3. 实施后的价格与成本事实分层

### 3.1 第一层：请求用量事实

来源：模型调用响应中的 `usage`、Gateway 统计和 Usage Outbox。

主要字段：

- 未缓存输入 Token；
- 缓存命中 Token；
- 缓存写入 Token；
- 输出 Token；
- 推理 Token；
- 图片、音频、请求次数等扩展计费单位。

用途：回答“本次请求用了多少”。

### 3.2 第二层：单位价格事实

来源优先级：

```text
企业合同价 CONTRACT_PRICE
  > 渠道实际价格 CHANNEL_ACTUAL
  > 供应商官方目录 PROVIDER_OFFICIAL
  > 公共参考来源 PUBLIC_REFERENCE
```

用途：回答“每个计费单位多少钱”，用于请求时实时成本估算。

### 3.3 第三层：供应商实际账单事实

来源：OpenAI Costs API 或其他供应商账单 JSON API。

用途：回答“供应商最终实际扣了多少钱”，用于与 TokenSea 的实时估算成本对账。

账单记录不会直接生成或修改 `price_version`，而是进入：

```text
provider_billing_record
        ↓
provider_reconciliation
        ↓
差异确认、解决与审计
```

---

## 4. 第一阶段实施：减少供应商专用解析器

## 4.1 Azure Retail Prices API

新增适配器：

```text
AZURE_RETAIL_PRICES
```

代码：

```text
services/control-plane/src/main/java/com/tokensea/governance/pricing/adapter/AzureRetailPriceAdapter.java
```

处理流程：

1. 读取 Azure Retail Prices 的 `Items` 数组；
2. 将 `serviceName`、`productName`、`skuName`、`meterName` 组合为 SKU 证据；
3. 通过价格源配置中的 `includePattern` 限定 Azure OpenAI 或目标产品；
4. 通过 `modelPattern` 或 `modelMappings` 提取供应商模型标识；
5. 根据输入、输出、缓存读取、缓存写入或请求计费关键词识别计费项；
6. 根据 `unitOfMeasure` 转换为每百万 Token，或按请求保存；
7. 按模型、区域、币种和调用模式聚合为统一价格记录；
8. 输出到既有价格差异与官方价格目录流程。

Azure Retail Prices API 采用 `NextPageLink` 分页。Control Plane 会自动获取后续页面，最多由价格源配置 `maxPages` 控制。

推荐配置示例：

```json
{
  "includePattern": "(?i)azure openai|openai",
  "modelPattern": "(?i)\\b(?<model>(?:gpt|o[134])[-a-z0-9.]+)\\b",
  "maxPages": 20,
  "maxResponseBytes": 10000000
}
```

## 4.2 AWS Price List Bulk

新增适配器：

```text
AWS_PRICE_LIST_BULK
```

代码：

```text
services/control-plane/src/main/java/com/tokensea/governance/pricing/adapter/AwsPriceListBulkAdapter.java
```

默认页面预设：

```text
https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/index.json
```

处理流程：

1. 读取 `products`；
2. 按 SKU 关联 `terms.OnDemand`；
3. 遍历 `priceDimensions`；
4. 使用产品属性、usage type、operation 和 dimension description 识别模型与计费项；
5. 按 `pricePerUnit` 和 `unit` 归一化；
6. 只处理 On-Demand 价格，不把 Reserved、合同折扣或促销价混入公开价；
7. 生成统一价格组件和证据。

AWS Bulk 文件可能大于普通价格页面，因此价格源可以配置：

```json
{
  "maxResponseBytes": 50000000
}
```

服务端硬上限为 50 MB，避免无限下载。

## 4.3 Google Cloud Billing Catalog

新增适配器：

```text
GOOGLE_CLOUD_CATALOG
```

代码：

```text
services/control-plane/src/main/java/com/tokensea/governance/pricing/adapter/GoogleCloudCatalogPriceAdapter.java
```

处理流程：

1. 读取 `skus`；
2. 读取 SKU `description`、`category` 和 `serviceRegions`；
3. 读取最新 `pricingInfo`；
4. 读取 `pricingExpression.tieredRates` 的首个公开价档；
5. 使用 `modelMappings` 或 `modelPattern` 将 SKU 描述映射为供应商模型；
6. 按区域、币种和调用模式聚合价格；
7. 使用 `nextPageToken` 获取后续页面。

Google Cloud Catalog 可以通过价格源配置复用供应商渠道托管凭据，并使用受控认证头：

```json
{
  "authHeader": "x-goog-api-key",
  "modelMappings": {
    "Gemini 2.5 Pro": "gemini-2.5-pro"
  },
  "maxPages": 20
}
```

为防止任意 HTTP Header 注入，服务端仅允许：

- `x-goog-api-key`
- `api-key`
- `x-api-key`

## 4.4 公共参考源

原有适配器继续使用：

```text
LITELLM_COST_MAP
MODELS_DEV
```

对应的数据仍标记为：

```text
sourceClass = PUBLIC_REFERENCE
```

这些来源可以用于：

- 补充模型目录；
- 发现新模型；
- 对比价格变化；
- 作为人工审核线索。

但不能在没有官方或合同证据时直接成为生产正式价格。

## 4.5 云目录通用匹配支持

新增公共支持类：

```text
services/control-plane/src/main/java/com/tokensea/governance/pricing/adapter/CatalogPriceAdapterSupport.java
```

提供：

- `modelPattern` 正则模型提取；
- `modelMappings` 显式 SKU 名称到模型 ID 映射；
- `includePattern`、`excludePattern`；
- 输入、输出、缓存读取、缓存写入和请求计费分类；
- `sourceBillingQuantity` 单位配置；
- 每 Token、每千 Token、每百万 Token 的统一换算；
- 标准价格组件生成；
- 模型、区域、币种和调用模式聚合。

价格目录适配器必须配置 `modelPattern` 或 `modelMappings`。服务端不会在缺少模型匹配规则时静默启用，以避免把非模型 SKU 误识别为模型价格。

---

## 5. 供应商 Costs / 账单 API 实施

## 5.1 新增账单数据模型

Flyway 迁移：

```text
services/control-plane/src/main/resources/db/migration/
V37__multi_source_price_catalogs_and_provider_billing.sql
```

新增表：

### `provider_billing_source`

保存：

- 账单源名称；
- 绑定的供应商渠道；
- 账单适配器；
- API 地址；
- 官方域名；
- 默认币种；
- 同步周期；
- 适配器配置；
- 状态及最近执行结果。

### `provider_billing_sync_run`

保存：

- 手工或定时触发；
- 同步周期；
- 同步状态；
- 获取记录数；
- 获取金额；
- 币种；
- 执行日志及错误。

### `provider_billing_record`

保存不可变账单证据：

- 账单周期；
- 金额与币种；
- 输入、输出 Token 和请求数；
- 账单项目；
- 供应商模型；
- 供应商项目；
- 来源地址；
- 原始 JSON；
- 证据哈希。

同一账单源下相同证据哈希不会重复写入。

### `provider_reconciliation`

新增：

```text
billing_source_id
billing_sync_run_id
```

用于追踪对账记录来自哪次账单同步。

## 5.2 OpenAI Costs API

新增适配器代码：

```text
OPENAI_COSTS_API
```

解析器：

```text
services/control-plane/src/main/java/com/tokensea/governance/ProviderBillingParser.java
```

同步服务：

```text
services/control-plane/src/main/java/com/tokensea/governance/ProviderBillingSyncService.java
```

默认地址：

```text
https://api.openai.com/v1/organization/costs
```

支持：

- `start_time`、`end_time`；
- `bucket_width=1d`；
- 最大 180 个时间桶；
- `next_page` 游标分页；
- `amount.value`；
- `amount.currency`；
- `line_item`；
- `project_id`。

OpenAI Costs API 需要组织管理员密钥。TokenSea 本次实现复用“供应商渠道托管凭据”，因此推荐为账单同步单独建立一个管理用途渠道，不要把组织管理员密钥与普通推理渠道密钥混用。

## 5.3 通用供应商账单 JSON

新增适配器：

```text
GENERIC_BILLING_JSON
```

必填配置：

```json
{
  "recordsPath": "data",
  "periodStartField": "period_start",
  "periodEndField": "period_end",
  "amountField": "amount"
}
```

可选配置：

```json
{
  "currencyField": "currency",
  "inputTokensField": "input_tokens",
  "outputTokensField": "output_tokens",
  "requestCountField": "request_count",
  "lineItemField": "line_item",
  "modelField": "model",
  "projectField": "project_id",
  "startParam": "start_time",
  "endParam": "end_time",
  "nextPageField": "next_page",
  "pageParam": "page",
  "timeFormat": "EPOCH_SECONDS",
  "lookbackDays": 7
}
```

同一次同步如果返回多个币种，系统会拒绝合并，并要求拆分账单源，避免把不同币种金额直接相加。

## 5.4 自动对账

每次账单同步成功后：

1. 按账单源和证据哈希写入 `provider_billing_record`；
2. 统计账单周期内该供应商渠道的 TokenSea 用量成本；
3. 使用现有 `tokensea_fx_amount` 将内部成本折算到账单币种；
4. 计算供应商金额与内部成本差异；
5. 创建或刷新 `provider_reconciliation`；
6. 保留账单源、同步任务和账单原始证据关联。

OpenAI Costs API不提供逐请求 Token，因此自动对账会明确把供应商 Token 标记为 `UNAVAILABLE`，不会伪造 Token 差异。

---

## 6. 第二阶段实施：通用价格文档提取引擎

## 6.1 新增适配器

```text
GENERIC_DOCUMENT
```

代码：

```text
services/control-plane/src/main/java/com/tokensea/governance/pricing/adapter/GenericDocumentPriceAdapter.java
```

支持的输入：

- `application/json`
- `text/csv`
- `text/html`
- `application/pdf`
- `text/plain`
- `application/octet-stream`

## 6.2 JSON 提取

支持：

- `recordsPath` 指定记录数组；
- 对象键作为模型 ID；
- 自定义模型、输入、输出、缓存、币种、区域、调用模式和计费单位字段；
- 单价倍率；
- 每 Token 价格转换为每百万 Token；
- 标准价格组件生成。

示例：

```json
{
  "recordsPath": "data.models",
  "modelField": "model_id",
  "displayNameField": "display_name",
  "inputField": "input_price",
  "outputField": "output_price",
  "cacheReadField": "cached_input_price",
  "cacheWriteField": "cache_write_price",
  "currencyField": "currency",
  "regionField": "region",
  "sourceBillingQuantity": 1000000,
  "llmEnabled": false
}
```

## 6.3 CSV 提取

支持：

- 带引号字段；
- 双引号转义；
- CRLF/LF；
- 自定义分隔符；
- 首行表头；
- 通用字段映射。

示例：

```json
{
  "delimiter": ",",
  "modelField": "model",
  "inputField": "input",
  "outputField": "output",
  "currencyField": "currency",
  "sourceBillingQuantity": 1000000
}
```

## 6.4 HTML table 提取

支持：

- 扫描页面所有 `table`；
- 通过 `tableIndex` 指定目标表；
- 首行作为表头；
- 表头标准化；
- 中文或英文表头字段映射；
- 原始 table、row 索引作为证据。

当官网具有复杂 rowspan、colspan、动态表头、阶梯价或特殊缓存语义时，仍应保留供应商专用解析器，不强行使用通用表格提取。

## 6.5 PDF 文本提取

依赖：

```xml
<dependency>
  <groupId>org.apache.pdfbox</groupId>
  <artifactId>pdfbox</artifactId>
  <version>3.0.8</version>
</dependency>
```

PDF 获取后以 Base64 形式进入价格快照，通用适配器使用 PDFBox 提取正文文本，再交给可选的 LLM Schema 映射。

PDF 原始字节仍由快照 checksum 和响应字节数保留证据，不把二进制内容错误地按 UTF-8 文本处理。

## 6.6 LLM Schema 映射

代码：

```text
services/control-plane/src/main/java/com/tokensea/governance/pricing/adapter/PriceDocumentLlmExtractor.java
```

LLM 只负责：

```text
已获取的官方文档正文
        ↓
映射到统一价格 Schema
```

LLM 不负责：

- 猜测缺失价格；
- 自动发布；
- 修改历史价格；
- 决定合同价；
- 绕过币种、单位和价格完整性校验。

输出 Schema 包括：

- `providerType`
- `providerModelName`
- `displayName`
- `currency`
- `billingBasis`
- `billingQuantity`
- `inputUnitPrice`
- `outputUnitPrice`
- `cacheReadUnitPrice`
- `cacheWriteUnitPrice`
- `region`
- `requestMode`
- `serviceTier`
- `contextTier`
- `evidence`

默认关闭：

```text
TOKENSEA_PRICE_DOCUMENT_LLM_ENABLED=false
```

启用配置：

```text
TOKENSEA_PRICE_DOCUMENT_LLM_ENABLED=true
TOKENSEA_PRICE_DOCUMENT_LLM_URL=https://llm.example.com/v1/chat/completions
TOKENSEA_PRICE_DOCUMENT_LLM_MODEL=your-structured-extraction-model
TOKENSEA_PRICE_DOCUMENT_LLM_API_KEY=REPLACE_WITH_SECRET
TOKENSEA_PRICE_DOCUMENT_LLM_MAX_CHARS=60000
```

LLM 请求使用现有 Egress Proxy 配置。API Key 只从环境变量读取，不写入价格源配置或数据库。

## 6.7 提取结果的安全门槛

无论结果来自 HTML、CSV、JSON、PDF 还是 LLM，后续都进入现有链路：

```text
原始快照
  → 标准化价格
  → 结构指纹
  → 价格差异
  → 风险等级
  → 确认次数
  → 人工批准或低风险自动发布
  → 官方价格目录
  → 渠道价格版本
```

高风险变化、新模型首价、结构变化、币种变化、计费项变化仍需要人工审核。

---

## 7. Control Plane 代码变更

### 7.1 新增文件

```text
services/control-plane/src/main/java/com/tokensea/governance/pricing/adapter/
  CatalogPriceAdapterSupport.java
  AzureRetailPriceAdapter.java
  AwsPriceListBulkAdapter.java
  GoogleCloudCatalogPriceAdapter.java
  GenericDocumentPriceAdapter.java
  PriceDocumentLlmExtractor.java

services/control-plane/src/main/java/com/tokensea/governance/
  ProviderBillingParser.java
  ProviderBillingSyncService.java
  ProviderBillingController.java

services/control-plane/src/main/resources/db/migration/
  V37__multi_source_price_catalogs_and_provider_billing.sql
```

### 7.2 修改文件

```text
services/control-plane/pom.xml
services/control-plane/src/main/resources/application.yml
services/control-plane/src/main/java/com/tokensea/governance/ProviderPriceSyncService.java
services/control-plane/src/main/java/com/tokensea/governance/ProviderPriceSyncController.java
services/control-plane/src/test/java/com/tokensea/FlywayUpgradeIntegrationTests.java
```

### 7.3 `ProviderPriceSyncService` 变化

- 支持 Azure `NextPageLink`；
- 支持 Google `nextPageToken`；
- 后续页面继续执行 HTTPS、官方域名和公网地址校验；
- 后续页面只有在原始主机一致时才携带托管凭据；
- 支持来源级 `maxPages`；
- 支持来源级 `maxResponseBytes`，硬上限 50 MB；
- 支持 PDF 二进制 Base64 快照；
- 支持受控自定义 API Key Header；
- Content-Type 增加 PDF、CSV、HTML、JSON 通用文档类型；
- 聚合结果写入 `_tokenseaPageCount` 作为分页证据。

### 7.4 `ProviderPriceSyncController` 变化

新增价格适配器白名单：

```text
AZURE_RETAIL_PRICES
AWS_PRICE_LIST_BULK
GOOGLE_CLOUD_CATALOG
GENERIC_DOCUMENT
```

新增校验：

- 云目录必须属于 `OFFICIAL`；
- 云目录必须配置 `modelPattern` 或 `modelMappings`；
- 通用文档必须配置确定性字段映射，或显式设置 `llmEnabled=true`；
- 自定义认证 Header 使用白名单；
- 保留 HTTPS、官方域名、币种、周期和风险参数校验。

---

## 8. Console 页面变更

## 8.1 价格源管理

路径：

```text
高级治理 → 价格源管理
```

新增适配器选项：

- Azure Retail Prices API；
- AWS Price List Bulk；
- Google Cloud Billing Catalog；
- 通用价格文档（HTML/CSV/JSON/PDF）。

选择适配器后，DataPage 会自动填充安全默认值：

- 来源类别；
- 获取方式；
- 官方域名；
- 默认币种；
- 建议 API 地址；
- 初始配置 JSON；
- 最大页数或响应大小。

新增通用能力：

```text
fieldPresets
```

实现文件：

```text
apps/console/src/pages/DataPage.vue
apps/console/src/config/resources.ts
```

该能力不是针对某一家供应商硬编码页面，而是通用资源表单的字段预设机制。

## 8.2 供应商账单源

路径：

```text
成本管理 → 供应商账单源
```

功能：

- 新建 OpenAI Costs API 或通用账单 JSON；
- 绑定供应商渠道凭据；
- 配置账单地址、官方域名和币种；
- 配置同步周期和回看天数；
- 测试账单；
- 手工指定时间范围立即同步；
- 启用或暂停定时同步；
- 查看最近成功、失败和错误原因。

## 8.3 账单同步任务

路径：

```text
成本管理 → 账单同步任务
```

展示：

- 账单源；
- 触发方式；
- 同步周期；
- 获取记录数；
- 金额与币种；
- 状态；
- 错误原因；
- 开始和完成时间。

## 8.4 供应商账单明细

路径：

```text
成本管理 → 供应商账单明细
```

展示：

- 账单周期；
- 账单项目；
- 供应商项目；
- 供应商模型；
- 金额和币种；
- Token 与请求数；
- 来源地址；
- 证据哈希；
- 原始 JSON。

## 8.5 供应商对账

现有“供应商对账”页面继续保留。自动账单同步生成的对账记录将携带：

```text
billingSourceId
billingSyncRunId
```

人工录入和自动同步两种对账方式可以同时存在。

---

## 9. 业务流程变化

## 9.1 公开价格同步流程

### 修改前

```text
供应商官网
  → 专用 HTML 解析器
  → 价格差异
  → 审核发布
```

### 修改后

```text
机器可读官方目录 API ─┐
公共结构化参考源 ─────┤
通用官方文档 ─────────┤→ 统一价格 Schema → 价格差异 → 审核发布
供应商特殊页面解析器 ─┘
```

## 9.2 通用文档流程

```text
管理员创建通用价格文档源
        ↓
配置官方地址和域名
        ↓
配置确定性字段映射
        ↓
测试获取 / 测试解析
        ↓
确定性提取成功？
   ├─ 是 → 标准价格记录
   └─ 否 → 是否显式启用 LLM？
             ├─ 否 → 保留快照与诊断，不发布
             └─ 是 → LLM Schema 映射
                         ↓
                   规则校验与价格差异
```

## 9.3 实际账单对账流程

```text
供应商账单源
  → Costs / Billing API
  → 分页获取
  → 标准账单记录
  → 证据哈希去重
  → 统计 TokenSea 同周期内部成本
  → 汇率折算
  → 创建或刷新供应商对账
  → 人工确认差异或解决
```

## 9.4 定时任务

账单调度默认每 5 分钟检查一次到期来源：

```text
TOKENSEA_PROVIDER_BILLING_SCHEDULER_MS=300000
```

每个账单源自己的同步周期使用 ISO-8601 Duration，例如：

```text
PT1H
PT6H
P1D
P7D
```

---

## 10. 安全设计

### 10.1 网络边界

价格目录和账单 API 都要求：

- HTTPS；
- 无 URL 用户信息；
- 主机必须在来源 `officialHosts` 中；
- 目标必须解析为公网地址；
- 未配置出口代理时，还必须进入全局出口硬白名单；
- 最多 3 次重定向；
- 重定向后重新执行主机和 DNS 校验；
- 只有原始主机一致时才发送托管凭据。

### 10.2 响应限制

- 普通价格源默认 5 MB；
- 来源可以提高 `maxResponseBytes`；
- 服务端硬上限 50 MB；
- 账单 API 单页及累计响应最大 5 MB；
- 云目录最大 100 页，默认 20 页；
- 账单最大 30 页；
- 单次价格同步最多 20,000 条标准化价格。

### 10.3 凭据

- 供应商凭据继续使用加密托管，并按 `INFERENCE`、`PRICING_READ`、`BILLING_READ` 三类用途隔离；
- 认证价格源必须选择同一供应商渠道下已启用的 `PRICING_READ` 凭据，启用和同步时均校验；
- 账单源必须选择同一渠道下已启用的 `BILLING_READ` 凭据，不保存或返回明文；
- LLM Mapper 仅接收 TokenSea Gateway 专用 Virtual Key，不直接接收供应商原始 Key；
- Google API Key Header 和通用账单认证头使用白名单；
- OpenAI 组织管理员 Key 不与普通推理渠道共用。

### 10.4 LLM 抽取

- 默认关闭，仅处理已获取并保存快照的官方价格文档；
- 只能调用 HTTPS Gateway，或 Compose 内可信的 `tokensea-gateway-runtime` HTTP 地址；
- Temperature 固定为 0，响应采用严格 JSON Schema，最大响应体 2 MB；
- 系统提示将文档正文声明为不可信输入，忽略文档内嵌指令，禁止猜测、补全和外部知识；
- 每条记录必须返回逐字原文证据，缺少证据不允许输出；
- 确定性结果优先，LLM 只补充未识别范围，重复模型范围由确定性结果覆盖；
- LLM、低置信度、校验警告和强制人工审核记录只进入审核工作台；
- 输出继续经过 Schema、币种、单位、价格组件一致性和证据校验，不允许直接发布。

---

## 11. 配置和部署变化

### 11.1 新增环境变量

```text
TOKENSEA_PRICE_DOCUMENT_LLM_ENABLED
TOKENSEA_PRICE_DOCUMENT_LLM_URL
TOKENSEA_PRICE_DOCUMENT_LLM_MODEL
TOKENSEA_PRICE_DOCUMENT_LLM_VIRTUAL_KEY
TOKENSEA_PRICE_DOCUMENT_LLM_MAX_CHARS
TOKENSEA_PROVIDER_BILLING_SCHEDULER_MS
```

已更新：

```text
deploy/compose/.env.example
deploy/compose/docker-compose.yml
services/control-plane/src/main/resources/application.yml
```

### 11.2 出口域名示例

`.env.example` 增加：

```text
prices.azure.com
pricing.us-east-1.amazonaws.com
cloudbilling.googleapis.com
raw.githubusercontent.com
models.dev
```

实际部署仍需管理员审核价格源中的 `officialHosts`。全局白名单不能替代来源级官方域名限制。

### 11.3 数据库迁移

服务重新部署后，由 Flyway 依次执行：

- `V37__multi_source_price_catalogs_and_provider_billing.sql`：机器可读目录与供应商账单；
- `V39__multi_source_pricing_governance.sql`：连接器治理、映射、未映射记录、凭据用途和账单快照；
- `V40__price_document_extraction_review.sql`：文档配置、抽取运行、记录级证据、审核及价格差异证据关联。

迁移不删除现有价格、用量、模型、供应商或账单证据。V40 对旧 `config` 使用容错型布尔/数字解析和上下限收敛，已验证 V1→V40 与脏 V6→V40。

---

## 12. 测试覆盖

新增和扩展测试：

```text
services/control-plane/src/test/java/com/tokensea/governance/pricing/adapter/
  MultiSourcePricingAdapterTests.java
  PriceDocumentLlmExtractorTests.java

services/control-plane/src/test/java/com/tokensea/governance/pricing/connector/
  PriceSourceConnectorRegistryTests.java

services/control-plane/src/test/java/com/tokensea/governance/pricing/mapping/
  PriceSourceMappingServiceTests.java

services/control-plane/src/test/java/com/tokensea/governance/pricing/extractor/
  PriceDocumentTypeDetectorTests.java
  PriceExtractionValidatorTests.java
  PriceDocumentExtractionServiceTests.java

services/control-plane/src/test/java/com/tokensea/governance/
  ProviderBillingParserTests.java
  ProviderPriceSourceGovernanceTests.java

services/control-plane/src/test/java/com/tokensea/provider/
  ProviderSecretControllerTests.java
  ManagedPurposeCredentialServiceTests.java
```

覆盖：

- Azure 输入价和输出价聚合；
- Google SKU 映射和价格归一化；
- AWS Product/Term/PriceDimension 关联；
- JSON 通用价格文档；
- CSV 通用价格文档；
- HTML table 通用价格文档；
- PDF 文本提取、页码、文本块坐标和确定性 `linePattern`；
- JSONPath 安全路径与脚本表达式拒绝；
- UTF-8 BOM、分号 CSV 与行级证据；
- 未配置 LLM Virtual Key 时的明确诊断；
- LLM 端点 HTTPS/可信内部 Gateway 限制；
- 置信度、价格组件一致性和证据校验；
- LLM/低置信度记录强制人工审核；
- PostgreSQL JSONB 审核后恢复为标准化价格；
- OpenAI Costs 时间桶解析；
- 通用账单 JSON 字段映射；
- Flyway 最新版本和账单表结构；
- Console 适配器、页面、菜单和时间范围表单契约。

最终验证结果：

- Control Plane 普通测试：154 项，0 失败、0 错误、16 项按 Live/外部环境条件跳过；
- 第二阶段定向样本：19 项，0 失败、0 错误；
- Flyway PostgreSQL 16：全新 V1→V40、脏 V6→V40、价格源字段持久化、JSONB 审核恢复四条路径逐项通过；
- Console：资源契约测试和生产构建通过；
- Compose：配置解析通过后方可部署，本次不重启现有服务。

真实 Azure、AWS、Google 和 OpenAI API 仍需要对应账号、Service ID、API Key 或组织管理员 Key，不能用伪造凭据替代真实供应商端到端验收。

---

## 13. 现阶段限制

### 13.1 云目录 SKU 不是统一模型 Schema

Azure、AWS、Google 的价格目录记录的是 SKU 或 Meter，不保证直接提供标准模型 ID。TokenSea 使用：

```text
modelMappings > modelPattern > 不生成价格
```

宁可不生成，也不猜测模型归属。

### 13.2 Google Catalog 需要真实 Service ID

Console 不自动猜测 Google Cloud Billing Catalog 的 Service ID。管理员需根据实际账号和服务目录配置完整 Endpoint。

### 13.3 AWS Bulk 文件较大

AWS Bulk JSON 可能较大。应使用 `includePattern` 尽量限定 Bedrock，并合理配置 `maxResponseBytes`。超过 50 MB 的文件应在外部预处理为受控 JSON/CSV，再通过通用文档适配器导入。

### 13.4 LLM 不能替代业务规则

复杂上下文阶梯、缓存 TTL、Batch 折扣、优先服务层级、音视频单位和工具调用可能无法仅靠基础字段表达。此类供应商仍保留特殊解析器或高级价格组件。

### 13.5 Costs API 不是单价 API

OpenAI Costs API返回组织账单金额，不返回每个模型的完整单位价格，也不保证包含逐请求 Token。它用于对账，而不是替代价格版本。

### 13.6 真实供应商端到端未在本次自动执行

本次没有：

- 启用真实云目录价格源；
- 上传真实 PDF 合同或报价单；
- 使用真实 OpenAI 组织管理员 Key；
- 修改现有业务数据库；
- 重启 Docker。

---

## 14. 推荐上线顺序

### 步骤一：部署代码和执行迁移

1. 备份 PostgreSQL；
2. 部署 Control Plane 和 Console；
3. Flyway 执行 V37；
4. 验证新增三张账单表；
5. 验证原有价格源和 Qwen/Kimi 等解析器仍正常。

### 步骤二：接入公共参考源

1. 保留 LiteLLM；
2. 保留 models.dev；
3. 检查来源仍为 `PUBLIC_REFERENCE`；
4. 禁止自动覆盖正式价格。

### 步骤三：逐个接入云目录

1. 先 Azure；
2. 再 AWS；
3. 再 Google；
4. 每个来源先保持 `DRAFT`；
5. 完成测试获取、测试解析和模型映射检查；
6. 确认价格差异符合预期后再启用。

### 步骤四：接入 OpenAI Costs API

1. 建立独立管理渠道；
2. 托管组织管理员 Key；
3. 创建账单源；
4. 先同步最近 1 天；
5. 对比 Usage 与 Costs；
6. 确认无币种和周期问题后启用定时同步。

### 步骤五：启用通用文档

1. 优先使用确定性 JSON/CSV/HTML 字段映射；
2. PDF 或复杂文档无法确定性解析时，再考虑 LLM；
3. LLM 始终保持人工审核；
4. 供应商特殊规则继续使用专用解析器。

---

## 15. 回滚策略

### 应用回滚

回滚 Control Plane 和 Console 代码即可停止使用新适配器和页面。

### 数据回滚

不建议直接回滚 V37，因为账单记录属于审计证据。推荐：

1. 将账单源状态改为 `PAUSED` 或 `DISABLED`；
2. 保留 `provider_billing_record`；
3. 回滚应用版本；
4. 确认不再需要数据后，通过单独审批的迁移处理，而不是手工删除表。

### 价格回滚

云目录和通用文档最终仍通过既有价格发布与撤销机制管理。误发布时使用“价格差异审核 → 撤销发布”，不删除原始快照。

---

## 16. 官方接口参考

- Azure Retail Prices API：<https://learn.microsoft.com/rest/api/cost-management/retail-prices/azure-retail-prices>
- AWS Price List Bulk API：<https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/price-changes.html>
- AWS Price List Bulk 文件：<https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/using-the-aws-price-list-bulk-api.html>
- Google Cloud Billing Catalog：<https://cloud.google.com/billing/docs/reference/rest/v1/services.skus/list>
- OpenAI Usage 与 Costs：<https://platform.openai.com/docs/api-reference/usage>
- Apache PDFBox：<https://pdfbox.apache.org/>

---

## 17. 最终实施结论

TokenSea 的价格获取方式已经从：

> 以供应商网页专用解析器为主

调整为：

> 官方机器可读目录优先、公共参考辅助、通用文档覆盖长尾、特殊解析器处理复杂规则、供应商账单负责最终成本校准

现有 Qwen、Kimi 等解析器没有删除，生产价格审核门槛没有降低，供应商账单和单位价格也保持了清晰的数据边界。
