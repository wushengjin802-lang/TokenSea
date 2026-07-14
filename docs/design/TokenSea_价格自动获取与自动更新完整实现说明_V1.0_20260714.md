# TokenSea 价格自动获取与自动更新完整实现说明

版本：V1.0  
日期：2026-07-14  
对应设计：`TokenSea_LiteLLM与CCSwitch成本价格体系分析及落地方案_V1.0_20260714.md`  
实施范围：公共价格参考、供应商官方价格获取、原始快照、差异治理、自动更新、模型部署价格匹配、Gateway 成本计算和管理页面  
明确不包含：供应商账户余额、额度、充值、合同价、内部核算价和对客销售价

---

## 1. 实施结论

本轮已经把原设计中的“价格自动化上半段”和 TokenSea 已有的“价格运行下半段”连接为完整闭环：

```text
LiteLLM Cost Map / models.dev
供应商官方价格页面
供应商官方 JSON / CSV 价格接口
                 │
                 ▼
          价格源管理与定时任务
                 │
                 ▼
        原始响应与证据快照留存
                 │
                 ▼
         专用适配器解析与标准化
                 │
                 ▼
        公共参考库 / 官方价格差异
                 │
        ┌────────┴────────┐
        ▼                 ▼
可信低风险自动发布    高风险人工审核
        │                 │
        └────────┬────────┘
                 ▼
 provider_model_price_catalog
                 │
                 ▼
 自动匹配 channel_model_deployment
                 │
                 ▼
 price_version(PROVIDER_OFFICIAL)
                 │
                 ▼
 Gateway 按实际路由和 usage 计算成本
                 │
                 ▼
       usage_cost_snapshot 不可变快照
```

实现后，管理员不再需要逐模型手工填写价格。系统可以自动下载原始价格、保存证据、识别变化、按规则发布新价格版本，并使后续请求自动使用新价格；历史请求成本保持不变。

---

## 2. 实现边界

### 2.1 本轮已实现

- LiteLLM Model Cost Map 自动同步；
- models.dev 公共模型价格自动同步；
- DeepSeek 官方价格页面专用适配器；
- 通用供应商官方 JSON 价格 API 适配器；
- 通用供应商官方 CSV 价格源适配器；
- 供应商官方 API 使用 TokenSea 托管渠道凭据认证；
- 定时同步和管理员立即同步；
- ETag、Last-Modified、SHA-256 增量判断；
- 原始价格响应永久证据快照；
- 价格标准化和高精度单位换算；
- 输入、输出、缓存读取、缓存写入、推理等价格组件；
- 新增、变化、删除、币种变化、计费维度变化等差异；
- 低风险自动发布；
- 连续多次一致确认；
- 高风险人工审核；
- 价格发布、审核、同步、失败告警和操作审计；
- 模型部署自动匹配官方价格；
- 自动生成并替换 `PROVIDER_OFFICIAL` 价格版本；
- Gateway 读取生效价格组件；
- 缓存 Token 避免重复计费；
- 请求级不可变成本快照；
- 数据库动态出口域名策略；
- 价格源、同步任务、原始快照、差异审核和公共参考页面。

### 2.2 本轮明确不实现

- API 账户剩余额度；
- 供应商余额查询；
- 充值和套餐；
- 企业合同折扣价；
- 渠道折扣价；
- 内部核算价的新增治理；
- 对客销售价的新增治理；
- 税费、汇率和毛利；
- 供应商账单自动下载；
- 所有供应商的专用网页适配器。

### 2.3 适配器覆盖说明

当前已经具备一套可扩展的完整同步框架，并落地以下实际适配器：

| 适配器 | 来源定位 | 是否可进入生产官方价格 |
|---|---|---|
| `LITELLM_COST_MAP` | 公共参考 | 否 |
| `MODELS_DEV` | 公共参考 | 否 |
| `DEEPSEEK_OFFICIAL_PAGE` | DeepSeek 官方价格页 | 是 |
| `OFFICIAL_JSON` | 供应商官方结构化 JSON/API | 是 |
| `OFFICIAL_CSV` | 供应商官方 CSV | 是 |

