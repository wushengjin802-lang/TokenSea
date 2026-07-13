# TokenSea MVP 版本修订与交付记录

- 文档日期：2026-07-13
- 依据：`TokenSea_PDD_V1.4_20260710.md` 与 `TokenSea_Product_Prototype_V1.9_PDD_V1.4`
- 交付范围：TokenSea 控制面、Gateway Runtime、出口代理、Console、Compose 部署契约及 V5–V12 数据迁移
- 说明：本记录只陈述实际完成和实际执行的检查；未执行或受环境限制的项目单列说明。

## 1. PDD 范围与交付结论

本轮完成了模型供应商与渠道、公共模型参考、企业服务模型、真实模型发现与差异审核、主动能力探测、价格版本、路由、Virtual Key、租户/项目/应用、预算、实际成本、供应商对账、调用追踪、告警、审批与版本回滚、审计、开发者快速开始等核心闭环。

交付实现遵循以下原则：

- 页面业务选项使用中文展示，下拉选项由受控枚举或真实后端数据源提供。
- 业务页面不展示“MVP”等开发阶段文字。
- 运行数据来自数据库、供应商接口、Gateway 或受控导入，不以静态演示数据冒充真实状态。
- 高风险发布、激活、预算生效和回滚进入审批与审计链路。
- Gateway 只使用通过主动探测、审核和价格/预算治理的可路由配置。

## 2. 版本修订摘要

### 控制面与数据治理

- 增加真实供应商连接验证、DNS/出口目标约束、模型目录发现、原始快照、差异审核和字段来源。
- 增加服务端 CHAT、STREAM、EMBEDDING 主动能力探测；客户端不能自行提交通过结果。
- 企业服务模型、路由策略、价格版本和预算规则增加提交、审批、激活、退役、版本与回滚链路。
- 价格严格区分公共参考价、渠道实际成本和内部核算价，并校验层级、归属对象、来源类型与依据。
- 数据源同步支持 Provider API、HTTPS 白名单公共参考源和最大 2 MB/5000 条的受控 JSON/CSV 导入；定时任务具备抢占、心跳、日志和失败隔离。
- 增加真实错误码注册表、系统设置持久化与脱敏、敏感访问记录、快照安全查看和动态快速开始配置。

### Gateway、成本与预算

- 路由候选要求部署已审核、可路由且存在成功的 `LIVE_PROBE`。
- Gateway 优先读取已生效的渠道实际价格版本，并记录逐请求成本快照和多次调用尝试。
- 预算规则按租户、项目、应用和 Virtual Key 执行；仅执行 `ACTIVE + APPROVED` 规则，支持阻断、告警和降级。
- 增加成本单实体与多维明细、生成/确认/调整/导出状态机；生成时识别缺失价格、对账偏差、预算事件和失败尝试并标记异常。
- 供应商对账按真实 usage/cost 与账单输入拆分 Token、价格、汇率和税费差异，支持确认、解决、审计和导出。

### 安全与审计

- Provider 请求强制经独立出口代理、主机/端口 allowlist、DNS 快照及响应大小限制。
- 密钥使用托管密文，不通过运行时配置或 API 返回明文。
- `AuditService` 自动记录 actor ID、actor name、来源 IP 和 User-Agent。
- 原始模型快照查看必须提供理由，敏感字段受控脱敏并写入敏感访问日志。
- Compose 中 PostgreSQL、Redis、控制面、Gateway、Runtime Core、Console、Prometheus 和 Grafana 均限制为 `127.0.0.1` 发布端口。
- Redis 配置密码环境变量及 `requirepass`；Gateway outbox 使用持久卷；内部网络标记为 `internal: true`，出口代理单独连接 provider-egress 网络。

### Console/UI

- 按 PDD 重组模型资产、同步中心、访问治理、租户体系、成本预算、路由观测、审计设置和开发者中心导航。
- 增加公共模型参考、模型部署、能力验证、价格版本、预算、审批、版本回滚、调用详情、成本单、开发者模型、快速开始和租户工作台页面。
- 表单中的状态、类型、币种、动作、来源等使用中文下拉选项；租户、项目、应用、渠道、模型等选项读取真实 API。
- 移除不可用的静态 API 文档/监控占位页面及旧样式入口，统一真实空态、错误态和加载态。

## 3. 数据迁移与冻结

V5–V12 是已验证的前向迁移链。交付后不得修改已发布迁移；后续数据库变化必须新增 V13 或更高版本。以下 SHA-256 用于冻结核验：

