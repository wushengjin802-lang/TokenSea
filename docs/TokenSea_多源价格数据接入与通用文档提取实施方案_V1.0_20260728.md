# TokenSea 多源价格数据接入与通用文档提取实施方案

- **版本**：V1.0
- **日期**：2026-07-28
- **适用项目**：TokenSea 企业级统一 LLM API Gateway
- **适用阶段**：价格治理架构升级、迭代排期、编码前评审
- **文档性质**：实施方案与完成情况基线；第一、第二阶段代码已于 2026-07-28 完成，真实供应商凭据验收需在部署环境另行执行
- **目标阶段**：
  - 第一阶段：减少供应商专用网页解析器数量
  - 第二阶段：建设通用文档提取引擎

---

## 0. 实施结果回填

### 0.1 第一阶段完成情况

- 已建立连接器注册中心、元数据 API 和 Console 配置基线；
- 已接入 LiteLLM、models.dev、Azure Retail Prices、AWS Price List、Google Cloud Catalog；
- 已建立 SKU 映射规则和未映射记录工作台；
- 已接入 OpenAI Costs 与通用账单 JSON，保存账单原始快照并进入供应商对账；
- 已将供应商密钥按 `INFERENCE`、`PRICING_READ`、`BILLING_READ` 隔离；价格目录和账单同步均不得复用推理 Key；
- 公共参考来源由数据库约束和后端校验共同禁止自动发布。

### 0.2 第二阶段完成情况

- 已支持 JSON、CSV/TSV、HTML、TEXT 和文本型 PDF 的统一确定性提取；
- 已支持安全 JSON 字段路径、嵌套字段、CSV BOM/分隔符/说明行和 PDF 页码/坐标证据；
- 已建设通过 TokenSea Gateway 专用 Virtual Key 调用的受控 LLM Schema Mapper；
- 已建设抽取运行、记录、证据、置信度、校验、记录级审核和提交价格差异闭环；
- 已新增 Console“价格文档提取审核”工作台；
- LLM、低置信度、校验警告或强制人工审核记录不得直接生成正式价格差异。

### 0.3 验证结果

- Control Plane 普通测试 154 项：0 失败、0 错误、16 项外部环境条件跳过；
- 第二阶段定向测试 19 项：0 失败、0 错误；
- PostgreSQL 16 上 V1→V40、脏 V6→V40、价格源字段持久化和 JSONB 审核恢复逐项通过；
- Console 资源契约和生产构建通过；
- 未使用伪造凭据执行 Azure、AWS、Google、OpenAI 的真实账号端到端调用。

### 0.4 保留边界

扫描型 PDF OCR、复杂跨页表格、多栏版式和超大批量文档仍按本方案原定边界交由后续独立文档提取服务处理；本轮完成的是文本型 PDF 和可由确定性规则或受控 LLM 映射的文档。

---

## 1. 编制背景

TokenSea 当前已经形成“价格源获取 → 原始快照 → 价格解析 → 标准化 → 价格差异 → 人工审核 → 官方价格目录 → 渠道价格版本 → 运行时成本计算”的完整治理闭环，并已针对 Qwen、Kimi、Xiaomi MIMO、智谱等供应商建设官方价格页面适配器。

现有方案能够满足 MVP 阶段价格自动同步要求，但继续按供应商增加 HTML 专用解析器会产生以下问题：

1. 每接入一家供应商，通常需要新增一个解析器和一组回归样本；
2. 供应商调整页面表格、文案、动态渲染方式或 URL 后，解析器可能失效；
3. 同一家供应商可能同时存在标准、Batch、缓存、长上下文、区域、服务层级等多类价格表，解析规则快速膨胀；
4. 网页解析只能获得公开目录价，无法反映云账号合同价、企业折扣和供应商实际账单成本；
5. LiteLLM、models.dev 等社区价格库已经提供结构化数据，但当前平台尚未形成统一参考源接入机制；
6. OpenAI Costs API、Google Cloud Billing Pricing API 等账单或账号价格接口，应纳入成本核对，而不是继续依赖官网页面估算全部成本。

因此，本方案不推翻现有价格治理闭环，而是在其基础上扩展“多源连接器 + 通用提取器 + 统一标准化 + 可信度治理 + 账单对账”能力，将网页专用解析器从主要接入方式逐步降级为必要的供应商特殊适配方式。

---

## 2. 与产品目标的关系

本方案延续 TokenSea PRD 中以下既定目标：

- 模型目录需要维护供应商、区域、上下文、输入/输出价格和能力信息；
- 价格表需要支持生效时间、版本、币种、供应商成本价、销售价和折扣；
- 用量记录必须包含模型、供应商、Token、成本、价格、状态和耗时；
- 计费记录必须可重放、可对账；
- LiteLLM 主要提供统一调用和 Spend Tracking，企业级价格、合同、账单和对账仍由 TokenSea 自研控制面负责；
- 产品架构继续采用“自研控制面 + Gateway 数据面 + LiteLLM Runtime Core”，避免把价格治理逻辑绑定到 LiteLLM 内部表结构。

本次升级不会改变 Gateway 的实时调用入口，也不会让第三方参考价直接覆盖正式成本价。升级重点位于控制面价格治理、后台管理页面、同步任务和账单核对流程。

---

## 3. 建设目标与非目标

### 3.1 建设目标

1. 将价格来源从“官方网页解析为主”升级为“官方目录 API、账号价格 API、参考数据集、官方文档、账单 API 并存”。
2. 复用当前 `provider_price_source`、同步任务、原始快照、差异审核和价格发布链路，不建立相互割裂的第二套价格系统。
3. 为 Azure、AWS、Google Cloud 建立机器可读价格目录连接器。
4. 将 LiteLLM 与 models.dev 接入为结构化公共参考源。
5. 将 OpenAI Costs API 和其他供应商账单接口接入为成本对账源。
6. 建立 HTML、JSON、CSV、PDF 的通用文档提取能力。
7. 仅在确定性提取不足时调用 LLM，将文档映射为统一价格 Schema。
8. 所有来源保留原始证据、同步版本、结构指纹和审核记录。
9. 正式运行时价格仍由 TokenSea 已发布价格版本提供，确保历史请求可按请求时价格重放。

### 3.2 非目标

本方案不包含：

- 用第三方价格库直接替代 TokenSea 正式价格目录；
- 从单次模型响应 `usage` 中推断供应商已经扣款的真实金额；
- 自动把 LLM 抽取结果直接发布到生产；
- 删除现有 Qwen、Kimi、Xiaomi MIMO、智谱等适配器；
- 修改 Gateway Virtual Key、路由、预算和调用协议；
- 在第一阶段建设完整发票 OCR、财务总账或合同管理系统；
- 对历史已发布价格进行无审核批量覆盖。

---

## 4. 当前代码与流程梳理

## 4.1 当前后端价格治理结构

当前主要代码集中在：

```text
services/control-plane/src/main/java/com/tokensea/governance/
├── ProviderPriceSyncController.java
├── ProviderPriceSyncService.java
├── PriceSourceParser.java
├── ProviderPriceCatalogController.java
├── ProviderPriceCatalogService.java
├── EffectiveCostPriceResolver.java
├── PricingComponentService.java
└── pricing/adapter/
    ├── PriceSourceAdapter.java
    ├── PriceSourceAdapterContext.java
    ├── PriceSourceAdapterRegistry.java
    ├── PriceSourceDocument.java
    ├── PriceSourceParseResult.java
    ├── OfficialPriceAnalyzer.java
    ├── OfficialHtmlPriceSupport.java
    ├── PriceStructureFingerprint.java
    ├── QwenOfficialPriceAdapter.java
    ├── KimiOfficialPriceAdapter.java
    ├── XiaomiMimoOfficialPriceAdapter.java
    └── ZhipuOfficialPriceAdapter.java
```