OpenAI、Anthropic、Qwen、智谱、火山等官方网页结构差异较大，后续应逐供应商增加专用页面适配器；若供应商提供结构化官方 JSON/CSV，则可直接通过本轮通用适配器接入，无需新增后端代码。

---

## 3. 代码变更总览

## 3.1 数据库迁移

新增：

```text
services/control-plane/src/main/resources/db/migration/
V14__price_source_sync_and_components.sql
```

该迁移只向前增加表、字段、约束和索引，不修改 V1～V13。

新增表：

```text
provider_price_source
provider_price_sync_run
provider_price_raw_snapshot
provider_price_diff
public_model_price_reference
provider_price_component
```

扩展表：

```text
public_model_reference
provider_model_price_catalog
price_version
usage_cost_snapshot
```

内置但默认暂停的价格源：

```text
LiteLLM 公共成本参考
models.dev 公共模型参考
DeepSeek 官方价格页
```

DeepSeek 官方价格源默认配置：

```text
auto_publish = true
max_auto_change_ratio = 0.30
confirmation_runs = 2
status = PAUSED
```

管理员启用后，新价格必须连续两次获得相同解析结果，且变化风险满足规则，才会自动发布。

---

## 3.2 控制面新增代码

### 价格解析器

```text
PriceSourceParser.java
```

职责：

- 根据 `adapter_code` 选择解析器；
- 将各种来源转换为统一 `NormalizedPrice`；
- 把每 Token、每千 Token、每百万 Token 统一转换为每千 Token；
- 生成完整价格组件；
- 对来源结构变化快速失败；
- 使用 `BigDecimal` 保证金额精度。

### 价格同步服务

```text
ProviderPriceSyncService.java
```

职责：

- 定时扫描到期价格源；
- 防止同一价格源并发运行多个同步任务；
- 调用外部价格来源；
- 条件请求和内容哈希判断；
- 保存原始快照；
- 标准化；
- 创建公共参考价格；
- 创建官方价格差异；
- 风险判断；
- 连续一致确认；
- 自动发布和人工批准发布；
- 生成告警和审计；
- 发布后自动重新匹配模型部署。

### 价格同步控制器

```text
ProviderPriceSyncController.java
```

职责：

- 价格源 CRUD；
- 测试获取；
- 立即同步；
- 启用和暂停；
- 同步任务查询；
- 原始快照查询；
- 差异查询、批准和驳回；
- 公共价格参考查询。

### 动态出口策略

```text
EgressPolicyController.java
```

职责：

- 向独立出口代理提供已经受控配置的官方价格源域名；
- 使用内部随机 Token 鉴权；
- 不向浏览器开放供应商密钥；
- 不允许通配符主机。

### 供应商渠道凭据复用

修改：

```text
ProviderConnectionService.java
```

增加服务端受控方法：

```text
resolveManagedApiKey()
applyManagedAuthentication()
```

浏览器只提交 `providerInstanceId`，控制面从密钥托管表读取并解密凭据。原始 Key 不返回前端，也不写入同步日志和原始响应快照。

---

## 3.3 Gateway Runtime

修改：

```text
services/gateway-runtime/app/main.py
```

主要变化：

1. 优先读取 `PROVIDER_OFFICIAL` 生效价格；
2. 读取价格组件、证据哈希、区域、调用模式和服务层级；
3. 解析响应模型和用量明细；
4. 支持缓存读取、缓存写入和推理 Token；
5. 避免把已包含在 `prompt_tokens` 中的缓存 Token 再按普通输入价格重复计算；
6. 使用 Python `Decimal` 计算；
7. 按实际 Fallback 路由部署计算成本；
8. 将价格版本、价格组件、成本分项和计算器版本写入请求级成本快照；
9. `usage_cost_snapshot` 改为不可变：冲突时不覆盖历史快照；
10. 无价格时返回明确的 `TOKENSEA_PRICE_NOT_CONFIGURED`，不按零价运行。

成本计算示意：

```text
普通输入成本
+ 输出成本
+ 缓存读取成本
+ 缓存写入成本
+ 推理 Token 成本
= 请求实际成本
```

非 Token 类价格组件已经可以保存，但当前 Gateway 自动核算主要执行 Token 类组件；图片、音频、视频和按请求计价需要在对应 API 端点补充用量标准化后再参与运行时计算。

