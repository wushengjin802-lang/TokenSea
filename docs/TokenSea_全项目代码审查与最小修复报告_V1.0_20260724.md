# TokenSea 全项目代码审查与最小修复报告

- **版本**：V1.0
- **日期**：2026-07-24
- **最近更新**：2026-07-27（完成 TS-CR-R07）
- **审查目录**：`D:\12_其他项目\30_APIGateway\tokensea`
- **审查原则**：不新增功能；优先修复确定性问题；采用最小改动；不改数据库业务数据；不启动、停止或重建 Docker 服务；无法确认产品语义的问题保留并记录。
- **文档依据**：项目代码、`README.md`、`AGENTS.md`、现有设计/修订记录，以及《企业级统一 LLM API Gateway 平台 PRD》和《LiteLLM 详细分析报告》。产品文档用于判断目标边界，当前代码是本次审查的事实来源。

---

## 1. 审查结论

TokenSea 当前已经形成了较完整的企业级 LLM API Gateway MVP 闭环，整体采用“自研控制面 + 自研安全网关 + LiteLLM Runtime Core”的分层方案，而不是直接把 LiteLLM Admin UI 当作最终产品。代码已覆盖：

1. 供应商渠道、密钥托管、连接测试与模型发现；
2. 官方价格同步、原始快照、价格差异审核、价格版本和有效成本价格；
3. 模型部署生命周期、能力探测、生产准入、路由和企业服务模型发布；
4. 租户、项目、应用、账户、角色和 Virtual Key；
5. OpenAI-compatible API、流式响应、路由、重试、Fallback、限流和预算；
6. 用量、成本快照、汇率、账务 Outbox、调用链、审计、告警和监控；
7. 受控出口代理、Headless 页面抓取、Docker Compose 和可观测组件。

本次审查发现的确定性问题中，已按最小改动修复 **10 类**，包括鉴权错误分类、Key 审计归因、组织层级状态完整性、删除审计语义、资源不存在响应、连接测试有效期不一致、失败请求监控遗漏、Maven 编码、本地临时文件污染，以及管理列表分页与 N+1 查询。

仍有部分问题需要运行环境、产品语义或较大重构才能决定，本次未擅自修改，已在第 7 章列为遗留项。

---

## 2. 审查范围

本次主要审查以下代码和配置：

| 模块 | 主要目录 | 规模概况 | 主要职责 |
|---|---|---:|---|
| Console | `apps/console/src` | 34 个源文件，约 10,939 行 | 管理后台、租户工作台、开发者入口、模型/价格/Key/日志/用量页面 |
| Control Plane 主代码 | `services/control-plane/src/main` | 148 个源文件，约 15,939 行 | 资产、租户、IAM、价格治理、路由、账务、审计、Runtime 配置 |
| Control Plane 测试 | `services/control-plane/src/test` | 32 个测试文件，约 3,425 行 | 单元、契约、Flyway 与 PostgreSQL 集成测试 |
| Gateway Runtime | `services/gateway-runtime/app/main.py` | 约 2,500 行 | Virtual Key 鉴权、模型路由、预算限流、LiteLLM 调用、用量计费与 Outbox |
| Gateway 测试 | `services/gateway-runtime/tests` | 约 817 行 | 路由、计价、缓存 Token、DNS、预算、持久化规则 |
| Egress Proxy | `services/egress-proxy` | 约 626 行 | 供应商出口白名单、DNS/公网地址校验、CONNECT/HTTP 转发 |
| Headless Fetcher | `services/headless-fetcher` | 约 248 行 | 受控 Playwright 页面渲染和价格页面抓取 |
| 部署与脚本 | `deploy`、`scripts` | 约 1,927 行 | Compose、环境启动、验证、Virtual Key 调用和 E2E 脚本 |

未纳入代码逻辑审查的内容：`node_modules`、`target`、`dist`、日志、缓存、数据库实际业务数据和第三方镜像内部源码。

---

## 3. 架构与代码逻辑分析

### 3.1 总体架构

当前工程可分为五层：

```text
Console / 外部业务系统 / OpenAI SDK
              │
              ├── 管理请求 ──> Control Plane（Spring Boot）
              │                    ├── PostgreSQL / Flyway
              │                    ├── Redis
              │                    └── 价格、模型、租户、Key、路由、审计、账务
              │
              └── 模型请求 ──> Gateway Runtime（FastAPI）
                                   ├── Virtual Key / 权限 / 预算 / 限流
                                   ├── 路由 / Fallback / Usage / Cost / Outbox
                                   └── LiteLLM Runtime Core
                                             │
                                      Egress Proxy
                                             │
                                      模型供应商 API
```