现有职责如下：

| 组件 | 当前职责 | 本方案处理方式 |
|---|---|---|
| `ProviderPriceSyncController` | 价格源 CRUD、测试获取、测试解析、同步、启停、差异审批 | 保留；扩展连接器配置、来源类型和诊断字段 |
| `ProviderPriceSyncService` | HTTP/Headless 获取、快照、解析、差异、发布、模型候选发现 | 保留为总编排器；拆出获取器、标准化器和对账处理器 |
| `PriceSourceParser` | 通用解析及标准化价格对象 | 保留兼容入口；逐步下沉为统一标准化层 |
| `PriceSourceAdapterRegistry` | 按 `adapterCode` 选择供应商专用适配器 | 扩展为连接器与提取器注册体系，兼容旧适配器 |
| `OfficialPriceAnalyzer` | HTML table 识别、表头判断和解析诊断 | 继续作为 HTML 通用提取器基础 |
| 供应商专用 Adapter | 处理供应商页面特有结构和计费语义 | 保留，仅承担特殊映射，不再承担所有获取与解析逻辑 |
| `ProviderPriceCatalogService` | 生成正式官方目录、部署价格、价格缺失告警 | 不改变正式发布职责 |
| `EffectiveCostPriceResolver` | 合同价、渠道实际价、供应商官方价的有效成本选择 | 保持现有优先级；新增来源只进入既有价格层 |

### 4.1.1 当前价格源获取能力

`ProviderPriceSyncService` 当前已具备：

- 普通 HTTP 获取；
- ETag、Last-Modified 等条件请求；
- Headless Fetcher 动态页面获取；
- 页面大小、Content-Type、HTTP 状态和内容哈希记录；
- 原始快照持久化；
- 内容未变化时跳过重新解析；
- 解析诊断、页面结构指纹和告警；
- 价格子页面发现；
- 价格差异审核；
- 正式价格目录和部署价格派生。

这些能力应继续复用。第一阶段重点不是重写同步服务，而是把 `fetch()` 和 `parseDetailed()` 从“网页特化”扩展为多连接器编排。

### 4.1.2 当前数据库基础

当前价格治理相关表已经包括：

- `provider_price_source`
- `provider_price_sync_run`
- 原始快照相关表
- `provider_price_diff`
- `provider_model_price_catalog`
- `price_version`
- 价格组件及部署价格关联表
- 告警、审计和治理 Outbox 相关表

现有 `provider_price_source` 已包含来源类别、来源类型、适配器代码、获取模式、认证模式、端点、币种、自动变更阈值、调度、状态等字段，并通过后续迁移增加了价格性质、优先级、结构指纹和解析诊断。

因此建议扩展现有表，不新增一套 `cloud_price_source` 或 `document_price_source` 平行模型。

## 4.2 当前 Console 前端结构

当前价格页面主要由通用 `DataPage.vue` 和资源配置驱动：

```text
apps/console/src/
├── pages/DataPage.vue
├── config/resources.ts
├── config/menu.ts
├── router.ts
└── api/client.ts
```

“价格源管理”目前已支持：

- 新建、编辑价格源；
- 测试获取；
- 测试解析；
- 立即同步；
- 启用、暂停；
- 同步任务和解析诊断查看；
- HTTP 状态、表格数量、匹配表格数量、生成价格记录数；
- 跳过原因、结构指纹、标准化价格样例；
- 与价格差异审核、官方价格目录、模型候选和部署价格链路衔接。

本方案优先扩展当前页面和详情 Tab，不立即建设完全独立的价格采集前端应用。

## 4.3 当前完整流程

```text
价格源配置
  ↓
定时任务或手工同步
  ↓
普通 HTTP / Headless 获取
  ↓
保存原始快照、Checksum、响应元数据
  ↓
PriceSourceAdapter / PriceSourceParser
  ↓
NormalizedPrice
  ↓
模型候选与价格标准化
  ↓
与当前目录比较
  ↓
价格差异审核
  ↓
供应商官方价格目录
  ↓
渠道部署价格版本
  ↓
EffectiveCostPriceResolver
  ↓
Gateway 请求时成本快照
```

目标架构必须继续使用该后半段闭环，只替换或扩展前半段“获取和提取”。

---

## 5. 目标总体架构

```text
┌─────────────────────────────────────────────────────────────┐
│                     Price Source Connectors                 │
├─────────────────────────────────────────────────────────────┤
│ 官方目录 API                                                │
│ Azure Retail Prices / AWS Price List / Google Billing       │
├─────────────────────────────────────────────────────────────┤
│ 公共参考数据                                                │
│ LiteLLM Cost Map / models.dev                               │
├─────────────────────────────────────────────────────────────┤
│ 成本与账单 API                                              │
│ OpenAI Costs / Provider Billing / Cloud Billing Export      │
├─────────────────────────────────────────────────────────────┤
│ 官方文档                                                    │
│ HTML / JSON / CSV / PDF / Headless                         │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│               Raw Snapshot & Evidence Layer                 │
│ 原始响应、文件、HTTP 头、版本、Checksum、抓取时间、凭据范围  │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│              Deterministic Extraction Layer                 │
│ API JSON Mapper / JSONPath / CSV / HTML Table / PDF Text    │
└─────────────────────────────────────────────────────────────┘
                              ↓ 低置信度或复杂语义
┌─────────────────────────────────────────────────────────────┐
│                LLM Schema Mapping Layer                     │
│ 严格 JSON Schema、证据定位、置信度、禁止自动发布             │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                Unified Price Normalization                  │
│ 模型、区域、场景、档位、组件、单位、币种、生效时间、来源可信度 │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│              Existing TokenSea Governance                   │
│ 差异检测 → 审核 → 正式目录 → 价格版本 → 运行时成本            │
└─────────────────────────────────────────────────────────────┘
                              ↘
┌─────────────────────────────────────────────────────────────┐
│                 Billing Reconciliation                      │
│ TokenSea 估算成本 ↔ 供应商账单实际成本 ↔ 差异与告警            │
└─────────────────────────────────────────────────────────────┘
```

### 5.1 核心设计原则

1. **连接器与解析器分离**：连接器解决“从哪里、如何获取”，提取器解决“文档是什么结构”，标准化器解决“如何映射到 TokenSea 价格语义”。
2. **原始证据优先**：任何价格记录必须可追溯到原始快照、API 版本或账单记录。
3. **正式价格唯一出口**：所有来源最终都经过当前价格差异和发布流程。
4. **第三方参考不自动发布**：LiteLLM、models.dev 只能作为参考证据或变化提示。
5. **账单不等于单价目录**：OpenAI Costs 等接口主要用于对账，不直接替代按模型、组件、档位维护的价格版本。
6. **LLM 只做映射，不做事实创造**：LLM 输出必须绑定原文证据，缺失字段必须返回空，不允许猜测。
7. **旧适配器持续可用**：新架构通过兼容层加载现有供应商适配器，避免一次性重写。

---

# 第一阶段：减少解析器数量

## 6. 第一阶段范围

第一阶段交付四类能力：

1. Azure、AWS、Google Cloud 官方价格目录 API；
2. LiteLLM、models.dev 公共参考数据；
3. OpenAI Costs API 及可扩展的供应商账单连接器；
4. 继续保留当前 Qwen、Kimi、Xiaomi MIMO、智谱及其他网页解析器。

第一阶段不引入 LLM 文档抽取，不改变现有供应商网页解析结果。

---

## 7. 第一阶段后端改造

## 7.1 新增连接器抽象

建议在以下目录新增：