---

## 3.4 出口代理

修改：

```text
services/egress-proxy/app/proxy.py
```

新增：

- 环境变量全局基线域名；
- 数据库价格源动态域名；
- 每 15 秒刷新动态策略；
- 动态域名与基线域名合并；
- 动态规则不改变允许端口；
- DNS 和最终 IP 安全校验；
- 动态策略获取失败时保留上一份已成功规则；
- 日志只记录主机数量和错误类型，不记录路径、请求头或密钥。

安全链路：

```text
控制台配置官方价格源
→ 控制面校验 HTTPS 和官方域名
→ 数据库存储
→ 出口代理通过内部 Token 获取动态域名
→ DNS/IP 安全检查
→ 访问官方价格源
```

这样不再要求每新增一个供应商都修改 `.env` 并重启整个系统。

---

## 3.5 Console

修改：

```text
apps/console/src/config/menu.ts
apps/console/src/config/resources.ts
```

新增菜单：

```text
模型中心
└─ 公共价格参考

同步中心
├─ 价格源管理
├─ 价格同步任务
├─ 价格原始快照
└─ 价格差异审核
```

页面能力：

- 新建和修改价格源；
- 选择公共参考或供应商官方来源；
- 选择适配器；
- 选择供应商类型；
- 选择是否复用供应商渠道凭据；
- 选择供应商渠道；
- 配置官方域名、币种、区域、周期和自动发布策略；
- 测试获取；
- 立即同步；
- 查看执行结果和错误；
- 查看原始响应；
- 对高风险差异批准或驳回；
- 查看公共参考价格与官方生效价格。

所有状态、来源、适配器、认证方式、币种和区域均使用中文下拉选项。

---

## 4. 数据结构说明

## 4.1 provider_price_source

代表一个可执行的价格来源。

关键字段：

```text
source_class
adapter_code
provider_type
provider_instance_id
auth_mode
endpoint
official_hosts
region
default_currency
schedule_expression
auto_publish
max_auto_change_ratio
confirmation_runs
etag
last_modified
last_content_hash
parser_version
status
```

认证方式：

```text
NONE
PROVIDER_INSTANCE
```

`PROVIDER_INSTANCE` 表示复用 TokenSea 供应商渠道已经托管的 API Key。当前不允许浏览器直接提交原始 Key。

## 4.2 provider_price_sync_run

每次同步的执行记录：

```text
触发方式
执行状态
HTTP 状态
读取数量
标准化数量
变化数量
自动发布数量
待审核数量
开始/完成时间
错误码和错误信息
执行日志
```

同一价格源最多只能存在一个 `PENDING` 或 `RUNNING` 任务，避免集群重复同步。

## 4.3 provider_price_raw_snapshot

保存供应商原始响应：

```text
来源地址
最终地址
HTTP 状态
Content-Type
ETag
Last-Modified
SHA-256
响应字节数
原始正文
解析器版本
获取时间
```

快照不会保存请求认证头，也不会保存供应商 API Key。

## 4.4 public_model_price_reference

独立保存 LiteLLM、models.dev 等不同公共来源的价格，避免多个来源互相覆盖。

公共参考价格不会直接进入生产 `price_version`。

## 4.5 provider_price_diff

记录官方价格变化：

```text
MODEL_ADDED
MODEL_REMOVED
PRICE_CHANGED
CURRENCY_CHANGED
UNIT_CHANGED
BILLING_DIMENSION_CHANGED
REGION_CHANGED
MODEL_MAPPING_CHANGED
SOURCE_STRUCTURE_CHANGED
```

风险等级：

```text
LOW
MEDIUM
HIGH
CRITICAL
```

处理状态：

```text
PENDING
AUTO_PUBLISHED
APPROVED
REJECTED
IGNORED
```

## 4.6 provider_price_component

用于保存可扩展计费组件：

```text
INPUT_TOKEN
OUTPUT_TOKEN
CACHE_READ_TOKEN
CACHE_WRITE_TOKEN
REASONING_TOKEN
INPUT_TOKEN_ABOVE_200K
OUTPUT_TOKEN_ABOVE_200K
IMAGE_INPUT
IMAGE_OUTPUT
AUDIO_SECOND
VIDEO_SECOND
REQUEST
```

