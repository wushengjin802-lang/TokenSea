# TokenSea 缓存命中与缓存未命中差异化计价改造方案

> 文档版本：V1.0  
> 编制日期：2026-07-23  
> 适用范围：TokenSea 企业级统一 LLM API Gateway  
> 参考对象：CC Switch、LiteLLM、OpenRouter、Portkey，以及 OpenAI、Anthropic、DeepSeek、Google Gemini 的官方计价与 Usage 语义  
> 文档性质：产品、数据模型、接口、计费引擎和页面改造设计方案，不包含本次代码实现

---

## 1. 改造背景

当前多数大模型已经不再只有简单的“输入价格 + 输出价格”两项费用。常见价格结构至少包括：

```text
输入价格（缓存未命中）
输入价格（缓存命中 / 缓存读取）
输入价格（缓存创建 / 缓存写入）
输出价格
```

部分供应商还进一步区分：

```text
5 分钟缓存写入
1 小时缓存写入
缓存存储 Token·小时
长上下文缓存价格
标准 / Batch / Flex / Priority 服务层级
文本 / 图片 / 音频等不同模态
```

DeepSeek 官方价格表已经直接使用“输入价格（缓存命中）”“输入价格（缓存未命中）”“输出价格”三个价格维度；其响应 Usage 还提供 `prompt_cache_hit_tokens` 与 `prompt_cache_miss_tokens`。Anthropic 将输入、缓存读取、缓存创建分开计量，并区分 5 分钟和 1 小时缓存写入价格。OpenAI 的模型价格表通常分别展示普通输入、Cached Input 和输出价格。Gemini 除缓存输入价外，还可能产生缓存存储费用。

当前 TokenSea 虽然已经具备 `CACHE_READ_TOKEN`、`CACHE_WRITE_TOKEN` 等底层价格组件和请求级缓存 Token 统计，但供应商官方价格目录、模型生效价格、路由价格选择和多数成本页面仍主要展示“输入单位价格、输出单位价格”，手工录入接口也不能完整维护缓存价格，导致平台已经具备部分底层能力，却没有形成管理员可操作、用户可解释、成本可对账的完整闭环。

---

## 2. 改造结论

本项目应把 Token 价格从“两项价格”升级为“基础四项价格 + 可扩展价格组件”。

基础四项统一定义为：

| 内部组件编码 | 页面名称 | 业务含义 |
|---|---|---|
| `INPUT_TOKEN` | 输入价格（缓存未命中） | 普通输入或未命中缓存部分的 Token 价格 |
| `CACHE_READ_TOKEN` | 输入价格（缓存命中） | 从供应商 Prompt/Context Cache 读取的 Token 价格 |
| `CACHE_WRITE_TOKEN` | 输入价格（缓存写入） | 创建或写入供应商 Prompt/Context Cache 的 Token 价格 |
| `OUTPUT_TOKEN` | 输出价格 | 普通输出 Token 价格 |

同时保留通用组件能力，用于后续支持：

```text
REASONING_TOKEN
CACHE_STORAGE_TOKEN_HOUR
INPUT_TOKEN_ABOVE_THRESHOLD
OUTPUT_TOKEN_ABOVE_THRESHOLD
IMAGE_INPUT
IMAGE_OUTPUT
AUDIO_INPUT_TOKEN
AUDIO_OUTPUT_TOKEN
VIDEO_SECOND
REQUEST
SEARCH_REQUEST
```

最终产品原则：

1. 页面默认按“每百万 Token”展示和录入。
2. `INPUT_TOKEN` 在产品语义上明确表示“缓存未命中输入”，不再使用含义模糊的“输入单位价格”。
3. 缓存命中、缓存写入不得隐藏在不可见 JSON 中。
4. 价格组件是成本计算的唯一权威依据，输入、输出摘要字段只用于列表展示。
5. 缓存 Token 必须转换为互斥的标准用量后再计费，禁止重复计费。
6. 供应商 Prompt Cache 与 TokenSea 网关响应缓存必须分开建模。
7. 缓存价格缺失不能默认按 0 处理，也不能自动等同普通输入价格。

---

## 3. 外部平台与供应商参考

## 3.1 CC Switch

CC Switch 的价格配置界面按每百万 Token 提供四个固定字段：

```text
Input Price
Output Price
Cache Read Price
Cache Creation Price
```

该设计的优点是：

- 管理员可以直接看懂输入、输出、缓存读取、缓存创建四类价格；
- 模型价格表与用量统计口径一致；
- 适合开发者快速维护自定义价格；
- 缓存命中率、缓存 Token 和成本可以在同一个用量面板中查看。

CC Switch 近期版本还专门修复了格式转换链路中的缓存 Token 双计费问题，并在请求记录中保存实际计价模型与价格依据。这说明缓存价格不仅是页面展示问题，还必须与协议转换、模型映射、Usage 解析和请求级成本快照联动。

TokenSea 应借鉴 CC Switch 的“四项基础价格”和直观编辑体验，但不能照搬其固定四列和直接覆盖价格的方式。TokenSea 仍需保留：

- 价格版本；
- 来源证据；
- 生效时间；
- 差异审核；
- 多租户归因；
- 供应商对账；
- 多服务层级和长上下文价格组件。

## 3.2 LiteLLM

LiteLLM 的价格字段通常包括：