```text
services/control-plane/src/main/java/com/tokensea/governance/pricing/connector/
├── PriceSourceConnector.java
├── PriceSourceConnectorContext.java
├── PriceSourceFetchResult.java
├── PriceSourceConnectorRegistry.java
├── HttpDocumentConnector.java
├── AzureRetailPriceConnector.java
├── AwsPriceListConnector.java
├── GoogleCloudCatalogConnector.java
├── LitellmReferenceConnector.java
├── ModelsDevReferenceConnector.java
└── OpenAiCostsConnector.java
```

接口建议：

```java
public interface PriceSourceConnector {
    boolean supports(String connectorCode);
    PriceSourceFetchResult fetch(PriceSourceConnectorContext context);
}
```

`PriceSourceFetchResult` 至少包含：

```text
sourceVersion
contentType
rawContent / rawObjectReference
checksum
responseBytes
httpStatus
etag
lastModified
nextCursor
recordCount
warnings
sourceMetadata
```

现有普通 HTTP 和 Headless 获取逻辑封装为 `HttpDocumentConnector`，从而保证旧来源行为不变。

## 7.2 调整同步服务职责

`ProviderPriceSyncService` 调整为：

```text
resolveConnector(source)
  ↓
connector.fetch(context)
  ↓
saveRawSnapshot(fetchResult)
  ↓
resolveExtractorOrAdapter(source)
  ↓
normalize(records)
  ↓
existing diff / review / publish flow
```

需要抽取的内部方法：

- `fetch()` → 迁移到连接器；
- `fetchHeadless()` → 由 `HttpDocumentConnector` 调用 Headless Client；
- API 翻页循环 → 由具体连接器处理；
- 内容校验和和快照保存 → 保留在同步服务公共层；
- `parseDetailed()` → 兼容当前 Adapter，同时支持连接器直接返回结构化记录。

建议增加统一中间对象：

```java
public record PriceSourceRecord(
    String externalId,
    String providerType,
    String providerModelName,
    String region,
    String requestMode,
    String serviceTier,
    String contextTier,
    String componentType,
    BigDecimal unitPrice,
    String currency,
    BigDecimal billingQuantity,
    String billingBasis,
    OffsetDateTime effectiveFrom,
    OffsetDateTime effectiveTo,
    Map<String, Object> attributes,
    Map<String, Object> evidence
) {}
```

连接器可直接返回 `PriceSourceRecord`，也可返回原始文档交给提取器。最终再聚合为当前 `NormalizedPrice`，以兼容现有发布链路。

## 7.3 Azure Retail Prices 连接器

### 7.3.1 获取方式

Azure 全球零售价格 API：

```text
https://prices.azure.com/api/retail/prices
```

能力特征：

- 无需认证；
- 支持 SKU、服务、区域等筛选；
- 单页最多约 1000 条；
- 通过 `NextPageLink` 翻页；
- 返回 `meterId`、`meterName`、`productName`、`skuName`、`armRegionName`、`unitOfMeasure`、`retailPrice`、`effectiveStartDate` 等字段；
- 中国区使用独立价格表下载接口，需要作为不同连接器配置处理。

### 7.3.2 配置示例

```json
{
  "connectorCode": "AZURE_RETAIL_PRICES",
  "cloudScope": "GLOBAL",
  "serviceFilters": ["Azure OpenAI", "Azure AI Foundry"],
  "regionFilters": ["eastus", "swedencentral"],
  "currencyCode": "USD",
  "apiVersion": "2023-01-01-preview",
  "mappingProfile": "AZURE_OPENAI_V1"
}
```

### 7.3.3 关键难点

Azure 返回的是计量 SKU，不一定直接等于模型名称，需要新增 SKU 映射规则：

```text
serviceName + productName + skuName + meterName + region
                         ↓
providerType + model + component + serviceTier + region
```

映射不能硬编码在连接器中，应配置在 `price_source_mapping_rule` 中。

## 7.4 AWS Price List 连接器

### 7.4.1 获取方式

优先支持两种模式：

1. Price List Query API：按服务与属性查询产品和价格；
2. Price List File API：获取指定服务、区域、生效日期的 JSON/CSV 价格文件。

AWS 连接器使用只读 IAM 凭据，通过 TokenSea 密钥托管引用，不在价格源配置中保存 Access Key 明文。

### 7.4.2 配置示例

```json
{
  "connectorCode": "AWS_PRICE_LIST",
  "serviceCode": "AmazonBedrock",
  "regionCodes": ["us-east-1", "us-west-2"],
  "currencyCode": "USD",
  "effectiveDateMode": "LATEST",
  "fileFormat": "json",
  "mappingProfile": "BEDROCK_MODEL_PRICING_V1"
}
```

### 7.4.3 关键难点

AWS Bedrock 价格可能包含：

- On-Demand；
- Batch；
- Provisioned Throughput；
- Prompt caching；
- 跨区域推理或服务层级；
- 图片、视频等非 Token 计费。

映射规则必须保留 `serviceTier`、`requestMode`、`billingBasis`，不能只生成输入价和输出价两个字段。

## 7.5 Google Cloud 价格连接器

### 7.5.1 两种模式

**公共目录模式**：Cloud Billing Catalog API，用于获取公开服务、SKU、区域和公开价格。

**账号价格模式**：Cloud Billing Pricing API，用于获取 Billing Account 可见价格和合同折扣价格。

### 7.5.2 配置示例

```json
{
  "connectorCode": "GOOGLE_CLOUD_PRICING",
  "pricingScope": "PUBLIC_CATALOG",
  "serviceDisplayNames": ["Vertex AI"],
  "currencyCode": "USD",
  "projectId": "finops-project",
  "billingAccountId": null,
  "mappingProfile": "VERTEX_AI_GENERATIVE_V1"
}
```

账号价格模式增加：

```json
{
  "pricingScope": "BILLING_ACCOUNT",
  "billingAccountId": "billingAccounts/XXXXXX-XXXXXX-XXXXXX"
}
```

### 7.5.3 认证与安全

- 公共目录可使用受限 API Key；
- 账号价格必须使用最小权限服务账号；
- 凭据只允许调用 Cloud Billing 只读 API；
- Egress Proxy 白名单增加 `cloudbilling.googleapis.com`；
- 同步日志不得输出 Access Token、API Key 或服务账号私钥。

## 7.6 LiteLLM 参考源连接器

### 7.6.1 数据来源

接入 LiteLLM 维护的模型价格与上下文数据文件，但必须固定以下信息：

- LiteLLM 版本或 Git commit；
- 原始 URL；
- 文件 checksum；
- 获取时间；
- 数据中的 provider/model key；
- 原始成本字段。

### 7.6.2 定位

LiteLLM 数据存在缺价、模型别名不一致和更新滞后风险，因此：

```text
sourceClass = PUBLIC_REFERENCE
priceNature = REFERENCE
trustLevel = COMMUNITY_REFERENCE
publishPolicy = MANUAL_ONLY
```

LiteLLM 数据仅用于：

- 提示当前正式价格可能过期；
- 补充模型上下文和能力参考；
- 与官方来源交叉核对；
- 新模型候选发现；
- 解析器失败时提供辅助证据。

不得直接覆盖 `PROVIDER_OFFICIAL` 或 `CONTRACT_PRICE`。

## 7.7 models.dev 参考源连接器

### 7.7.1 数据来源

```text
https://models.dev/api.json
```

models.dev 提供模型规格、能力、上下文和每百万 Token 价格，可直接映射：

```text
cost.input       → INPUT
cost.output      → OUTPUT
cost.cache_read  → CACHE_READ
cost.cache_write → CACHE_WRITE
cost.reasoning   → REASONING_OUTPUT
```

### 7.7.2 治理规则

- 保存整个 `api.json` 快照；
- 保存数据集更新时间和 checksum；
- 不依据远端文件原地变化修改历史快照；
- 所有价格按公共参考处理；
- 如果官方价格与 models.dev 冲突，以官方证据为准并生成参考源冲突提示；
- models.dev 模型 ID 与 TokenSea 供应商模型之间通过受控别名和映射规则关联。