## 4.7 usage_cost_snapshot

每个请求保存：

```text
price_version_id
price_layer
currency
prompt_tokens
completion_tokens
cache_read_tokens
cache_write_tokens
reasoning_tokens
price_components
cost_components
pricing_model
response_model
provider_instance_id
model_deployment_id
calculator_version
evidence_hash
actual_cost_amount
```

该记录创建后不因后续价格变化而更新。

---

## 5. 自动更新规则

## 5.1 无变化

系统使用：

```text
ETag
Last-Modified
SHA-256
```

判断来源是否变化。

若无变化且不存在连续确认任务：

```text
状态 = NO_CHANGE
不生成新快照
不生成价格版本
不清理运行时价格
```

## 5.2 连续确认

官方网页来源可配置：

```text
confirmation_runs = 2
```

第一次同步发现低风险变化后保留待确认差异。第二次同步即使服务器返回 304，系统也会回放上一份原始快照，再次解析和比较。两次结果一致后才允许自动发布。

## 5.3 自动发布

必须同时满足：

- 来源类别为供应商官方价格；
- `auto_publish = true`；
- 解析结构有效；
- 模型和价格字段完整；
- 风险等级为 `LOW`；
- 价格变化不超过 `max_auto_change_ratio`；
- 币种未变化；
- 计费维度未变化；
- 连续确认次数达到要求；
- 发布事务和审计写入成功。

发布动作：

```text
旧官方目录记录 → INACTIVE
新官方目录记录 → ACTIVE
旧 PROVIDER_OFFICIAL price_version → RETIRED
新 PROVIDER_OFFICIAL price_version → ACTIVE
后续请求使用新价格
历史请求快照保持不变
```

## 5.4 必须审核

以下变化保持旧价格生效，并进入审核：

- 变化超过阈值；
- 币种变化；
- 计费单位变化；
- 新增或删除缓存/推理等计费维度；
- 官方来源中模型消失；
- 页面结构变化；
- 同一模型出现冲突价格；
- 模型或区域映射变化。

## 5.5 同步失败

```text
同步任务 → FAILED
价格源 → DEGRADED
生成告警
保留上一条 ACTIVE 价格
不生成零价格
不停止已有生产调用
```

如果价格源本身处于暂停或停用状态，手动测试失败不会把它重新改为启用状态。

---

## 6. 供应商官方 API 接入

## 6.1 无认证官方 API

配置：

```json
{
  "sourceClass": "OFFICIAL",
  "adapterCode": "OFFICIAL_JSON",
  "providerType": "provider-a",
  "authMode": "NONE",
  "endpoint": "https://official.example/prices",
  "officialHosts": ["official.example"],
  "defaultCurrency": "USD",
  "config": {
    "recordsPath": "data",
    "modelField": "model",
    "inputField": "input_price",
    "outputField": "output_price",
    "currencyField": "currency",
    "unit": "PER_1M_TOKENS"
  }
}
```

## 6.2 使用供应商渠道凭据

配置：

```json
{
  "sourceClass": "OFFICIAL",
  "adapterCode": "OFFICIAL_JSON",
  "providerType": "provider-a",
  "authMode": "PROVIDER_INSTANCE",
  "providerInstanceId": "已配置渠道 ID",
  "endpoint": "https://official.example/prices"
}
```

控制面将：

```text
读取 provider_instance
→ 查找 ACTIVE provider_secret
→ 服务端解密
→ 按渠道 api_style 添加认证头
→ 经出口代理访问价格 API
```

支持现有渠道认证语义：

```text
OpenAI-compatible：Authorization: Bearer
Azure：api-key
Gemini：x-goog-api-key
Anthropic：x-api-key + anthropic-version
```

安全限制：认证信息只发送给原始主机；如果官方接口重定向到其他主机，系统不会转发认证头。

当前限制：如果供应商的计费管理 API 必须使用与推理 Key 不同的管理员 Key，需要后续增加独立的“价格同步凭据”类型。本轮不会把任意明文 Key 存入价格源表。

---

## 7. DeepSeek 官方价格自动同步

数据库已内置：