```text
input_cost_per_token
output_cost_per_token
cache_read_input_token_cost
cache_creation_input_token_cost
```

并进一步支持长上下文、推理 Token、多模态、Priority、Flex 等扩展价格字段。

LiteLLM 的优势是覆盖面广，适合作为 TokenSea 的公共价格参考和字段映射依据；其不足是价格字段会持续增加，不适合在 TokenSea 中逐个扩展为固定数据库列。

TokenSea 应继续采用“标准价格组件”模型，而不是把 LiteLLM 的每一个字段都建成独立列。

## 3.3 OpenRouter

OpenRouter 的 Prompt Caching 文档明确区分：

- `cached_tokens`：从供应商 Prompt Cache 读取的 Token；
- `cache_write_tokens`：建立缓存时写入的 Token。

OpenRouter 还提供网关级 Response Caching。网关响应缓存命中时不会调用上游模型，通常上报的可计费 Token 为 0。

这两个概念必须在 TokenSea 中分开：

| 类型 | 是否调用上游 | 计价方式 |
|---|---|---|
| 供应商 Prompt Cache 命中 | 是 | 命中 Token 按供应商缓存读取价格计费 |
| TokenSea 网关 Response Cache 命中 | 否 | 不产生供应商 Token 成本，只记录网关缓存命中与节省金额 |

## 3.4 Portkey

Portkey 在网关缓存观测中展示：

```text
Cache Hit
Cache Semantic Hit
Cache Miss
Cache Refreshed
Cache Disabled
```

并统计缓存命中率、延迟节省和成本节省。

TokenSea 后续若增加响应缓存，可以参考这一观测模型，但不能把网关缓存状态写入 `CACHE_READ_TOKEN`。前者属于 TokenSea 自身缓存，后者属于供应商返回的 Prompt Cache 用量。

## 3.5 供应商官方语义差异

### DeepSeek

DeepSeek 的 Usage 语义为：

```text
prompt_tokens = prompt_cache_hit_tokens + prompt_cache_miss_tokens
```

因此标准化后应得到：

```text
INPUT_TOKEN       = prompt_cache_miss_tokens
CACHE_READ_TOKEN  = prompt_cache_hit_tokens
CACHE_WRITE_TOKEN = 0
```

DeepSeek 缓存由供应商自动建立，当前价格表没有单独缓存写入价。

### Anthropic

Anthropic Usage 通常分别返回：

```text
input_tokens
cache_read_input_tokens
cache_creation_input_tokens
output_tokens
```

这些字段应直接转换为互斥标准用量：

```text
INPUT_TOKEN       = input_tokens
CACHE_READ_TOKEN  = cache_read_input_tokens
CACHE_WRITE_TOKEN = cache_creation_input_tokens
OUTPUT_TOKEN      = output_tokens
```

Anthropic 的缓存写入还可能按 5 分钟和 1 小时 TTL 使用不同价格，因此仅有一个 `CACHE_WRITE_TOKEN` 单价并不足够，必须支持作用域或变体。

### OpenAI

OpenAI 常见响应中：

```text
prompt_tokens_details.cached_tokens
```

包含于 `prompt_tokens` 之中，因此：

```text
CACHE_READ_TOKEN = cached_tokens
INPUT_TOKEN = prompt_tokens - cached_tokens - 可明确识别的 cache_write_tokens
```

只有在响应或官方协议能够明确识别缓存写入 Token 时，才计算 `CACHE_WRITE_TOKEN`。不能凭价格表存在缓存写入价就估算写入 Token。

### Gemini

Gemini 可能同时产生：

- 普通输入 Token；
- 缓存内容 Token；
- 输出 Token；
- 显式缓存存储 Token·小时费用。

因此 Gemini 不能仅用四项价格完整表达，还需要：

```text
CACHE_STORAGE_TOKEN_HOUR
```

---

## 4. TokenSea 当前实现评估

## 4.1 当前已经具备的能力

当前代码已经存在以下基础：

### 价格组件解析

`PriceSourceParser.java` 已支持：

```text
INPUT_TOKEN
OUTPUT_TOKEN
CACHE_READ_TOKEN
CACHE_WRITE_TOKEN
REASONING_TOKEN
```

其中：

- LiteLLM 价格源可读取缓存读取和缓存创建价格；
- models.dev 可读取 `cache_read` 和 `cache_write`；
- DeepSeek 官方价格页已经将缓存未命中价映射为 `INPUT_TOKEN`，将缓存命中价映射为 `CACHE_READ_TOKEN`；
- 通用官方 JSON 可通过 `componentFields` 配置扩展价格组件。

### 通用计费单位

V16 已将价格模型升级为：

```text
billing_basis
billing_quantity
unit_price
```

Token 默认使用每百万 Token，底层还支持请求、图片、秒、分钟、字符和音频分钟。

### 请求级缓存用量

Gateway Runtime 已记录：

```text
cache_read_tokens
cache_write_tokens
reasoning_tokens
```

并在 `usage_cost_snapshot` 中保存：

```text
price_components
cost_components
```

### 缓存 Token 防重复计费

当前计算逻辑在缓存 Token 被包含于 `prompt_tokens` 时，会先从普通输入 Token 中扣除，再按缓存价格计费。

## 4.2 当前主要缺口