## 7.8 OpenAI Costs API 与供应商账单连接器

### 7.8.1 正确定位

OpenAI Costs API 返回按时间桶、项目和 line item 聚合的实际费用，不是完整的逐模型单价目录。因此它应进入“成本对账域”，而不是直接进入正式价格目录。

流程：

```text
OpenAI Costs API（日级）
        ↓
provider_cost_record
        ↓
按供应商项目映射 TokenSea 渠道/租户
        ↓
聚合 usage_cost_snapshot
        ↓
估算成本 vs 实际账单成本
        ↓
差异率、覆盖率、未映射金额、告警
```

### 7.8.2 认证

- 使用 OpenAI Admin Key；
- 与推理 API Key 分开托管；
- 凭据用途标记为 `BILLING_READ`；
- 禁止 Gateway Runtime 获取该凭据；
- 只允许 Control Plane 后台同步任务解密使用；
- 审计每次凭据使用和同步任务。

### 7.8.3 对账粒度

第一阶段建议日级：

```text
providerInstanceId
providerAccountRef
providerProjectId
billingDate
lineItem
amount
currency
sourceRecordId
```

TokenSea 侧按相同时间窗聚合：

```text
usage_record + usage_cost_snapshot
```

输出：

- 平台估算成本；
- 供应商实际成本；
- 差异金额；
- 差异率；
- 已映射比例；
- 未映射供应商项目；
- 是否超过阈值；
- 处理状态。

### 7.8.4 其他供应商扩展方式

新增统一接口：

```java
public interface ProviderBillingConnector {
    boolean supports(String connectorCode);
    BillingFetchResult fetch(BillingConnectorContext context);
}
```

后续可实现：

- Azure Cost Management Export；
- AWS Cost and Usage Report；
- Google Cloud Billing Export；
- Anthropic Usage/Cost 管理接口；
- 国内供应商账单 API 或 CSV 导入。

账单连接器与价格目录连接器共享凭据、调度、快照和审计基础，但使用不同标准化对象，避免把账单金额错误解释为单价。

---

## 8. 第一阶段数据库改造方案

当前最新迁移为 V35。实施时建议按实际仓库状态顺延，不提前占用已被其他功能使用的版本号。

## 8.1 扩展 `provider_price_source`

建议新增或通过现有 `config` 固化以下语义：

| 字段 | 类型 | 说明 |
|---|---|---|
| `connector_code` | varchar | 获取连接器，如 `AZURE_RETAIL_PRICES` |
| `data_scope` | varchar | `PUBLIC_CATALOG`、`ACCOUNT_PRICING`、`REFERENCE_DATASET`、`DOCUMENT` |
| `trust_level` | varchar | `OFFICIAL_PUBLIC`、`OFFICIAL_ACCOUNT`、`COMMUNITY_REFERENCE`、`LLM_EXTRACTED` |
| `publish_policy` | varchar | `AUTO_LOW_RISK`、`MANUAL_ONLY`、`RECONCILIATION_ONLY` |
| `schema_version` | varchar | 连接器输出 Schema 版本 |
| `credential_purpose` | varchar | `NONE`、`PRICING_READ`、`BILLING_READ` |
| `mapping_profile` | varchar | SKU/模型映射配置版本 |

为了最小化表结构变更，连接器特有参数继续保存在 `config jsonb` 中；需要列表筛选和治理判断的字段采用独立列。

## 8.2 新增映射规则表

```sql
price_source_mapping_rule
- id
- price_source_id
- mapping_profile
- external_service
- external_product
- external_sku
- external_meter
- external_model_pattern
- target_provider_type
- target_model_name
- target_component_type
- target_request_mode
- target_service_tier
- target_context_tier
- target_region
- billing_basis
- billing_quantity
- transform_config jsonb
- priority
- status
- created_at
- updated_at
```

用途：

- 将 Azure/AWS/Google SKU 映射到 TokenSea 模型与组件；
- 将 LiteLLM/models.dev 模型标识映射到供应商模型；
- 将映射逻辑从 Java 代码移出；
- 支持映射规则版本化、测试和人工修正。

## 8.3 新增账单对账表

```sql
provider_cost_sync_run
provider_cost_record
provider_cost_reconciliation
```

核心字段：

```text
syncRunId
providerInstanceId
providerAccountRef
providerProjectRef
billingStart
billingEnd
lineItem
actualAmount
currency
estimatedAmount
varianceAmount
varianceRatio
mappingStatus
reconciliationStatus
rawSnapshotId
```

## 8.4 扩展数据库约束

更新当前 `adapter_code`、`fetch_mode`、`auth_mode`、`source_type` 和 `source_class` 检查约束，支持：

```text
AZURE_RETAIL_PRICES
AWS_PRICE_LIST
GOOGLE_CLOUD_CATALOG
GOOGLE_CLOUD_ACCOUNT_PRICING
LITELLM_COST_MAP
MODELS_DEV_REFERENCE
OPENAI_COSTS
GENERIC_JSON
GENERIC_CSV
GENERIC_PDF
GENERIC_DOCUMENT_LLM
```

需要兼容旧值，不能删除现有 Qwen、Kimi、MIMO、智谱适配器代码。

---

## 9. 第一阶段 API 改造

## 9.1 扩展现有价格源接口

继续使用：

```text
GET    /api/provider-price-sources
POST   /api/provider-price-sources
PATCH  /api/provider-price-sources/{id}
POST   /api/provider-price-sources/{id}/test-fetch
POST   /api/provider-price-sources/{id}/test-parse
POST   /api/provider-price-sources/{id}/sync
POST   /api/provider-price-sources/{id}/enable
POST   /api/provider-price-sources/{id}/pause
```

请求增加：

```json
{
  "connectorCode": "AZURE_RETAIL_PRICES",
  "dataScope": "PUBLIC_CATALOG",
  "trustLevel": "OFFICIAL_PUBLIC",
  "publishPolicy": "MANUAL_ONLY",
  "credentialRef": null,
  "credentialPurpose": "NONE",
  "mappingProfile": "AZURE_OPENAI_V1",
  "config": {}
}
```

## 9.2 新增连接器元数据接口

```text
GET /api/provider-price-connectors
GET /api/provider-price-connectors/{code}/schema
```

返回：

- 连接器名称；
- 数据类别；
- 是否需要凭据；
- 配置字段 Schema；
- 默认端点；
- 支持币种；
- 支持分页方式；
- 支持的发布策略；
- 安全白名单要求。

前端根据 Schema 动态展示配置字段，避免把所有云厂商参数写死在 `resources.ts`。

## 9.3 新增映射规则接口

```text
GET    /api/provider-price-sources/{id}/mappings
POST   /api/provider-price-sources/{id}/mappings
PUT    /api/provider-price-sources/{id}/mappings/{mappingId}
DELETE /api/provider-price-sources/{id}/mappings/{mappingId}
POST   /api/provider-price-sources/{id}/mappings/test
GET    /api/provider-price-sources/{id}/unmapped-records
```

## 9.4 新增账单对账接口

```text
GET  /api/provider-cost-sync-runs
POST /api/provider-cost-sources/{id}/sync
GET  /api/provider-cost-records
GET  /api/provider-cost-reconciliations
POST /api/provider-cost-reconciliations/{id}/confirm
POST /api/provider-cost-reconciliations/{id}/ignore
```

账单同步和价格发布分离，不允许账单接口直接调用价格发布方法。

---

## 10. 第一阶段 Console 前端改造

## 10.1 价格源新建/编辑表单

“高级治理 → 价格源管理”表单增加第一层“来源方式”：

```text
官方目录 API
账号价格 API
公共参考数据
官方网页或文档
供应商账单/成本 API
人工维护
```