价格页面抓取由 Control Plane 调用独立 Headless Fetcher；Headless Fetcher 在 Compose 中通过 Egress Proxy 访问外部官方页面。

### 3.2 管理面流程

#### 3.2.1 供应商与模型上线流程

```text
供应商模板
  → 供应商渠道
  → 托管/轮换供应商密钥
  → 连接测试
  → /models 模型发现
  → 模型候选和渠道部署
  → LIVE_PROBE 能力验证
  → 正式成本价格匹配
  → 人工生产准入
  → 路由策略校验并生效
  → 企业服务模型发布
```

关键约束已体现在代码中：

- 供应商目标必须在出口域名和端口白名单内；
- 连接测试不在数据库事务内执行，避免网络 I/O 长时间占用事务；
- 渠道启用、部署生产准入、路由生效和企业服务模型发布分别执行独立校验；
- 生产路由候选必须满足渠道状态、连接验证、模型发现、健康探测、成本价格和人工审核条件；
- 企业服务模型发布时检查路由归属、路由状态、映射、可见范围和候选部署。

#### 3.2.2 价格治理流程

```text
价格源
  → HTTP / 静态 HTML / Headless 获取
  → 原始快照和校验和
  → 供应商适配器解析
  → 标准化价格记录
  → 价格差异
  → 人工批准 / 拒绝 / 撤销
  → 官方价格目录
  → Price Version
  → 部署有效成本价格
```

代码支持供应商官方价格、渠道实际价格、合同价格和内部核算价格，并按：

```text
CONTRACT_PRICE > CHANNEL_ACTUAL > PROVIDER_OFFICIAL
```

选择生产成本价格。价格版本包含计费对象、计费基数、输入/输出、缓存读写、组件数组、来源证据和完整性状态；外币预算通过月度汇率折算为 CNY。

### 3.3 租户、项目、应用和 Key 流程

```text
租户授权企业服务模型
  → 创建项目
  → 创建应用
  → 创建 Virtual Key
  → 校验 Key 归属层级
  → 校验 Key 模型范围是租户授权子集
  → 生成一次性明文 Key
  → Gateway 使用 SHA-256 Hash 鉴权
```

Gateway 实际执行的模型权限不是只看 Key，而是：

```text
有效模型范围 = Key 允许模型 ∩ 租户当前授权模型
```

因此租户缩小模型授权后，无需重新生成 Key 即可立即撤销访问。

### 3.4 模型请求流程

Gateway Runtime 的主要调用链如下：

1. 解析 Bearer Virtual Key 并计算 Key Hash；
2. 校验 Key、租户、项目和应用状态及归属关系；
3. 校验 IP 白名单和模型权限交集；
4. 加载已发布企业服务模型和已生效路由；
5. 校验租户可见范围；
6. 加载可生产调用的渠道部署、托管密钥和有效价格；
7. 检查连接测试时效和 DNS/端口；
8. 执行 Key 与供应商 RPM/TPM/QPS 限流；
9. 基于成本价格和月度汇率预占预算；
10. 在 LiteLLM Runtime Core 中按需注册模型；
11. 调用上游，执行重试和 Fallback；
12. 规范化不同供应商 Usage；
13. 计算成本、销售额、缓存节省和非 Token 计费组件；
14. 写入 request attempt、usage record、成本快照和 Outbox；
15. 结算或释放预算，并输出 Prometheus 指标。

### 3.5 安全与出站网络流程

- Control Plane 和 Runtime Core 不直接任意访问互联网；
- Egress Proxy 使用精确主机白名单、端口白名单和公网地址校验；
- Provider Connection Test 保存验证主机、端口和解析结果；
- Gateway 调用时再次验证域名和端口，并拒绝私网、回环、链路本地和保留地址；
- Headless Fetcher 校验官方域名、端口和初始/最终 URL，并在浏览器层拦截非白名单主机；
- 供应商密钥以密文保存，实体字段 `secretCipher` 使用 `@JsonIgnore`，业务侧只看到末四位和状态。

---

## 4. 已发现并修复的问题

### 4.1 修复清单