### 缺口一：页面仍是两项价格

当前“供应商官方价格目录”和“模型生效价格”主要展示：

```text
输入单位价格
输出单位价格
```

缓存命中和缓存写入价格没有成为正式列表字段，管理员无法在主页面快速判断价格是否完整。

### 缺口二：手工录入接口不能维护缓存价格

`ProviderPriceCatalogController.CatalogRequest` 目前只有：

```text
inputUnitPrice
outputUnitPrice
```

没有：

```text
cacheReadUnitPrice
cacheWriteUnitPrice
priceComponents
```

因此，通过页面人工核验官方价时，无法完整录入 DeepSeek、Kimi、Claude、OpenAI 等模型的缓存价格。

### 缺口三：价格组件有两个事实来源

当前目录表保留输入、输出摘要字段，同时 `normalized_price` 和 `provider_price_component` 又保存完整组件。如果二者不一致，缺少统一的权威规则。

建议明确：

```text
provider_price_component / price_version.price_components
是唯一计费依据；
input_unit_price / output_unit_price
只是从组件派生的摘要字段。
```

### 缺口四：价格版本 JSON 无法表达同类型多变体

`provider_price_component` 表通过 `scope_hash` 可以保存同一组件的多个作用域，但 `price_version.price_components` 当前以组件类型为 JSON Key，例如：

```json
{
  "CACHE_WRITE_TOKEN": {
    "unitPrice": 3.75
  }
}
```

这种结构无法同时表达：

```text
CACHE_WRITE_TOKEN / 5 分钟
CACHE_WRITE_TOKEN / 1 小时
```

后写入的记录会覆盖前一条。

### 缺口五：Usage Normalizer 未完整覆盖供应商原生字段

当前标准化逻辑主要识别：

```text
prompt_tokens_details.cached_tokens
cache_read_input_tokens
cache_creation_input_tokens
cache_read_tokens
cache_write_tokens
```

仍应补充：

```text
prompt_cache_hit_tokens
prompt_cache_miss_tokens
cachedContentTokenCount
usageMetadata.cachedContentTokenCount
total_cached_tokens
```

尤其 DeepSeek 原生 `prompt_cache_hit_tokens` 和 `prompt_cache_miss_tokens` 应直接适配，不能完全依赖 LiteLLM 是否已经转换。

### 缺口六：标准用量仍包含协议语义标志

当前依赖：

```text
cache_read_in_prompt
cache_write_in_prompt
reasoning_in_completion
```

来决定是否从输入或输出中扣除对应 Token。这种方法容易因供应商格式变化产生双计费或漏计费。

更稳妥的方式是：Provider Adapter 直接输出互斥的标准用量，成本计算器只做乘法，不再猜测上游字段是否包含在总数中。

### 缺口七：价格缺失语义不清晰

缓存写入价为空可能表示：

- 供应商不收缓存写入费；
- 供应商不支持显式缓存写入；
- 价格尚未录入；
- 缓存写入按普通输入价格；
- 当前模型没有返回可计量的写入 Token。

这些情况不能统一处理为 0 或空值。

---

## 5. 目标价格模型

## 5.1 基础价格摘要

供应商官方价格目录的列表和基础表单默认展示：

```text
输入价格（缓存未命中）
输入价格（缓存命中）
输入价格（缓存写入）
输出价格
```

单位统一展示为：

```text
CNY / 百万 Token
USD / 百万 Token
```

不要只显示“输入价格”，避免管理员误把缓存命中价填入普通输入价。

## 5.2 价格组件结构

建议将 `price_components` 从以组件类型为 Key 的对象改为数组：

```json
[
  {
    "componentType": "INPUT_TOKEN",
    "variant": "DEFAULT",
    "unitPrice": 3,
    "unitBasis": "TOKEN",
    "unitQuantity": 1000000,
    "mode": "EXPLICIT",
    "scope": {}
  },
  {
    "componentType": "CACHE_READ_TOKEN",
    "variant": "DEFAULT",
    "unitPrice": 0.3,
    "unitBasis": "TOKEN",
    "unitQuantity": 1000000,
    "mode": "EXPLICIT",
    "scope": {}
  },
  {
    "componentType": "CACHE_WRITE_TOKEN",
    "variant": "TTL_5M",
    "unitPrice": 3.75,
    "unitBasis": "TOKEN",
    "unitQuantity": 1000000,
    "mode": "EXPLICIT",
    "scope": {
      "cacheTtlSeconds": 300
    }
  },
  {
    "componentType": "CACHE_WRITE_TOKEN",
    "variant": "TTL_1H",
    "unitPrice": 6,
    "unitBasis": "TOKEN",
    "unitQuantity": 1000000,
    "mode": "EXPLICIT",
    "scope": {
      "cacheTtlSeconds": 3600
    }
  },
  {
    "componentType": "OUTPUT_TOKEN",
    "variant": "DEFAULT",
    "unitPrice": 15,
    "unitBasis": "TOKEN",
    "unitQuantity": 1000000,
    "mode": "EXPLICIT",
    "scope": {}
  }
]
```

## 5.3 组件价格模式

每个组件增加 `mode`：