选择后再展示连接器：

```text
官方目录 API
├── Azure Retail Prices
├── AWS Price List
└── Google Cloud Catalog

账号价格 API
└── Google Cloud Billing Account Pricing

公共参考数据
├── LiteLLM Cost Map
└── models.dev

官方网页或文档
├── 现有 Qwen/Kimi/MIMO/智谱适配器
└── 通用 HTTP/Headless

供应商账单/成本 API
└── OpenAI Costs
```

### 10.1.1 动态字段

连接器元数据驱动字段，例如：

- Azure：云范围、服务筛选、区域筛选、币种、API 版本；
- AWS：凭据、服务代码、区域、币种、文件格式；
- Google：项目、API Key/服务账号、Billing Account、SKU 范围；
- LiteLLM：版本或 commit、远端地址、更新策略；
- models.dev：数据地址、快照策略；
- OpenAI Costs：管理员凭据、组织、项目映射、同步窗口。

## 10.2 列表字段调整

价格源列表增加：

- 来源方式；
- 连接器；
- 数据范围；
- 可信等级；
- 发布策略；
- 映射覆盖率；
- 最近获取记录数；
- 最近未映射记录数；
- 最近同步状态。

原有 HTTP 地址、获取模式和适配器代码保留在详情中，列表避免过宽。

## 10.3 测试获取/解析报告增强

现有解析报告扩展为通用“连接器测试报告”：

```text
连接器状态
HTTP/API 状态
认证状态
页数/游标次数
原始记录数
映射成功数
未映射数
标准化价格数
数据集版本
Checksum
币种分布
区域分布
计费组件分布
警告
```

对于网页来源继续展示：

- 页面表格数；
- 匹配价格表数；
- 跳过原因；
- Headless 建议；
- HTML 结构指纹。

## 10.4 新增“价格映射”详情 Tab

在价格源详情中增加：

- 映射规则；
- 未映射记录；
- 映射测试；
- 最近一次映射覆盖率；
- 外部 SKU → TokenSea 模型/组件预览。

不建议第一阶段增加独立一级菜单，先作为价格源详情 Tab，避免菜单膨胀。

## 10.5 新增“成本对账”页面

建议放在：

```text
成本管理
└── 供应商成本对账
```

页面包括：

- 对账周期；
- 供应商渠道；
- 供应商项目；
- TokenSea 估算成本；
- 供应商实际成本；
- 差异金额；
- 差异率；
- 映射覆盖率；
- 状态；
- 查看明细；
- 确认或忽略差异。

该页面与“用量分析”“成本报表”关联，但不修改现有账单统计口径。

---

## 11. 第一阶段业务流程

## 11.1 官方目录 API 流程

```text
管理员创建官方目录价格源
  ↓
配置连接器、筛选条件、映射配置、同步周期
  ↓
测试连接与测试解析
  ↓
启用价格源
  ↓
调度器调用云厂商价格 API
  ↓
完成分页并保存原始快照
  ↓
SKU 映射为 TokenSea 标准价格组件
  ↓
未映射记录进入待处理队列
  ↓
与当前官方价格目录比较
  ↓
生成价格差异
  ↓
按现有风险规则人工审核或低风险自动发布
  ↓
派生渠道部署价格
```

## 11.2 公共参考源流程

```text
定时获取 LiteLLM/models.dev 固定版本快照
  ↓
Checksum 与版本记录
  ↓
模型别名和价格组件映射
  ↓
与 TokenSea 当前正式价格比较
  ↓
相同：记录佐证
不同：生成“参考源差异”提示
缺失：生成“参考覆盖缺失”提示
  ↓
不自动发布正式价格
```

## 11.3 成本 API 对账流程

```text
每天同步供应商 Costs/Billing API
  ↓
保存实际成本原始记录
  ↓
映射供应商项目和 TokenSea 渠道
  ↓
聚合相同时间窗口内 usage_cost_snapshot
  ↓
计算金额差异和覆盖率
  ↓
超过阈值产生告警
  ↓
财务或管理员确认
```

---

# 第二阶段：通用文档提取引擎

## 12. 第二阶段范围

第二阶段支持：

- HTML table；
- JSON；
- CSV；
- PDF；
- LLM 结构化映射；
- 原始证据定位；
- 抽取置信度和规则校验；
- 人工审核工作台。

第二阶段不删除现有供应商适配器。专用适配器将从“完整解析器”收敛为“特殊计费语义插件”。

---

## 13. 第二阶段后端架构

## 13.1 提取器接口

建议目录：

```text
services/control-plane/src/main/java/com/tokensea/governance/pricing/extractor/
├── PriceDocumentExtractor.java
├── PriceExtractionContext.java
├── PriceExtractionResult.java
├── PriceDocumentTypeDetector.java
├── JsonPriceExtractor.java
├── CsvPriceExtractor.java
├── HtmlTablePriceExtractor.java
├── PdfTextPriceExtractor.java
├── PdfTablePriceExtractor.java
├── LlmPriceSchemaMapper.java
├── ExtractionValidator.java
└── ExtractionConfidenceCalculator.java
```

接口：

```java
public interface PriceDocumentExtractor {
    boolean supports(String documentType, String extractionMode);
    PriceExtractionResult extract(PriceExtractionContext context,
                                  PriceSourceDocument document);
}
```

## 13.2 确定性提取优先级

```text
1. API 原生 JSON 映射
2. JSONPath 配置
3. CSV 列映射
4. HTML table 通用识别
5. PDF 文本与表格提取
6. LLM Schema 映射
7. 供应商特殊语义插件
```

LLM 不是默认第一步，避免增加成本、延迟和不确定性。

## 13.3 HTML table

复用 `OfficialPriceAnalyzer`：

- 表格发现；
- 表头合并；
- 横向/纵向表格识别；
- 模型、输入、输出、缓存字段定位；
- 区域、Batch、上下文和单位识别；
- 跳过原因和结构指纹。

通用 HTML 提取器输出候选行，供应商插件只补充：

- 特殊模型别名；
- 服务层级语义；
- 缓存 TTL；
- 长上下文阈值；
- 特殊折扣或区域规则。

## 13.4 JSON 提取

支持两种模式：

1. 已知结构：连接器内 Java 映射；
2. 配置结构：管理员配置 JSONPath。

配置示例：

```json
{
  "recordsPath": "$.models[*]",
  "fields": {
    "model": "$.id",
    "inputPrice": "$.cost.input",
    "outputPrice": "$.cost.output",
    "cacheReadPrice": "$.cost.cache_read",
    "currency": "USD"
  },
  "billingQuantity": 1000000,
  "billingBasis": "TOKEN"
}
```

JSONPath 配置必须经过白名单和最大深度限制，禁止执行脚本表达式。

## 13.5 CSV 提取

支持：

- UTF-8/UTF-8 BOM；
- 逗号、制表符和分号分隔；
- 表头别名配置；
- 货币与千分位处理；
- 每百万、每千、每 Token 单位转换；
- 空值与 `N/A` 语义；
- 多行表头和说明行跳过；
- 大文件流式处理。

## 13.6 PDF 提取

第一步建议使用 Java 内部能力完成：

- PDFBox 文本抽取；
- 页码与坐标保留；
- 基于位置的表格行列重建；
- 对可复制文本 PDF 进行规则识别。

以下情况再考虑独立 Python 文档提取服务：

- 扫描 PDF OCR；
- 复杂跨页表格；
- 多栏排版；
- 表格线检测；
- 大规模文档并行处理。

不建议把 PDF 提取直接塞入现有 Headless Fetcher。Headless Fetcher 继续只负责浏览器渲染和网页安全访问，文档提取器拥有独立资源和安全边界。

## 13.7 LLM Schema 映射

### 13.7.1 调用方式