| 编号 | 级别 | 问题 | 解决方案 | 处理结果 |
|---|---|---|---|---|
| TS-CR-001 | 中 | Maven 未指定源码和报告编码，Windows 下出现 GBK 警告，中文源码/测试输出存在环境差异 | 在 `pom.xml` 明确 `UTF-8` 编码 | **已修复**；JDK 21 编译通过 |
| TS-CR-002 | 高 | `JwtAuthFilter` 捕获所有异常并返回 401；数据库故障也会被误报为“登录失效” | 只捕获 JWT 解析异常；用户不存在返回 401；数据库异常继续上抛 | **已修复**；新增数据库故障回归测试 |
| TS-CR-003 | 中 | API Key 创建未记录 `createdBy`；人工批准未记录 `approvedBy` | 从当前 `Authentication` 写入创建人、批准人和批准时间 | **已修复**；新增审计归因断言 |
| TS-CR-004 | 高 | 新建项目/应用默认直接为 ACTIVE，但未校验上级租户或项目是否启用 | 创建 ACTIVE 项目时要求租户 ACTIVE；创建 ACTIVE 应用时要求租户和项目均 ACTIVE；ACTIVE 记录变更归属时执行同类校验 | **已修复**；新增父级停用场景测试 |
| TS-CR-005 | 中 | 通用只读详情接口在记录不存在时返回 `200 + null`，与其他接口的 404 语义不一致 | `ReadOnlyController.get()` 对空记录返回 404 | **已修复**；新增回归测试 |
| TS-CR-006 | 中 | 通用 CRUD 删除审计把删除前对象同时写入 before/after，无法表达“删除后不存在” | 删除审计保留 before，after 写 null | **已修复**；新增审计快照测试 |
| TS-CR-007 | 高 | 供应商渠道启用硬编码要求 30 分钟内连接测试；其他发布/调用链使用可配置的 7 天，导致同一渠道判断不一致 | 统一读取 `tokensea.provider.connection-test-valid-minutes`，默认 10080 分钟 | **已修复**；新增配置有效期测试 |
| TS-CR-008 | 中 | Gateway 在部分流式前置失败和统一异常路径中统计失败请求，但遗漏失败时延；部分流式 JSON 失败未记录请求数/时延 | 在确定的失败返回路径补充 `REQUESTS` 和 `LATENCY` | **已修复**；Gateway 全量规则测试通过 |
| TS-CR-009 | 低 | 根目录 `.pytest_cache`、日志、Windows 特殊文件和临时探测文件会污染工作区，`NUL` 还会干扰部分搜索工具 | 仅补充 `.gitignore`，不删除用户现有文件 | **已修复配置**；已有本地文件仍保留 |
| TS-CR-R07 | 中 | 租户、Virtual Key、账户、角色、审计和价格差异列表缺少统一服务端分页；Key、账户和角色列表存在逐条关联查询 | 统一 `{items,total,page,size}` 分页契约，增加安全排序白名单、服务端筛选和稳定排序；关联信息改为批量查询 | **已修复**；新增 7 项分页与批量查询回归测试 |

### 4.2 代码修改文件

本次直接修改或新增的文件如下：

```text
.gitignore
services/control-plane/pom.xml
services/control-plane/src/main/java/com/tokensea/security/JwtAuthFilter.java
services/control-plane/src/main/java/com/tokensea/apikey/controller/ApiKeyController.java
services/control-plane/src/main/java/com/tokensea/project/controller/ProjectController.java
services/control-plane/src/main/java/com/tokensea/app/controller/AppController.java
services/control-plane/src/main/java/com/tokensea/asset/controller/ProviderInstanceController.java
services/control-plane/src/main/java/com/tokensea/common/ReadOnlyController.java
services/control-plane/src/main/java/com/tokensea/common/BaseCrudController.java
services/control-plane/src/main/java/com/tokensea/common/PageQuery.java
services/control-plane/src/main/java/com/tokensea/common/PageResult.java
services/control-plane/src/main/java/com/tokensea/tenant/controller/TenantController.java
services/control-plane/src/main/java/com/tokensea/access/AccessControlController.java
services/control-plane/src/main/java/com/tokensea/audit/controller/AuditLogController.java
services/control-plane/src/main/java/com/tokensea/governance/ProviderPriceSyncController.java
apps/console/src/pages/AccessControl.vue
scripts/dev/run-flash-e2e.py
docs/api-reference.md
services/gateway-runtime/app/main.py
services/control-plane/src/test/java/com/tokensea/security/JwtAuthFilterTests.java
services/control-plane/src/test/java/com/tokensea/AdminFlowSimplificationTests.java
services/control-plane/src/test/java/com/tokensea/organization/ActiveHierarchyControllerTests.java
services/control-plane/src/test/java/com/tokensea/common/CommonControllerBehaviorTests.java
services/control-plane/src/test/java/com/tokensea/asset/controller/ProviderInstanceControllerTests.java
services/control-plane/src/test/java/com/tokensea/common/ManagementPaginationTests.java
```