| 值 | 含义 |
|---|---|
| `EXPLICIT` | 官方明确给出价格 |
| `EXPLICIT_ZERO` | 官方明确表示免费，价格为 0 |
| `INHERIT_INPUT` | 官方明确规定沿用普通输入价 |
| `NOT_APPLICABLE` | 当前模型或协议不适用该组件 |
| `UNKNOWN` | 尚未确认，不得当作 0 使用 |

发布规则：

- `INPUT_TOKEN` 和 `OUTPUT_TOKEN` 原则上必须为 `EXPLICIT` 或 `EXPLICIT_ZERO`；
- 模型支持缓存且上游会返回缓存 Token 时，`CACHE_READ_TOKEN` 不能是 `UNKNOWN`；
- `CACHE_WRITE_TOKEN` 可以是 `NOT_APPLICABLE`，但必须有来源依据；
- `UNKNOWN` 组件不参与金额计算，并触发价格完整性告警；
- 生产路由是否允许未知组件，由平台设置控制，默认不允许。

## 5.4 作用域字段

组件作用域至少支持：

```text
region
requestMode
serviceTier
contextTier
cacheTtlSeconds
modality
minimumInputTokens
maximumInputTokens
batchMode
priorityMode
```

选择价格时按“最具体匹配优先”处理。

示例：

```text
同一模型：
STANDARD + <=200K
STANDARD + >200K
BATCH + <=200K
BATCH + >200K
```

应选择唯一匹配组件，无法唯一匹配时禁止计费并告警。

---

## 6. 标准 Usage 模型

## 6.1 标准字段

Gateway Runtime 的 Provider Adapter 应输出以下互斥字段：

```text
input_uncached_tokens
cache_read_tokens
cache_write_tokens
output_tokens
reasoning_tokens
cache_storage_token_seconds
input_images
output_images
audio_input_tokens
audio_output_tokens
video_seconds
```

辅助字段：

```text
input_tokens_total
output_tokens_total
provider_reported_total_tokens
usage_source
usage_schema_version
usage_evidence
```

## 6.2 标准化不变量

对于文本输入：

```text
input_tokens_total
= input_uncached_tokens
+ cache_read_tokens
+ cache_write_tokens
```

如果供应商将缓存创建 Token 同时计入普通输入 Token，Provider Adapter 必须在标准化阶段拆分，不能把重叠字段传给通用计费器。

对于 DeepSeek：

```text
input_uncached_tokens = prompt_cache_miss_tokens
cache_read_tokens = prompt_cache_hit_tokens
cache_write_tokens = 0
```

对于 OpenAI：

```text
cache_read_tokens = prompt_tokens_details.cached_tokens
input_uncached_tokens = prompt_tokens - cache_read_tokens - identifiable_cache_write_tokens
```

对于 Anthropic：

```text
input_uncached_tokens = input_tokens
cache_read_tokens = cache_read_input_tokens
cache_write_tokens = cache_creation_input_tokens
```

## 6.3 Usage 校验

标准化后必须校验：

```text
所有数量 >= 0
input_uncached + cache_read + cache_write 与供应商总输入一致
output + reasoning 与供应商输出语义一致
缓存命中 Token 不得大于总输入 Token
同一 Token 不得同时进入两个计费组件
```

校验失败时：

- 请求响应仍可返回给用户；
- 成本状态记为 `USAGE_INVALID`；
- 不得用估算值静默记账；
- 产生告警并保存原始 Usage 证据。

---

## 7. 成本计算规则

## 7.1 基础公式

```text
实际成本
= input_uncached_tokens × 输入未命中单价 / 计费基数
+ cache_read_tokens × 缓存命中单价 / 计费基数
+ cache_write_tokens × 缓存写入单价 / 计费基数
+ output_tokens × 输出单价 / 计费基数
+ reasoning_tokens × 推理单价 / 计费基数
+ 其他价格组件成本
```

Gemini 显式缓存存储费用：

```text
缓存存储成本
= cache_storage_token_seconds
× CACHE_STORAGE_TOKEN_HOUR 单价
÷ 1000000
÷ 3600
```

## 7.2 DeepSeek 示例

假设：

```text
缓存未命中输入：8000 Token，3 CNY / 1M
缓存命中输入：12000 Token，0.025 CNY / 1M
输出：2000 Token，6 CNY / 1M
```

成本：

```text
8000 × 3 / 1000000
+ 12000 × 0.025 / 1000000
+ 2000 × 6 / 1000000
= 0.0363 CNY
```

不能使用：

```text
20000 × 普通输入价
```

否则会严重高估成本。

## 7.3 Anthropic 示例

假设：

```text
普通输入：1000 Token
缓存读取：9000 Token
5 分钟缓存写入：2000 Token
输出：500 Token
```

成本必须按四项分别计算，不能把：

```text
input_tokens + cache_read_input_tokens + cache_creation_input_tokens
```

再次整体乘普通输入价格。

## 7.4 缓存节省金额

建议新增：

```text
cache_gross_savings
cache_write_premium
cache_storage_cost
cache_net_savings
```

计算方式：

```text
缓存读取毛节省
= cache_read_tokens
× (普通输入价 - 缓存读取价)
÷ 计费基数

缓存净节省
= 缓存读取毛节省
- 缓存写入附加成本
- 缓存存储成本
```

这比只展示缓存命中率更能说明真实成本收益。

---

## 8. 数据库改造方案

建议新增 V19 迁移：

```text
V19__cache_aware_pricing_components.sql
```