LLM Mapper 通过 TokenSea Gateway 调用平台已批准模型，不直接使用外部供应商 Key。

使用专用内部应用和 Virtual Key：

```text
tenant   = TokenSea Internal
project  = Price Governance
app      = Document Extractor
keyScope = 指定结构化输出模型
budget   = 独立日/月预算
```

### 13.7.2 输入

- 文档类型；
- 来源供应商；
- 已提取文本或表格；
- 页码、行号、表格索引；
- 目标 JSON Schema；
- 允许的枚举值；
- 单位换算规则；
- 禁止猜测说明。

### 13.7.3 输出

```json
{
  "schemaVersion": "price-record-v1",
  "records": [
    {
      "providerModelName": "...",
      "region": "CN",
      "requestMode": "STANDARD",
      "serviceTier": "DEFAULT",
      "contextTier": "DEFAULT",
      "components": [
        {
          "componentType": "INPUT",
          "unitPrice": 1.0,
          "currency": "CNY",
          "billingQuantity": 1000000,
          "billingBasis": "TOKEN"
        }
      ],
      "evidence": {
        "page": 3,
        "tableIndex": 2,
        "row": 5,
        "text": "..."
      },
      "confidence": 0.94,
      "missingFields": [],
      "warnings": []
    }
  ]
}
```

### 13.7.4 强制约束

- `temperature = 0`；
- 使用严格 Structured Output / JSON Schema；
- 不允许输出 Schema 之外字段；
- 每条价格必须包含证据位置；
- 找不到的数据返回空，不推测；
- 单位换算由代码完成，不由 LLM 完成；
- LLM 结果必须通过 Java 校验器；
- LLM 结果默认 `MANUAL_ONLY`；
- 置信度不能单独决定自动发布；
- 原始 Prompt、模型、模型版本、响应哈希写入抽取运行记录。

## 13.8 统一校验器

`ExtractionValidator` 至少校验：

- 模型名非空；
- ISO-4217 币种；
- 单价非负；
- 计费数量大于 0；
- 计费组件枚举合法；
- 生效时间区间合法；
- 上下文档位不重叠；
- 同一范围内价格组件不重复；
- 缓存读、写模式语义完整；
- 价格变化比例是否异常；
- 文档证据是否存在；
- LLM 输出字段是否确实出现在证据中；
- 供应商和模型是否与来源范围匹配。

---

## 14. 第二阶段数据库改造

建议新增：

```sql
price_document_extraction_run
- id
- price_source_id
- sync_run_id
- raw_snapshot_id
- document_type
- extractor_code
- extraction_mode
- schema_version
- llm_model
- llm_request_id
- deterministic_record_count
- llm_record_count
- accepted_record_count
- confidence_summary jsonb
- validation_summary jsonb
- status
- started_at
- finished_at

price_document_evidence
- id
- extraction_run_id
- record_key
- page_number
- table_index
- row_index
- column_index
- source_text
- source_hash
- coordinates jsonb
```

现有价格差异继续引用原始快照；必要时增加 `extraction_run_id` 和 `evidence_id`，形成：

```text
价格差异
  → 标准化记录
  → 提取运行
  → 原始快照
  → 页码/表格/文本证据
```

---

## 15. 第二阶段 Console 前端改造

## 15.1 文档提取配置

价格源表单增加：

- 文档类型：自动、HTML、JSON、CSV、PDF；
- 提取模式：确定性、确定性 + LLM、仅专用适配器；
- Schema 版本；
- JSONPath/CSV 列映射；
- LLM 模型；
- 最低置信度；
- 是否必须人工审核；
- 最大文件大小和页数。

## 15.2 提取审核工作台

建议在价格差异审核详情中增加“提取证据”区域，而不是另建一套审批流程。

布局：

```text
左侧：原始文档/页面/表格
右侧：标准化价格记录
底部：校验结果、置信度、历史价格和差异
```

操作：

- 接受记录；
- 修正模型映射；
- 修正计费组件；
- 标记非价格内容；
- 驳回抽取；
- 保存为映射规则；
- 批准进入现有价格差异审核。

## 15.3 解析诊断扩展

现有测试解析弹窗增加：

- 提取器链路；
- 确定性提取记录数；
- LLM 补充记录数；
- 校验通过数；
- 证据完整率；
- 平均置信度；
- 未识别计费单位；
- 冲突记录；
- LLM 调用成本和耗时；
- 使用的模型和 Schema 版本。

---

## 16. 统一价格 Schema

当前 `NormalizedPrice` 应继续作为兼容输出，但建议定义稳定的 V1 Schema：

```json
{
  "schemaVersion": "price-record-v1",
  "providerType": "openai",
  "providerModelName": "gpt-x",
  "canonicalModelName": null,
  "region": "global",
  "requestMode": "STANDARD",
  "serviceTier": "DEFAULT",
  "contextTier": "DEFAULT",
  "contextMin": 0,
  "contextMax": null,
  "currency": "USD",
  "billingBasis": "TOKEN",
  "billingQuantity": 1000000,
  "components": [
    {
      "componentType": "INPUT",
      "unitPrice": 1.25,
      "pricingMode": "EXPLICIT"
    },
    {
      "componentType": "CACHE_READ",
      "unitPrice": 0.125,
      "pricingMode": "EXPLICIT"
    },
    {
      "componentType": "OUTPUT",
      "unitPrice": 10.0,
      "pricingMode": "EXPLICIT"
    }
  ],
  "effectiveFrom": null,
  "effectiveTo": null,
  "source": {
    "sourceId": "...",
    "connectorCode": "...",
    "sourceVersion": "...",
    "rawSnapshotId": "...",
    "trustLevel": "OFFICIAL_PUBLIC",
    "evidence": {}
  }
}
```

### 16.1 组件枚举建议

```text
INPUT
OUTPUT
CACHE_READ
CACHE_WRITE
REASONING_OUTPUT
AUDIO_INPUT
AUDIO_OUTPUT
IMAGE_INPUT
IMAGE_OUTPUT
VIDEO_INPUT
VIDEO_OUTPUT
EMBEDDING
RERANK
TOOL_CALL
WEB_SEARCH
REQUEST
SECOND
CHARACTER
OTHER
```

### 16.2 不允许被简化的维度

以下维度必须保留，不能仅折算成输入/输出两个价格：

- 区域；
- Batch/Standard/Flex/Priority；
- 服务层级；
- 长上下文档位；
- 缓存读取与写入；
- 缓存 TTL；
- 推理 Token；
- 图片、音频、视频；
- 请求次数和工具调用；
- 生效时间；
- 合同折扣范围。

---

## 17. 来源可信度与发布策略

建议统一来源等级：

| 等级 | 来源 | 默认策略 |
|---|---|---|
| `CONTRACTUAL` | 企业合同、账号合同价 | 人工审核后最高优先级 |
| `OFFICIAL_ACCOUNT` | 云账号 Pricing API | 人工审核，可进入渠道实际价 |
| `OFFICIAL_PUBLIC` | 官方目录 API、官方价格文档 | 现有风险规则审核后进入官方价 |
| `OFFICIAL_BILLING` | Costs/Billing API | 仅用于实际成本对账 |
| `COMMUNITY_REFERENCE` | LiteLLM、models.dev | 仅参考，不自动发布 |
| `LLM_EXTRACTED` | LLM 从官方文档抽取 | 必须人工审核 |
| `MANUAL_VERIFIED` | 人工录入并附证据 | 按审批规则发布 |

有效成本优先级保持：

```text
CONTRACT_PRICE
  > CHANNEL_ACTUAL
  > PROVIDER_OFFICIAL
```

公共参考价不直接进入运行时有效成本选择。

---

## 18. 安全设计

## 18.1 网络安全