| 迁移 | SHA-256 |
|---|---|
| V5 `mvp_runtime_security_compatibility` | `908d6acd01835f0077c1fb03e78708aca0e6a0a7cd96db9fa2b94302208b977f` |
| V6 `runtime_routing_accounting` | `a3f45b20cf97b0d354fc74fbb4a7f4320d0d2b78685c21ab2a1e950c3fd48cde` |
| V7 `control_plane_contracts` | `e68746c814e6269f4fa2629c1bbcc05fcbce9ab227ccf9051d4a4ffbf80fa612` |
| V8 `upgrade_recovery_and_accounting` | `320a17a0ec4f087b6bda95a9185e567d31cbecda89dd0df60bf385e864fb050e` |
| V9 `tenant_scope_and_egress_contract` | `f6e4ee549d7fdf9b6177aaa957767926474174bb3ee65a7db815c16f1d6e4cbb` |
| V10 `governance_discovery_and_cost_contracts` | `f336d415a6ce2d9e706c42ae0701cc3f9e4235a1c40aee951135565c99daede0` |
| V11 `runtime_governance_execution` | `70af1abe8054f4a44043a863b940eade7c2b7aa23d40490691fd300aeca9a78c` |
| V12 `pdd_delivery_closure` | `1023631bbd417e2911eb5a40ae45e57f682ef827f34cb923dd276ad0c7db262c` |

## 4. 实际通过的验证

### Java 与数据库

- JDK 21 `mvn -DskipTests package`：通过；84 个主源码和 5 个测试源码完成编译并生成可执行 JAR。
- JDK 21 全量 `mvn test`：19/19 通过，0 failure、0 error、0 skipped。
- `TokenseaApplicationTests`：2/2 通过；测试使用合法 Base64 32-byte 密钥和不可外联的本地测试代理。
- Flyway clean install：V1–V12 全部执行并验证成功，最终版本 V12。
- Flyway dirty upgrade：从含历史脏数据的 V6 快照升级至 V12 成功，隔离和兼容断言通过。

### Python/Gateway

- Gateway Runtime pytest：23 个测试通过，另有 10 个子测试通过。
- Egress Proxy pytest：23 个测试通过。

### Console

- npm/Vite production build：通过，包含 TypeScript/Vue 类型检查和生产资源构建。

## 5. 静态安全与依赖检查

### 已执行并通过

- 对 Git tracked/staged 内容执行常见秘密模式扫描；排除真实 `deploy/compose/.env`、`LOCAL_RUN.md` 和 gitignored 文件后，私钥、AWS Key、GitHub/OpenAI Token 和硬编码 secret 模式命中路径为 0。
- Maven runtime dependency inventory 成功生成：108 项依赖，108 项均存在本地 POM 元数据。
- `docker compose -p tokensea --env-file ./.env config --quiet` 成功解析；复审结果：
  - 所有发布端口均绑定 `127.0.0.1`。
  - `tokensea-internal` 为内部网络。
  - Gateway 只连接内部网络。
  - Egress Proxy 连接内部网络和独立 provider-egress 网络。
  - Gateway outbox 使用持久卷。
  - Redis 同时具备密码环境变量和 `requirepass`。

### 限制与未判定项

- `npm audit --omit=dev` 已实际执行，但当前 `npmmirror.com` audit API 返回 `404 NOT_IMPLEMENTED`；因此没有 npm 漏洞“通过”结论。
- Maven POM 许可证元数据自动汇总中有 34 项未声明或无法识别；本次只生成清单，没有作法律/许可证兼容性结论。
- 常见秘密模式扫描不是完整的熵分析、历史提交扫描或外部 secret scanner，结论仅覆盖本次扫描规则和当前 tracked/staged 内容。

## 6. Docker 运行态与限制

- 本轮没有执行 Docker image rebuild。
- 曾尝试 Docker build/全栈验证，但构建过程无输出并挂起，已终止；不得将其记为构建通过。
- 未执行完整 Compose 全栈启动后的端到端验收，因此没有“完整 Docker 全栈运行通过”结论。
- 为 Flyway clean/dirty 测试启动过一次性 PostgreSQL 16 测试容器；测试完成后已按严格名称校验删除，未删除卷、未清理或修改现有 TokenSea 数据库。
- Compose 本轮结论属于静态解析与安全配置审计，不等同于生产网络、宿主防火墙或运行时渗透测试。

## 7. 交付边界

- 未修改、未暂存用户的 `LOCAL_RUN.md` 工作树变更。
- 真实 `.env`、构建产物 `dist/`、`target/`、`node_modules/`、`__pycache__/` 和运行时 outbox 数据不进入交付暂存。
- 本记录不包含任何真实密钥、密码或 Token。
## 2026-07-13 部署权限回归修订

- 现象：控制面 `GET /api/model-templates` 返回 `403 Forbidden`。
- 根因：已存在的 `admin` 账号未关联 `ADMIN` 角色，登录 JWT 的 `roles` 为空；重新部署后的 `/api/**` 管理接口按角色拒绝访问。浏览器旧令牌也会继续复用这一无角色声明。
- 修复：使用部署端一次性恢复令牌为 `admin` 补授 `ADMIN` 角色并验证登录 JWT 已包含 `ADMIN`；前端对无角色令牌收到 403 时清理会话并跳转登录，避免旧令牌循环失败。
- 验证：重新登录后 `GET http://localhost:39211/api/model-templates` 返回 `200`。
- 安全补强：JWT 验签失败现在返回 `401`，前端可自动清理失效令牌并重新登录；普通租户账号访问该管理端点仍保持 `403`。