当前仍是研发版本，建议在迁移中清理现有测试价格目录、价格版本和成本快照后重新同步，避免为旧 JSON 结构维护复杂兼容逻辑。

## 8.1 `provider_price_component`

建议补充：

```text
variant varchar(80) NOT NULL DEFAULT 'DEFAULT'
component_mode varchar(30) NOT NULL DEFAULT 'EXPLICIT'
unit_quantity bigint NOT NULL
priority int NOT NULL DEFAULT 100
source_ref varchar(1200)
metadata jsonb NOT NULL DEFAULT '{}'
```

唯一约束调整为：

```text
catalog_price_id
+ component_type
+ variant
+ scope_hash
```

## 8.2 `provider_model_price_catalog`

保留：

```text
input_unit_price
output_unit_price
```

但明确它们是列表摘要：

```text
input_unit_price = INPUT_TOKEN 默认组件单价
output_unit_price = OUTPUT_TOKEN 默认组件单价
```

新增价格完整性字段：

```text
component_schema_version
price_completeness_status
cache_pricing_status
```

示例状态：

```text
COMPLETE
PARTIAL
UNKNOWN_CACHE_PRICE
UNSUPPORTED_CACHE
```

## 8.3 `price_version`

将 `price_components` 从对象改为不可变数组，并保存：

```text
component_schema_version
price_completeness_status
```

价格版本生效时，把目录组件完整复制到价格版本，不在运行时查询当前目录，以保证历史请求使用当时版本。

## 8.4 `usage_cost_snapshot`

新增或明确以下字段：

```text
input_uncached_tokens
input_tokens_total
cache_read_tokens
cache_write_tokens
output_tokens
reasoning_tokens
cache_storage_token_seconds
usage_schema_version
usage_source
usage_evidence jsonb
cache_gross_savings
cache_write_premium
cache_storage_cost
cache_net_savings
cost_status
```

`cost_components` 改为数组，以支持同一组件多个变体：

```json
[
  {
    "componentType": "CACHE_WRITE_TOKEN",
    "variant": "TTL_5M",
    "usageQuantity": "2000",
    "unitPrice": "3.75",
    "unitBasis": "TOKEN",
    "unitQuantity": 1000000,
    "amount": "0.0075"
  }
]
```

---

## 9. 控制面接口改造

## 9.1 供应商官方价格目录

现有：

```http
POST /api/provider-price-catalog
PATCH /api/provider-price-catalog/{id}
```

请求体改为：

```json
{
  "providerType": "anthropic",
  "providerModelName": "claude-sonnet-4-6",
  "currency": "USD",
  "billingBasis": "TOKEN",
  "billingQuantity": 1000000,
  "inputUnitPrice": 3,
  "cacheReadUnitPrice": 0.3,
  "cacheWriteUnitPrice": 3.75,
  "outputUnitPrice": 15,
  "priceComponents": [
    {
      "componentType": "CACHE_WRITE_TOKEN",
      "variant": "TTL_1H",
      "unitPrice": 6,
      "unitBasis": "TOKEN",
      "unitQuantity": 1000000,
      "mode": "EXPLICIT",
      "scope": {"cacheTtlSeconds": 3600}
    }
  ],
  "sourceType": "OFFICIAL_REFERENCE",
  "sourceRef": "...",
  "effectiveFrom": "2026-07-23T00:00:00+08:00",
  "status": "ACTIVE"
}
```

基础四项字段用于简化常见模型录入，服务端统一转换为价格组件；高级组件数组用于长上下文、TTL、存储和多模态价格。

服务端必须校验：

- 基础字段和组件数组不能冲突；
- 同一作用域只能匹配一个组件；
- `EXPLICIT_ZERO` 必须为 0；
- `NOT_APPLICABLE` 不能填写单价；
- `UNKNOWN` 不得激活为完整生产价格；
- `inputUnitPrice` 必须等于默认 `INPUT_TOKEN` 组件；
- `outputUnitPrice` 必须等于默认 `OUTPUT_TOKEN` 组件。

## 9.2 价格组件查询

新增：

```http
GET /api/provider-price-catalog/{id}/components
GET /api/price-versions/{id}/components
```

返回组件数组和完整作用域。

## 9.3 价格差异审核

价格差异接口应返回组件级差异：

```json
{
  "componentDiffs": [
    {
      "componentType": "CACHE_READ_TOKEN",
      "variant": "DEFAULT",
      "oldUnitPrice": 0.3,
      "newUnitPrice": 0.25,
      "changeRatio": -0.1667,
      "riskLevel": "LOW"
    }
  ]
}
```

以下变化应判定为高风险：

- 缓存价格从 0 变为非 0；
- 缓存价格从非 0 变为 0；
- 组件新增或删除；
- `EXPLICIT` 变为 `INHERIT_INPUT`；
- TTL、服务层级或上下文作用域变化；
- 计费单位或计费基数变化；
- 缓存命中价高于普通输入价且官方来源未明确说明。

## 9.4 模型生效价格

`GET /api/price-versions` 增加摘要字段：

```text
inputUncachedUnitPrice
cacheReadUnitPrice
cacheWriteUnitPrice
outputUnitPrice
cacheWriteVariantCount
priceCompletenessStatus
```

---

## 10. 价格源解析器改造