本次没有新增页面、菜单、数据库表或 Flyway 迁移；既有六类管理列表接口的 GET 响应调整为统一分页对象。

---

## 5. 重点修复说明

### 5.1 JWT 过滤器错误分类

#### 原逻辑

`JwtAuthFilter` 把以下操作全部放在一个大 `try/catch` 中：

- JWT 解析；
- 查询用户；
- 查询角色；
- 查询租户授权；
- 建立 SecurityContext。

任何异常都被转换为 401。这会造成 PostgreSQL 连接失败、SQL 执行失败等服务端故障被客户端理解为 Token 过期，前端可能清理正常会话，运维也无法根据 HTTP 状态快速区分认证问题和基础设施问题。

#### 修改后

- JWT 签名、格式、过期异常：401；
- 用户不存在或停用：401；
- 数据库或程序异常：不吞掉，由统一异常链路按服务端故障处理。

该修改不改变正常登录和权限规则，只修正错误分类。

### 5.2 组织层级状态完整性

数据库迁移 V20 已经约束项目、应用和 Key 的租户/项目归属，但没有阻止“ACTIVE 子资源挂在未启用父资源下”。原控制器创建项目和应用时直接设置 ACTIVE，因此可能生成：

```text
DRAFT/SUSPENDED 租户
  └── ACTIVE 项目
        └── ACTIVE 应用
```

Gateway 调用时最终会拒绝这些资源，但管理面仍展示 ACTIVE，形成状态语义冲突。本次在控制器入口增加父级状态校验，没有改变表结构。

### 5.3 供应商连接测试有效期一致性

此前存在三套语义：

- 供应商渠道“启用”：硬编码 30 分钟；
- 企业服务模型发布：配置项，默认 10080 分钟；
- Gateway 实际调用：环境变量，默认 604800 秒。

这会导致连接测试在发布和调用时仍有效，但渠道启用页面已经拒绝。现在控制面渠道启用和企业服务模型发布共用同一配置，Compose 默认值与 Gateway 的 7 天一致。

### 5.4 删除审计语义

删除事件应表示：

```json
{
  "before": { "原对象": "..." },
  "after": null
}
```

原通用控制器写成 before 和 after 完全相同，容易在审计回放中被误判为一次无变化更新。本次仅修正通用 CRUD 删除事件，未改写历史记录。

### 5.5 管理列表分页与关联批量加载

统一分页对象位于 `ApiResponse.data`：

```json
{
  "items": [],
  "total": 0,
  "page": 1,
  "size": 20
}
```

处理范围包括租户、Virtual Key、账户、角色、操作审计和价格差异审核。`page` 从 1 开始，默认每页 20 条，单次最多 500 条；排序字段必须命中各接口白名单，排序方向仅接受 `asc` 或 `desc`，并追加唯一 ID 作为稳定排序条件。Console 通用 `queryPage()` 原本已兼容分页对象，账户/角色自定义页面改为真实服务端查询、筛选和翻页；表单下拉仍可显式请求最多 500 条，保持现有选择器语义。

关联加载由逐条查询改为批量查询：

- 账户列表：当前页主查询后，一次批量加载角色、一次批量加载租户；
- 角色列表：当前页主查询后，一次批量加载权限，关联账户数在主查询聚合；
- Key 列表：当前页主查询后，分别批量加载去重后的租户、项目和应用；
- 租户、审计和价格差异：采用“总数查询 + 当前页查询”，不再传输完整列表。

现有 E2E 脚本同步读取分页 `items`，避免接口契约调整后把分页对象误当数组。没有新增数据库索引或迁移，也没有改变单条详情、创建、审批和导出语义。

---

## 6. 测试与验证结果