- 所有外部目录 API、数据集和账单 API 通过 Egress Proxy；
- 连接器声明允许主机和端口；
- 禁止跟随到非白名单域名；
- 继续执行公网地址校验、DNS 重绑定防护和响应大小限制；
- Headless 与普通 API 连接器使用不同的内容大小和超时策略；
- PDF/CSV 下载限制 MIME、文件大小、压缩比和重定向次数。

## 18.2 凭据安全

凭据按用途分离：

```text
INFERENCE
PRICING_READ
BILLING_READ
```

要求：

- 账单凭据不得下发 Gateway Runtime；
- API 测试结果不得显示密钥或访问令牌；
- 日志只显示凭据引用和末四位；
- 最小权限 IAM；
- 支持轮换和停用；
- 同步任务记录凭据使用审计。

## 18.3 LLM 数据安全

- 只发送价格相关文本和表格，不发送供应商密钥、合同中的无关敏感信息；
- 对合同、账单等私有文档，使用平台批准的数据处理区域和模型；
- 默认不使用公共免费模型处理合同和账单；
- Prompt 和响应按审计要求保存摘要及哈希，原文按权限查看；
- 防止文档中的 Prompt Injection：文档内容视为不可信数据，系统指令明确禁止执行文档指令；
- LLM 输出只能通过 JSON Schema 接收。

---

## 19. 监控与告警

新增指标：

```text
price_connector_fetch_total
price_connector_fetch_failure_total
price_connector_records_total
price_connector_unmapped_records_total
price_extraction_total
price_extraction_validation_failure_total
price_llm_extraction_total
price_llm_extraction_cost
price_source_mapping_coverage_ratio
provider_cost_reconciliation_variance_ratio
provider_cost_unmapped_amount
```

新增告警：

- 官方目录 API 连续失败；
- 数据集版本长时间未更新；
- 映射覆盖率下降；
- 单次价格变化超过阈值；
- 参考源与官方源冲突；
- 账单对账差异超过阈值；
- 未映射账单金额过高；
- LLM 抽取证据缺失；
- LLM 抽取成本或调用量异常；
- 文档结构指纹变化。

---

## 20. 测试方案

## 20.1 单元测试

### 连接器

- Azure 分页和 `NextPageLink`；
- AWS NextToken、价格文件和区域过滤；
- Google nextPageToken、Money 单位换算；
- LiteLLM 每 Token 单价转换为每百万 Token；
- models.dev 组件映射；
- OpenAI Costs 日级分页和 amount/currency 解析。

### 映射规则

- SKU 精确匹配；
- 正则匹配；
- 优先级冲突；
- 未映射记录；
- 区域和服务层级映射；
- 单位转换；
- 重复组件检测。

### 通用提取器

- JSONPath；
- CSV 编码和分隔符；
- HTML 横向/纵向表格；
- PDF 文本页码；
- LLM Schema 校验；
- 证据缺失拒绝；
- Prompt Injection 文本不被执行。

## 20.2 集成测试

- 从连接器原始响应到价格差异；
- 参考源不能自动发布；
- 官方目录低风险/高风险发布规则；
- 同一快照幂等同步；
- 映射更新后重放原始快照；
- 账单同步与 usage 聚合对账；
- 凭据用途隔离；
- Egress 白名单和私网阻断；
- Flyway V1 → 最新版本升级；
- 旧 Qwen/Kimi/MIMO/智谱解析器回归。

## 20.3 前端测试

- 来源方式切换显示正确字段；
- 连接器 Schema 动态表单；
- 凭据字段不回显；
- 测试获取和解析报告；
- 未映射记录处理；
- 价格差异证据展示；
- 对账分页、筛选和状态处理；
- 旧价格源编辑兼容；
- Console 类型检查和生产构建。

## 20.4 验收数据集

每个连接器保存固定脱敏样本：

```text
services/control-plane/src/test/resources/pricing/
├── azure/
├── aws/
├── google/
├── litellm/
├── models-dev/
├── openai-costs/
├── html/
├── csv/
└── pdf/
```

测试禁止依赖实时外网结果。实时 API 测试使用单独 profile 和环境变量，默认跳过。

---

## 21. 实施步骤与排期建议

## 21.1 第一阶段建议拆分

### Sprint 1：连接器框架与数据模型（1.5～2 周）

- 连接器接口与注册中心；
- 现有 HTTP/Headless 兼容连接器；
- 价格源字段和数据库迁移；
- 连接器元数据 API；
- Console 动态来源表单；
- 旧价格源回归。

### Sprint 2：公共参考源（1～1.5 周）

- LiteLLM Cost Map；
- models.dev；
- 模型别名和组件映射；
- 参考源可信等级和禁止自动发布；
- 参考源冲突提示。

### Sprint 3：云厂商目录 API（2～3 周）

- Azure Retail Prices；
- AWS Price List；
- Google Cloud Catalog；
- SKU 映射规则；
- 未映射记录工作台；
- API 快照和分页诊断。

### Sprint 4：OpenAI Costs 与对账（1.5～2 周）

- 账单凭据用途隔离；
- OpenAI Costs 连接器；
- 成本记录和对账表；
- 供应商成本对账页面；
- 差异告警。

第一阶段合计约 **6～8.5 周**，取决于云厂商 SKU 映射和测试账号准备情况。

## 21.2 第二阶段建议拆分

### Sprint 5：通用 JSON/CSV/HTML（2～3 周）

- 文档类型检测；
- JSONPath；
- CSV；
- 复用 HTML Analyzer；
- 统一证据模型。

### Sprint 6：PDF（2～3 周）

- PDF 文本与页码；
- 表格重建；
- PDF 样本回归；
- 文件安全限制。

### Sprint 7：LLM Schema Mapper（2～3 周）

- 专用内部 Key 和预算；
- 严格 Schema；
- 证据校验；
- 抽取运行记录；
- 安全和 Prompt Injection 测试。

### Sprint 8：审核工作台与收敛（1.5～2 周）

- 文档证据对照；
- 映射规则沉淀；
- 解析器迁移；
- 监控和验收。

第二阶段合计约 **7.5～11 周**。

---

## 22. 文件级改动清单

以下为计划文件，不代表本方案输出时已经创建。

## 22.1 Control Plane

```text
修改：
services/control-plane/src/main/java/com/tokensea/governance/ProviderPriceSyncController.java
services/control-plane/src/main/java/com/tokensea/governance/ProviderPriceSyncService.java
services/control-plane/src/main/java/com/tokensea/governance/PriceSourceParser.java
services/control-plane/src/main/java/com/tokensea/governance/pricing/adapter/PriceSourceAdapterRegistry.java
services/control-plane/src/main/java/com/tokensea/governance/ProviderPriceCatalogService.java
services/control-plane/src/main/java/com/tokensea/audit/service/AuditService.java
services/control-plane/src/main/resources/application.yml

新增：
services/control-plane/src/main/java/com/tokensea/governance/pricing/connector/*
services/control-plane/src/main/java/com/tokensea/governance/pricing/extractor/*
services/control-plane/src/main/java/com/tokensea/governance/pricing/mapping/*
services/control-plane/src/main/java/com/tokensea/governance/reconciliation/*
services/control-plane/src/main/resources/db/migration/Vxx__multi_source_pricing_connectors.sql
services/control-plane/src/main/resources/db/migration/Vxx__provider_cost_reconciliation.sql
services/control-plane/src/main/resources/db/migration/Vxx__price_document_extraction.sql
```

## 22.2 Console

```text
修改：
apps/console/src/config/resources.ts
apps/console/src/config/menu.ts
apps/console/src/router.ts
apps/console/src/pages/DataPage.vue
apps/console/src/api/client.ts

建议新增：
apps/console/src/pages/ProviderCostReconciliation.vue
apps/console/src/pages/PriceSourceMappings.vue（也可先做详情组件）
apps/console/src/components/pricing/ConnectorConfigForm.vue
apps/console/src/components/pricing/ConnectorTestReport.vue
apps/console/src/components/pricing/PriceEvidenceViewer.vue
apps/console/src/components/pricing/ExtractionReviewPanel.vue
```