## 10.1 LiteLLM

继续映射：

```text
input_cost_per_token                   → INPUT_TOKEN
output_cost_per_token                  → OUTPUT_TOKEN
cache_read_input_token_cost            → CACHE_READ_TOKEN
cache_creation_input_token_cost        → CACHE_WRITE_TOKEN
```

同时增加：

- 长上下文缓存读取价格；
- 长上下文缓存写入价格；
- Priority / Flex / Batch 价格作用域；
- 解析不到官方字段时保留 `UNKNOWN`，不写 0。

## 10.2 models.dev

映射：

```text
input          → INPUT_TOKEN
output         → OUTPUT_TOKEN
cache_read     → CACHE_READ_TOKEN
cache_write    → CACHE_WRITE_TOKEN
```

models.dev 继续只作为公共参考，不直接覆盖生产价格。

## 10.3 DeepSeek 官方页面

当前解析逻辑已经正确区分缓存命中和未命中，应调整命名和输出：

```text
inputUnitPrice      → 输入价格（缓存未命中）
cacheReadUnitPrice  → 输入价格（缓存命中）
outputUnitPrice     → 输出价格
cacheWriteMode      → NOT_APPLICABLE
```

## 10.4 通用官方 JSON

推荐配置示例：

```json
{
  "inputField": "pricing.input_cache_miss",
  "outputField": "pricing.output",
  "componentFields": {
    "CACHE_READ_TOKEN": {
      "field": "pricing.input_cache_hit",
      "unit": "PER_1M_TOKENS"
    },
    "CACHE_WRITE_TOKEN": {
      "field": "pricing.cache_write",
      "unit": "PER_1M_TOKENS"
    }
  }
}
```

## 10.5 通用官方 CSV

当前 CSV 只处理输入和输出，应补充与 JSON 相同的 `componentFields` 配置。

示例：

```json
{
  "inputField": "input_cache_miss",
  "outputField": "output",
  "componentFields": {
    "CACHE_READ_TOKEN": "input_cache_hit",
    "CACHE_WRITE_TOKEN": "cache_write"
  }
}
```

---

## 11. Gateway Runtime 改造

## 11.1 Provider Usage Adapter

在通用成本计算前增加独立适配层：

```text
OpenAIUsageAdapter
AnthropicUsageAdapter
DeepSeekUsageAdapter
GeminiUsageAdapter
LiteLLMNormalizedUsageAdapter
```

选择依据：

```text
实际供应商类型
+ 实际模型部署
+ 上游协议
+ 返回 Usage 结构
```

不得仅依赖字段猜测。

## 11.2 成本计算器

当前成本计算器中的：

```text
cache_read_in_prompt
cache_write_in_prompt
reasoning_in_completion
```

应逐步移除。

新成本计算器只接收互斥标准用量：

```text
component_usage_quantity
× unit_price
÷ unit_quantity
```

组件选择流程：

```text
获取请求上下文
→ 按价格版本筛选匹配组件
→ 最具体作用域优先
→ 校验唯一匹配
→ 分项计算
→ 保存不可变成本快照
```

## 11.3 流式调用

必须验证：

- 流式响应最终 Usage 是否包含缓存字段；
- `stream_options.include_usage` 是否已经开启；
- SSE 多个 Usage 片段不能重复累计；
- 最终汇总片段覆盖中间片段，而不是相加；
- 上游未返回最终 Usage 时标记成本不完整。

## 11.4 Fallback

每次路由尝试分别保存：

```text
实际供应商
实际模型
缓存用量
价格版本
分项成本
```

不能用最终成功模型的缓存价格计算前一次失败尝试。

---

## 12. Console 页面改造

## 12.1 供应商官方价格目录

列表字段调整为：

```text
供应商
模型
币种
缓存未命中输入价
缓存命中输入价
缓存写入价
输出价
价格完整性
来源
生效时间
状态
```

对于复杂价格：

```text
缓存写入价：2 个变体
长上下文：已配置
服务层级：3 个
```

点击后查看完整组件。

### 新建/编辑表单

分为两个区域：

```text
基础价格
高级价格组件
```

基础价格默认字段：

```text
输入价格（缓存未命中）
输入价格（缓存命中）
输入价格（缓存写入）
输出价格
```

每个缓存字段旁增加状态选择：

```text
明确价格
明确免费
沿用普通输入价
不适用
尚未确认
```

高级组件使用可增删表格，不要求管理员编辑 JSON。

## 12.2 价格差异审核

新增四项价格对比卡：

```text
缓存未命中输入
缓存命中输入
缓存写入
输出
```

复杂组件使用树形或分组表格展示：

```text
组件类型
变体
作用域
旧价格
新价格
变化比例
风险
```

## 12.3 模型生效价格

列表价格摘要改为：

```text
输入未命中 3.00
缓存命中 0.30
缓存写入 3.75
输出 15.00
USD / 1M Token
```

路由价格版本下拉框同步展示四项价格，不再只显示输入、输出。

## 12.4 调用日志与调用链

请求详情新增：

```text
普通输入 Token
缓存命中 Token
缓存写入 Token
输出 Token
推理 Token
缓存命中率
普通输入成本
缓存读取成本
缓存写入成本
输出成本
缓存净节省
价格版本
计价模型
Usage 原始证据
```

## 12.5 用量分析