```text
adapter_code = DEEPSEEK_OFFICIAL_PAGE
provider_type = deepseek
endpoint = https://api-docs.deepseek.com/quick_start/pricing/
currency = USD
confirmation_runs = 2
auto_publish = true
status = PAUSED
```

使用步骤：

1. 进入“同步中心 → 价格源管理”；
2. 找到“DeepSeek 官方价格页”；
3. 点击“启用”；
4. 等待动态出口规则刷新；
5. 点击“测试获取”；
6. 点击“立即同步”；
7. 第一次变化进入连续确认；
8. 第二次同步结果一致且风险低时自动发布；
9. 模型部署自动匹配；
10. “模型生效价格”出现新的 `PROVIDER_OFFICIAL` 版本。

适配器识别：

- 模型名称；
- 每百万输入 Token 缓存命中价；
- 每百万输入 Token 缓存未命中价；
- 每百万输出 Token 价；
- 美元币种；
- 官方页面来源。

页面结构发生变化时，适配器不会输出猜测价格，而是使同步失败并保留旧价格。

---

## 8. 控制面 API

### 价格源

```text
GET    /api/provider-price-sources
POST   /api/provider-price-sources
PATCH  /api/provider-price-sources/{id}
POST   /api/provider-price-sources/{id}/test
POST   /api/provider-price-sources/{id}/sync
POST   /api/provider-price-sources/{id}/enable
POST   /api/provider-price-sources/{id}/pause
```

### 同步任务

```text
GET /api/provider-price-sync-runs
GET /api/provider-price-sync-runs/{id}
```

### 原始快照

```text
GET /api/provider-price-snapshots
GET /api/provider-price-snapshots/{id}
```

### 价格差异

```text
GET  /api/provider-price-diffs
GET  /api/provider-price-diffs/{id}
POST /api/provider-price-diffs/{id}/approve
POST /api/provider-price-diffs/{id}/reject
```

### 公共价格参考

```text
GET /api/public-price-references
GET /api/public-price-references/{id}
```

### 内部出口策略

```text
GET /internal/egress/allowed-hosts
X-TokenSea-Egress-Policy-Token: <internal token>
```

该内部接口不使用浏览器 JWT，而使用独立的机器间随机 Token。

---

## 9. 部署配置

新增环境变量：

```text
TOKENSEA_EGRESS_POLICY_TOKEN
TOKENSEA_EGRESS_POLICY_REFRESH_SECONDS
```

必须设置随机内部 Token：

```text
TOKENSEA_EGRESS_POLICY_TOKEN=<至少32字节随机值>
```

权威环境模板：

```text
deploy/compose/.env.example
```

部署流程：

```powershell
cd D:\12_其他项目\30_APIGateway\tokensea\deploy\compose
Copy-Item .env.example .env
# 修改 .env 中所有 REPLACE_* 值，并配置 TOKENSEA_EGRESS_POLICY_TOKEN
docker compose -p tokensea --env-file ./.env up -d --build
```

启动后 Flyway 自动执行 V14。

本轮代码修改没有主动重建或部署本地正式 TokenSea Compose 环境，只使用临时 PostgreSQL 容器完成迁移和集成测试。

---

## 10. 验证结果

### 10.1 控制面

已通过：

```text
JDK 21 Maven 编译与打包
PriceSourceParserTests
ControlPlaneContractTests
Phase1GContractTests
Phase4CContractTests
ProviderPriceSyncIntegrationTests
```

集成测试使用临时 PostgreSQL 16，实际执行：

```text
Flyway V1 → V14
官方价格差异批准
官方价格目录发布
价格组件写入
公共参考价格写入
公共模型参考更新
```

### 10.2 Gateway Runtime

```text
24 passed
10 subtests passed
```

覆盖缓存 Token 不重复计费和价格组件成本计算。

### 10.3 出口代理

```text
24 passed
```

覆盖静态与动态域名合并、私网和元数据地址阻断、DNS 固定和请求转发安全。

### 10.4 Console

```text
vue-tsc --noEmit 通过
Vite production build 通过
```

存在原项目已有的大包体积警告，不影响构建成功。

### 10.5 Compose

```text
docker compose config 通过
```

### 10.6 数据库

在临时 PostgreSQL 16 容器中按顺序执行 V1～V14 全部迁移成功。