| 验证项 | 结果 | 说明 |
|---|---|---|
| Control Plane 定向回归 | **通过** | 30 项测试通过：JWT、Key、层级状态、通用控制器、渠道连接有效期、管理分页与关联批量加载；0 failure、0 error、0 skipped |
| Control Plane JDK 21 编译 | **通过** | `mvn -q -DskipTests compile` |
| Control Plane 全量测试 | **受环境阻塞** | 共发现 108 项：0 failure、2 error、17 skipped；2 个错误均为本地 PostgreSQL 用户 `tokensea` 密码认证失败，非断言失败 |
| Control Plane 打包 | **受运行文件锁阻塞** | 编译完成后 Spring Boot repackage 无法重命名正在被占用的 JAR；未停止当前运行服务 |
| Gateway Runtime | **通过** | 37 passed，另有 14 个 subtests passed |
| Egress Proxy | **通过** | 24 passed |
| Headless Fetcher 语法检查 | **通过** | `python -m compileall -q app` |
| Headless Fetcher pytest | **未执行完成** | 当前本机 Python 环境缺少 `playwright`，测试在收集阶段停止；依赖已写在该服务 `requirements.txt` 中 |
| Console 类型检查和生产构建 | **通过** | `vue-tsc --noEmit`、Vite 构建通过 |
| Console 构建体积 | **警告** | 主 JS 约 1.82 MB，gzip 约 571 KB；Vite 提示超过 500 KB |
| Docker Compose 配置解析 | **通过** | 使用 `deploy/compose/.env.example` 执行 `docker compose config --quiet` |
| Git 差异格式检查 | **通过** | 无空白错误；仅存在 Windows CRLF 提示 |

本次未执行 Docker 重启、数据库迁移、真实供应商 API 调用或现网数据变更。

---

## 7. 遗留问题与不修改原因

以下问题已经确认或具有较高风险，但因产品语义、环境条件或改动范围无法在本次最小修复中可靠决定。

### TS-CR-R01：Control Plane 全量集成测试依赖本地数据库凭据

- **现象**：`TokenseaApplicationTests` 加载 Spring Context 时，PostgreSQL 返回 `password authentication failed for user "tokensea"`。
- **影响**：本次不能声明完整 Spring Context 与 Flyway 全量测试通过。
- **本次处理**：保留；已完成定向测试和 JDK 21 编译。
- **后续条件**：提供当前测试数据库正确凭据，或为测试建立隔离 PostgreSQL/Testcontainers 环境。

### TS-CR-R02：Headless Fetcher 本地测试环境缺少 Playwright

- **现象**：pytest 收集时无法导入 `playwright.async_api`。
- **影响**：Headless 安全测试未在本机执行。
- **本次处理**：保留；Python 语法检查通过。
- **后续条件**：在该服务虚拟环境安装 `requirements.txt` 并安装 Chromium 后执行测试。

### TS-CR-R03：Headless 单独运行时的网络安全依赖出口代理

- **现状**：初始和最终 URL 会进行公网 DNS 校验，浏览器 route guard 会限制主机；Compose 中所有外部请求还会经过 Egress Proxy，再次执行 DNS 和公网地址校验。
- **风险**：如果脱离 Compose 且未配置 Headless Proxy，子资源请求只按域名判断，未逐请求固定已验证 IP。
- **不修改原因**：强制代理、逐请求 DNS 检查和浏览器请求 IP 固定方案会影响部署与页面兼容性，需要明确 Headless 是否允许独立部署。

### TS-CR-R04：路由候选中的显式 `priceVersionId` 与运行时有效价格选择语义不完全一致

- **现状**：路由配置可携带 `priceVersionId`，但 Gateway `load_price(_price_id, ...)` 实际按部署和价格层级动态选当前 ACTIVE 价格，参数未直接限定查询。
- **可能解释**：这是为了价格版本切换后路由自动使用当前有效价格，也可能违背“路由固定价格版本”的预期。
- **不修改原因**：两种语义均合理，需产品确认“动态有效价”还是“发布时锁价”。

### TS-CR-R05：调用尝试持久化最终失败时，主请求仍可能继续

- **现状**：`safe_record_attempt()` 先写数据库，失败后写 Outbox；两者均失败时返回 `False`。多数调用方未检查返回值。
- **风险**：极端情况下最终 usage 可存在，但某次 attempt 明细丢失，影响完整调用链审计。
- **不修改原因**：需要决定系统采用“审计失败即阻断请求”的 fail-closed，还是“优先保证模型可用性”的 fail-open。