新增 KPI：

```text
缓存命中 Token
缓存命中率
缓存读取成本
缓存写入成本
缓存净节省
未识别缓存 Usage 请求数
缓存价格不完整请求数
```

缓存命中率建议按 Token 计算：

```text
cache_read_tokens
÷ (input_uncached_tokens + cache_read_tokens)
```

缓存写入 Token 不放入该分母，避免首次建缓存导致命中率失真。

新增图表：

- 按供应商缓存命中率；
- 按模型缓存净节省；
- 按应用缓存命中率；
- 缓存读取成本与普通输入成本对比；
- 缓存命中率趋势；
- 缓存价格不完整告警趋势。

---

## 13. 告警与治理规则

新增告警类型：

```text
CACHE_PRICE_MISSING
CACHE_USAGE_UNRECOGNIZED
CACHE_USAGE_INCONSISTENT
CACHE_COMPONENT_AMBIGUOUS
CACHE_PRICE_ABNORMAL
CACHE_COST_SNAPSHOT_INCOMPLETE
```

建议规则：

| 场景 | 级别 |
|---|---|
| 模型返回缓存 Token，但价格版本没有缓存读取组件 | ERROR |
| 缓存读取价高于普通输入价且无官方依据 | WARNING |
| 缓存 Token 大于总输入 Token | ERROR |
| 同一作用域匹配多个缓存写入组件 | ERROR |
| 缓存价格来源超过设定有效期 | WARNING |
| 上游返回缓存字段但 Usage Adapter 未识别 | ERROR |
| 价格差异出现组件新增、删除或单位变化 | HIGH RISK |

企业服务模型发布和路由生效时，应校验目标模型的缓存价格完整性。

---

## 14. 与网关响应缓存的边界

本次改造只解决“供应商 Prompt/Context Cache 的差异化计价”，不在本次范围内实现 TokenSea 自身的响应缓存。

后续如果建设网关响应缓存，应新增独立模型：

```text
gateway_cache_status
GATEWAY_CACHE_HIT
GATEWAY_CACHE_MISS
GATEWAY_SEMANTIC_CACHE_HIT
GATEWAY_CACHE_REFRESH
```

网关响应缓存命中时：

- 不调用供应商；
- 不生成供应商 Prompt Cache 用量；
- 供应商成本为 0；
- 可以记录 TokenSea 自身缓存存储成本；
- 单独计算节省的推定上游成本；
- 不得伪造 `CACHE_READ_TOKEN`。

---

## 15. 开发实施顺序

## P0：基础四项价格闭环

1. 供应商官方价格目录增加缓存命中、缓存写入表单字段；
2. 手工目录接口支持基础四项价格；
3. 目录列表、详情、价格差异、模型生效价格显示四项价格；
4. 路由价格版本下拉显示四项价格；
5. DeepSeek 原生 Usage 字段适配；
6. 请求快照增加 `input_uncached_tokens`；
7. 用量页面展示缓存 Token、成本和命中率；
8. 缓存 Token 双计费回归测试。

## P1：多变体价格组件

1. `price_components` 改为数组；
2. 支持相同组件的多个作用域；
3. Anthropic 5 分钟 / 1 小时缓存写入价格；
4. 长上下文缓存价格；
5. 通用 CSV 组件映射；
6. 组件级价格差异审核；
7. 价格完整性与告警。

## P2：缓存成本治理

1. Gemini 缓存存储 Token·小时费用；
2. 缓存净节省分析；
3. 按租户、项目、应用、Key 的缓存收益排行；
4. 供应商账单缓存分项对账；
5. 缓存友好度参与成本路由；
6. 后续独立建设 TokenSea 网关响应缓存。

---

## 16. 建议修改文件

### Console

```text
apps/console/src/config/resources.ts
apps/console/src/pages/RoutePolicies.vue
apps/console/src/pages/Calls.vue
apps/console/src/pages/UsageAnalysis.vue
apps/console/src/pages/DataPage.vue
```

必要时新增：

```text
apps/console/src/components/PricingComponentEditor.vue
apps/console/src/components/CachePriceSummary.vue
apps/console/src/components/CostFormulaViewer.vue
```

### Control Plane

```text
services/control-plane/src/main/java/com/tokensea/governance/PriceSourceParser.java
services/control-plane/src/main/java/com/tokensea/governance/ProviderPriceCatalogController.java
services/control-plane/src/main/java/com/tokensea/governance/ProviderPriceCatalogService.java
services/control-plane/src/main/java/com/tokensea/governance/ProviderPriceSyncService.java
services/control-plane/src/main/java/com/tokensea/governance/GovernanceController.java
```

建议新增：

```text
PricingComponentValidator.java
PricingComponentSelector.java
CachePricingCompletenessService.java
```

### Gateway Runtime

```text
services/gateway-runtime/app/main.py
```

建议拆分：

```text
services/gateway-runtime/app/usage_normalizer.py
services/gateway-runtime/app/provider_usage_adapters.py
services/gateway-runtime/app/cost_calculator.py
```

### 数据库

```text
services/control-plane/src/main/resources/db/migration/
V19__cache_aware_pricing_components.sql
```

---

## 17. 测试方案

## 17.1 价格解析测试

覆盖：