### 10.7 代码格式

```text
git diff --check 通过
```

Windows 工作区仍会提示 LF 后续可能转换为 CRLF，该提示不是代码格式错误。

---

## 11. 当前运行流程

### 公共参考同步

```text
启用 LiteLLM/models.dev
→ 自动下载
→ 原始快照
→ 标准化
→ public_model_price_reference
→ 公共价格参考页面
→ 不进入生产价格
```

### 官方价格同步

```text
启用官方价格源
→ 动态出口域名生效
→ 定时获取
→ 原始快照
→ 标准化
→ 价格差异
→ 自动发布或审核
→ 官方价格目录
→ 自动匹配模型部署
→ 生效价格版本
```

### 请求成本

```text
Virtual Key 请求
→ 实际路由部署
→ 上游响应 usage
→ Token 语义标准化
→ 读取该部署生效价格组件
→ Decimal 计算
→ usage_record
→ 不可变 usage_cost_snapshot
```

---

## 12. 生产使用注意事项

1. V14 应先在备份数据库或预生产环境执行；
2. `TOKENSEA_EGRESS_POLICY_TOKEN` 必须使用随机强 Token；
3. 内置价格源默认暂停，部署后由管理员逐个启用；
4. 公共参考来源不得改成生产自动发布；
5. 新官方页面适配器初次上线建议设置 `confirmation_runs=2`；
6. 大幅价格变化必须保留人工审核；
7. 不要将搜索引擎、博客或媒体报价配置为官方生产价格源；
8. 供应商官方页面结构变化后应升级解析器版本；
9. Gateway 历史成本快照不得回填覆盖；
10. 图片、音频、视频和按请求计费在正式启用前必须补充对应端点的用量标准化测试；
11. 供应商官方 API 使用不同管理员凭据时，需要增加独立价格同步凭据，不能把管理 Key 填入普通配置 JSON；
12. 价格自动发布应先在试点环境观察至少一个价格更新周期。

---

## 13. 尚未穷尽但不阻塞本轮闭环的事项

### 13.1 供应商专用适配器数量

本轮完成了通用框架、DeepSeek 专用页面适配器和通用 JSON/CSV 适配器，但没有声称穷尽所有供应商。新增供应商时按照以下顺序接入：

```text
官方机器可读 API
→ OFFICIAL_JSON / OFFICIAL_CSV
→ 官方页面专用适配器
→ 受控文件导入
```

### 13.2 非 Token 计费

数据库已经支持多类价格组件，但 Gateway 当前重点完成 Token、缓存和推理 Token 成本。图像、音频和视频等需要结合相应接口的实际 usage 返回结构继续补充。

### 13.3 独立计费管理员凭据

当前认证价格 API 可复用供应商渠道托管凭据。供应商若要求组织管理员 Key 或 Billing Key，应新增独立凭据用途，不能复用推理 Key 语义。

### 13.4 集群调度增强

当前通过数据库唯一索引和任务状态认领避免同一来源并发执行，已经适合多实例基础运行。更大规模环境可进一步增加：

- `FOR UPDATE SKIP LOCKED` 批量认领；
- 任务租约超时恢复；
- 多节点心跳监控；
- 独立 Worker 服务。

---

## 14. 最终状态判断

本轮已经完成“自动获取原始价格、自动保存证据、自动识别变化、自动发布低风险价格、人工审核高风险变化、自动匹配模型部署、自动更新运行价格、请求级成本核算”的完整技术闭环。

准确状态为：

```text
价格自动同步框架：已实现
LiteLLM 公共参考：已实现
models.dev 公共参考：已实现
DeepSeek 官方价格页：已实现
通用官方 JSON/CSV：已实现
渠道托管凭据认证：已实现
原始快照与审计：已实现
价格差异与审核：已实现
低风险自动发布：已实现
模型部署价格自动更新：已实现
Gateway 缓存/推理 Token 成本：已实现
所有供应商专用网页适配器：未穷尽，按供应商逐步增加
额度/余额：按本轮要求未实现
```

数据库是运行时唯一价格权威源；LiteLLM 和 models.dev 只作为公共参考；供应商官方来源经过证据、差异和版本治理后才能成为生产价格。