## 22.3 部署与配置

```text
修改：
deploy/compose/.env.example
deploy/compose/docker-compose.yml
deploy/compose/docker-compose.dev.yml
services/egress-proxy 允许主机配置

可能新增：
services/document-extractor/（仅在复杂 PDF/OCR 阶段需要）
```

## 22.4 测试

```text
services/control-plane/src/test/java/com/tokensea/governance/pricing/connector/*
services/control-plane/src/test/java/com/tokensea/governance/pricing/extractor/*
services/control-plane/src/test/java/com/tokensea/governance/reconciliation/*
services/control-plane/src/test/resources/pricing/*
apps/console/tests/resource-contract.test.mjs
```

---

## 23. 兼容与迁移策略

1. 所有已有价格源保持原 `adapterCode` 和行为；
2. 新增 `connectorCode` 为空时自动按旧配置映射为 `HTTP_DOCUMENT`；
3. Qwen、Kimi、MIMO、智谱适配器继续由 Registry 加载；
4. 现有同步任务、原始快照和价格差异记录不迁移内容；
5. 当前 `NormalizedPrice` 在第一阶段保持二进制和调用兼容；
6. 新 Schema 先由转换器适配到 `NormalizedPrice`；
7. 参考源上线后不自动回写已有正式价格；
8. OpenAI Costs 上线后先运行“只对账不告警”观察期；
9. 对账阈值经实际数据验证后再启用告警；
10. 新菜单和权限加入现有 RBAC，不给普通开发者开放账单凭据和原始成本明细。

---

## 24. 风险与应对

| 风险 | 说明 | 应对 |
|---|---|---|
| 云目录 SKU 难映射 | SKU 描述与模型名不完全一致 | 映射规则表、未映射工作台、样本回归 |
| 目录 API 不覆盖全部模型 | 部分模型仍仅在网页公布 | 保留现有官方网页适配器 |
| 公共参考数据错误 | LiteLLM/models.dev 可能滞后或缺价 | 参考源禁止自动发布，官方证据优先 |
| Costs API 粒度不足 | 可能只有项目和 line item，无法定位单模型 | 定位为日级对账，不作为单价目录 |
| 账号价格权限过大 | Billing API 凭据风险高 | 最小权限、用途隔离、审计、禁止下发 Runtime |
| LLM 幻觉 | 可能补充文档未提供的价格 | 严格 Schema、证据绑定、缺失返回空、人工审核 |
| PDF 复杂结构 | 表格跨页、扫描件识别困难 | 分阶段支持，复杂场景独立服务/OCR |
| 同步任务变慢 | 云目录和大数据集记录较多 | 增量、分页、流式处理、异步任务和限流 |
| 数据结构膨胀 | 不同供应商计费维度不断增加 | 组件化 Schema + attributes，核心字段稳定 |
| 页面复杂度增加 | 动态连接器配置项较多 | 元数据驱动表单，分步配置和高级设置折叠 |

---

## 25. 验收标准

### 25.1 第一阶段

1. Azure、AWS、Google 至少各有一个目录 API 连接器可完成测试获取、快照、标准化和差异生成；
2. LiteLLM 和 models.dev 可按固定版本同步并记录 checksum；
3. 公共参考源不能直接发布正式成本价；
4. OpenAI Costs 可按日同步并生成 TokenSea 估算成本与实际成本差异；
5. 云目录未映射记录可在后台查看并配置映射；
6. 原有 Qwen、Kimi、MIMO、智谱同步和审核流程不受影响；
7. 所有同步均保留原始证据、版本和审计记录；
8. 新凭据不会出现在日志、前端响应或 Runtime 配置中；
9. 同一快照重复同步不产生重复价格差异；
10. Control Plane、Console、Flyway 和安全回归测试通过。

### 25.2 第二阶段

1. HTML、JSON、CSV、文本型 PDF 均可生成统一价格 Schema；
2. LLM 仅在配置允许时调用；
3. 每条 LLM 抽取价格都有页码、表格或文本证据；
4. 缺少证据或校验失败的记录不能进入发布；
5. 审核人员可查看原文与标准化记录对照；
6. 通用提取器可覆盖大部分新增供应商，只对特殊计费语义增加小型插件；
7. 文档结构变化会生成诊断和告警，不静默覆盖正式价格；
8. LLM 抽取成本、调用次数和失败率可监控；
9. 旧供应商专用解析器仍可回退使用；
10. 已发布价格版本和历史用量成本可重放。

---

## 26. 推荐的最终产品流程

```text
供应商模板/渠道
       ↓
价格源管理
       ↓
选择来源：
官方目录 API / 账号价格 API / 公共参考 / 官方文档 / 账单 API
       ↓
测试连接
       ↓
测试提取与映射覆盖率
       ↓
启用定时同步
       ↓
原始证据快照
       ↓
确定性提取
       ↓（必要时）
LLM Schema 映射
       ↓
统一价格 Schema 校验
       ↓
模型别名和 SKU 映射
       ↓
价格差异审核
       ↓
官方价格目录 / 渠道实际价 / 合同价
       ↓
有效成本价格
       ↓
Gateway 用量成本快照
       ↓
供应商账单实际成本对账
```

---

## 27. 实施结论

TokenSea 应从“为每家供应商持续编写网页解析器”升级为“多源价格治理平台”。

第一阶段通过云厂商官方目录 API、LiteLLM、models.dev 和 OpenAI Costs 等机器可读来源，减少对网页结构的依赖，并建立实际成本对账能力。第二阶段通过 HTML、JSON、CSV、PDF 通用提取和受控 LLM Schema 映射，使新供应商接入从“开发一个完整解析器”降低为“配置连接器、映射规则和少量特殊计费插件”。

现有 Qwen、Kimi、Xiaomi MIMO、智谱解析器不应立即删除，它们继续承担官方文档特殊语义和回退能力。所有新增来源仍必须经过 TokenSea 已有的原始快照、差异检测、风险审核、价格版本和运行时成本快照闭环。

最终产品定位应为：

> 以官方目录、账号价格、公共参考、官方文档、合同和账单为证据来源，以统一价格 Schema、版本化、审核和对账为核心的企业级 LLM 价格治理平台。

---

## 28. 官方技术参考

1. Azure Retail Prices API：`https://learn.microsoft.com/rest/api/cost-management/retail-prices/azure-retail-prices`
2. Azure 中国区零售价格 API：`https://learn.microsoft.com/rest/api/cost-management/retail-prices/azure-retail-prices-china`
3. AWS Price List Query API：`https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/using-price-list-query-api.html`
4. AWS ListPriceLists API：`https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_pricing_ListPriceLists.html`
5. Google Cloud Billing Catalog API：`https://cloud.google.com/billing/v1/how-tos/catalog-api`
6. Google Cloud Pricing API：`https://cloud.google.com/billing/docs/reference/pricing-api/rest`
7. OpenAI Usage and Costs API：`https://platform.openai.com/docs/api-reference/usage`
8. LiteLLM 模型成本映射：`https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json`
9. models.dev：`https://models.dev/api.json`

---

## 29. 文档依据

- 《企业级统一 LLM API Gateway 平台 PRD｜基于 LiteLLM 二次开发》V1.0，2026-07-08；
- 《LiteLLM 详细分析报告》V1.0，2026-07-08；
- 当前 TokenSea Console、Control Plane、Gateway Runtime、Headless Fetcher、Egress Proxy、Flyway 迁移和价格治理代码；
- 当前项目价格自动同步、解析诊断、模型发现、价格差异审核和有效成本价格实现记录。