### TS-CR-R06：审计写入方式不完全统一

- **现状**：新代码多使用 `AuditService`，能记录操作人、IP 和 User-Agent；部分旧控制器直接构造 `AuditLog`，只记录操作和对象快照。
- **影响**：审计页面可展示记录，但部分历史/新增事件缺少完整操作者上下文。
- **不修改原因**：统一改造涉及多个控制器构造函数、测试夹具和事务边界，不适合在本次最小修复中批量替换。

### TS-CR-R08：Gateway Runtime 单文件过大

- **现状**：`services/gateway-runtime/app/main.py` 约 2,500 行，包含鉴权、路由、DNS、预算、限流、Runtime 注册、计价、持久化和 Outbox。
- **影响**：模块耦合高，后续修改容易产生跨域回归。
- **不修改原因**：拆分属于结构重构，无法保证是“最小改动”，本次只修改确定的监控遗漏。

### TS-CR-R09：Console 主包体积偏大

- **现象**：生产构建主 JS 约 1.82 MB，gzip 约 571 KB。
- **影响**：首次加载和弱网体验可能下降。
- **不修改原因**：路由懒加载、Ant Design 按需加载或 manualChunks 会改变构建和页面加载策略，不属于本次缺陷修复。

### TS-CR-R10：本地 JAR 文件被运行进程占用

- **现象**：Spring Boot `repackage` 无法把 JAR 重命名为 `.original`。
- **影响**：本次只能确认编译和定向测试，不能确认本机打包动作完成。
- **不修改原因**：按要求未停止或重启正在运行的 Control Plane。

### TS-CR-R11：工作区存在大量既有未提交修改和本地文件

- **现状**：本次开始前工作区已包含大量修改、新增文件、测试结果和运行产物。
- **本次处理**：没有 reset、checkout、删除或覆盖既有工作；仅增加 ignore 规则阻止后续继续污染。
- **后续建议**：在确认现有成果后分批提交，避免后续审查无法准确区分变更来源。

---

## 8. 未发现需要立即修改的关键点

经本次代码审查，以下关键安全与一致性措施已经存在，因此没有重复开发：

- 供应商密钥密文不通过 JSON 返回；
- Virtual Key 明文只在生成时返回一次，数据库保存 Hash；
- Key 模型范围必须是租户模型授权的子集；
- Gateway 调用时重新计算租户与 Key 权限交集；
- 供应商出站采用域名/端口白名单和公网地址校验；
- Runtime Core 内部调用明确 `trust_env=False`，避免继承本机 SOCKS 代理；
- 价格、Usage、成本和汇率保留来源及快照；
- 流式请求有 request intent、WAL/Outbox 和中断恢复逻辑；
- 生产路由要求真实能力验证、正式价格和人工准入；
- 供应商价格发布支持批准、拒绝和撤销。

---

## 9. 后续处理优先级建议

在不新增业务功能的前提下，建议后续按以下顺序继续处理遗留项：

1. **先恢复可重复测试环境**：统一 JDK 21、PostgreSQL 测试凭据和 Headless Playwright 环境；
2. **确认两项产品语义**：路由是否锁定价格版本、attempt 持久化失败是否阻断模型请求；
3. **统一审计上下文**：逐个将直接写 `AuditLog` 的控制器迁移到 `AuditService`；
4. **评估超大选项集交互**：当前表单下拉单次最多读取 500 条，数据超过该规模后应采用远程搜索，避免把管理分页重新退化为全量选项加载；
5. **最后进行结构优化**：拆分 Gateway 大文件和 Console 代码分包。

---

## 10. 最终处理结果

- **新增功能**：无；
- **数据库结构变更**：无；
- **业务数据变更**：无；
- **Docker 服务操作**：无；
- **确定性问题修复**：10 类；
- **新增/补充回归测试**：JWT、Key 归因、组织层级、通用控制器、渠道有效期、管理分页与批量关联查询；
- **已通过验证**：Control Plane 定向测试与编译、Gateway、Egress、Console、Compose 配置；
- **未完整验证**：Control Plane 全量 Spring Context、Headless pytest、Spring Boot repackage；原因均已记录；
- **保留待决问题**：价格版本语义、attempt 持久化策略、Headless 独立部署安全边界、审计重构和超大选项集远程搜索。