- DeepSeek 缓存命中 / 未命中价格；
- LiteLLM 缓存读取 / 缓存创建价格；
- models.dev 缓存字段；
- 官方 JSON 组件映射；
- 官方 CSV 组件映射；
- Anthropic 多 TTL 缓存写入组件；
- 单位从每 Token、每千 Token转换为每百万 Token。

## 17.2 Usage 标准化测试

覆盖：

- OpenAI `prompt_tokens_details.cached_tokens`；
- DeepSeek `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`；
- Anthropic `cache_read_input_tokens` / `cache_creation_input_tokens`；
- Gemini `cachedContentTokenCount`；
- 流式最终 Usage；
- 缓存字段包含于总输入和不包含于总输入两种语义；
- 异常负数、超出总输入和字段不一致。

## 17.3 成本计算测试

必须验证：

```text
普通输入成本正确
缓存命中成本正确
缓存写入成本正确
输出成本正确
缓存 Token 不重复计费
同类型多变体选择正确
未知缓存价格不会被当作 0
明确免费价格可以计算为 0
Fallback 每次尝试独立计费
历史快照不受新价格影响
```

## 17.4 页面测试

- 目录可录入基础四项价格；
- 缓存不适用、明确免费、未知状态可区分；
- 高级组件支持多个 TTL；
- 差异审核正确显示组件变化；
- 路由下拉展示完整价格摘要；
- 调用详情可解释成本公式；
- 用量分析缓存命中率与节省金额正确。

---

## 18. 验收标准

### 价格管理

- [ ] 管理员能录入缓存未命中、缓存命中、缓存写入和输出价格；
- [ ] 模型官方价格目录能够展示四项价格；
- [ ] 多 TTL、长上下文和服务层级价格可通过高级组件表达；
- [ ] 价格组件有来源、版本、生效时间和状态；
- [ ] 缓存价格未知、免费、不适用和继承输入价可以明确区分。

### Usage 与成本

- [ ] DeepSeek、OpenAI、Anthropic、Gemini 缓存用量可标准化；
- [ ] 缓存 Token 不与普通输入重复计费；
- [ ] 每次请求保存普通输入、缓存读取、缓存写入、输出分项；
- [ ] 请求详情可以还原完整成本公式；
- [ ] 缓存命中率和缓存净节省可按租户、应用、Key、模型和供应商统计。

### 治理

- [ ] 模型返回缓存 Usage 但价格缺失时产生告警；
- [ ] 组件新增、删除、单位或作用域变化进入高风险审核；
- [ ] 路由生效前校验价格完整性；
- [ ] 未知缓存价格不会静默记为 0；
- [ ] 供应商 Prompt Cache 与 TokenSea 网关响应缓存保持独立。

---

## 19. 最终建议

TokenSea 不应只在现有价格表中简单增加一个“缓存命中价格”列。完整改造需要同时覆盖：

```text
价格目录
→ 价格组件
→ 价格同步
→ 差异审核
→ 模型生效价格
→ 路由价格选择
→ Provider Usage 标准化
→ Gateway 成本计算
→ 请求级成本快照
→ 用量分析
→ 告警与对账
```

最适合当前项目的设计是：

> 页面采用 CC Switch 类似的“缓存未命中输入、缓存命中输入、缓存写入、输出”四项基础价格；底层继续使用 TokenSea 已有的通用价格组件，并升级为可表达同类型多作用域组件的数组结构；成本计算前由 Provider Adapter 生成互斥标准用量，彻底消除缓存 Token 双计费风险。

---

## 20. 参考资料

以下资料用于理解产品设计和供应商 Usage 语义，不作为 TokenSea 生产价格的直接权威源。生产价格仍须以供应商当日官方价格来源、原始快照和 TokenSea 审核记录为准。

1. CC Switch Usage Statistics and Pricing Configuration  
   <https://github.com/farion1231/cc-switch/blob/main/docs/user-manual/en/4-proxy/4.4-usage.md>
2. CC Switch v3.16.3 Release Notes  
   <https://github.com/farion1231/cc-switch/releases>
3. LiteLLM Custom Pricing  
   <https://github.com/BerriAI/litellm/blob/main/docs/my-website/docs/proxy/custom_pricing.md>
4. DeepSeek Context Caching  
   <https://api-docs.deepseek.com/zh-cn/guides/kv_cache>
5. DeepSeek Chat Completion Usage Fields  
   <https://api-docs.deepseek.com/zh-cn/api/create-chat-completion>
6. DeepSeek Models and Pricing  
   <https://api-docs.deepseek.com/zh-cn/quick_start/pricing>
7. Anthropic Pricing and Prompt Caching  
   <https://docs.anthropic.com/en/docs/about-claude/pricing>
8. OpenAI Model Pricing and Cached Input  
   <https://developers.openai.com/api/docs/models/compare>
9. Google Gemini Context Caching  
   <https://ai.google.dev/gemini-api/docs/caching>
10. Google Gemini API Pricing  
    <https://ai.google.dev/gemini-api/docs/pricing>
11. OpenRouter Prompt Caching  
    <https://openrouter.ai/docs/guides/best-practices/prompt-caching>
12. OpenRouter Response Caching  
    <https://openrouter.ai/docs/guides/features/response-caching>
13. Portkey Cache Documentation  
    <https://portkey.ai/docs/product/ai-gateway/cache-simple-and-semantic>
