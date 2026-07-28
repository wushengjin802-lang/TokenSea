# TokenSea 项目 Agent 使用说明

本文件用于 ChatGPT Desktop / Codex / DevSpace 等代码协作场景。进入 TokenSea 项目后，优先遵守本文件要求；如果用户在对话中给出更具体要求，以用户当前要求为准。

---

## 1. 沟通与输出原则

- 默认使用中文沟通，除非用户明确要求英文，或代码、接口、错误信息本身需要英文。
- 先给结论，再给必要细节。
- 不要泛泛解释，不要重复总结。
- 修改代码后必须说明：修改了哪些文件、解决了什么问题、验证了什么、还有哪些风险未验证。
- 不要承诺已经完成未验证的事情；没有实际运行过的测试、构建、部署，不得说“已通过”。
- 遇到部署、端口、Docker、数据库、环境变量、权限、密钥等问题时，先说明影响和最小处理方案。

---

## 2. 项目定位

TokenSea 是企业内部统一 LLM API Gateway 平台，定位是：

```text
企业内部统一模型 API 出口
供应商与模型资产治理
Virtual Key 管理
成本预算与对账
路由、Fallback、观测、审计和运维治理
```

核心架构：

```text
LiteLLM 数据面 + TokenSea 自研控制面 + 成本治理面 + 安全治理面 + 元数据同步面
```

重要产品原则：

- TokenSea 不是公网 API 转售平台。
- TokenSea 不以模型 API 差价盈利为核心定位。
- 业务用户只使用 TokenSea Virtual Key，不直接接触供应商原始 Key。
- 业务用户调用企业服务模型，不直接依赖供应商真实模型名。
- 公共模型参考库只作参考，不自动覆盖企业真实生产配置。
- 成本、用量、请求记录、价格版本和审计记录必须可追踪、可回放、可对账。

---

## 3. 主要目录

```text
apps/console                 前端控制台，Vue 3 + TypeScript + Vite + Ant Design Vue
services/control-plane        控制面后端，Java / Spring Boot / PostgreSQL / Redis
services/gateway-runtime      TokenSea Gateway Runtime，Python / FastAPI
services/egress-proxy         出口代理，限制供应商访问目标
deploy/compose                Docker Compose 本地与私有化部署配置
docs                          项目文档、评估报告、流程说明
configs                       Provider、Gateway、模型目录等配置
compliance                    开源合规、许可证、SBOM 相关
security                      安全、密钥、漏洞响应相关
```

---

## 4. 修改范围控制

- 只修改与当前任务直接相关的文件。
- 不要顺手重构无关代码。
- 不要全仓格式化。
- 不要主动升级依赖。
- 不要删除、覆盖或回滚用户已有改动，除非用户明确要求。
- 不要创建 commit、push、开 PR，除非用户明确要求。
- 不要读取、打印、提交或泄露 `.env`、token、secret、private key、API key、数据库密码等敏感信息。
- 如果需要新增依赖、改数据库 schema、改部署配置、改环境变量，先说明原因、影响和回滚方案。
- 每次修改后，必须填写修改记录，./docs/record/TOKENSEA_MVP_REVISION.md
---

## 5. Docker 与部署规则

### 5.1 前端样式或页面展示修改

如果用户只是要求：

```text
修改前端样式
调整页面布局
修改按钮、表格、卡片、颜色、间距、字体
修改文案
修改菜单展示
修改前端交互但不涉及后端接口
```

不要主动执行 Docker 部署，不要执行：

```bash
docker compose up -d --build
docker compose down
docker compose down -v
```

完成后只提示：

```text
本次只修改前端代码，没有部署 Docker。请你按当前项目部署方式自行重新构建/部署前端或全栈服务。
```

可以运行前端构建验证：

```bash
npm run console:build
```

但不要因此启动或重建 Docker 容器。

### 5.2 后端、数据库、Gateway、Compose 修改

如果任务涉及：

```text
后端接口
数据库迁移
Gateway Runtime
路由、Key、预算、成本
docker-compose.yml
.env.example
Redis/PostgreSQL
LiteLLM Runtime Core
```

可以建议部署验证，但在没有用户明确要求前，不要主动执行 Docker 启停或重建。

如用户要求部署，优先提醒：

```text
请确认是否允许我停止/重建本地 TokenSea 容器。该操作不会删除数据，除非使用 down -v。
```

严禁未经确认执行：

```bash
docker compose down -v
```

因为 `-v` 会删除数据库和 Redis volume。

### 5.3 Compose 项目名

TokenSea 本地 Compose 建议固定项目名：

```bash
cd deploy/compose
docker compose -p tokensea --env-file ./.env up -d --build
```

不要混用不同项目名，避免同时出现：

```text
tokensea-tokensea-redis-1
compose-tokensea-redis-1
```

导致端口冲突。

---

## 6. 前端 UI 与交互规范

### 6.1 整体风格

- TokenSea 是统一企业后台平台，不是多套割裂系统。
- 平台管理员工作台、租户工作台、开发者门户应共用一套视觉语言和组件体系。
- 不要把平台管理员工作台和租户工作台做成完全不同风格的两个平台。
- 页面风格应保持企业级、克制、高密度、清晰、可运维。
- 优先使用现有组件、样式变量和布局结构。

### 6.2 页面角色差异

同一平台内根据角色展示不同首页、菜单、数据范围和操作权限：

```text
平台管理员：全局治理视图
租户管理员：租户运营视图
开发者：接入调试视图
成本人员：成本治理视图
安全审计员：审计视图
运维人员：运行视图
```

差异应体现在：

```text
可见菜单
首页指标
数据范围
可执行操作
字段权限
敏感信息展示方式
```

不要通过完全不同样式来体现角色差异。

### 6.3 表单字段规范

凡是状态、类型、环境、来源、能力、角色、优先级、币种、策略、动作等枚举字段，尽量使用下拉框、单选框或多选框，不要让用户自由输入。

例如：

```text
状态：草稿、待审核、启用、暂停、停用、退役
环境：开发、测试、生产、沙箱
区域：中国、日本、美国、欧洲、其他
来源类型：供应商接口、公共参考来源、受控文件导入、合同、供应商账单、人工确认
价格层级：公共参考价、渠道实际成本、内部核算成本
预算动作：仅告警、阻断、降级
审批状态：草稿、待审批、已批准、已拒绝、已执行
健康状态：健康、降级、不可用、观察中
Key 状态：草稿、待审批、启用、暂停、禁用、已过期
角色：平台管理员、租户管理员、应用负责人、开发者、成本管理员、安全审计员、运维人员
```

枚举值展示必须使用中文。后端存储值可以是英文 code，但前端展示、下拉选项和用户可见文案必须是中文。

### 6.4 状态显示规范

状态字段不要只用颜色表达，必须包含文字。

推荐：

```text
文字 + 图标/标签 + 颜色
```

例如：

```text
启用 / 暂停 / 停用
待审核 / 已批准 / 已拒绝
健康 / 降级 / 不可用
已发布 / 已下架 / 已退役
```

### 6.5 暂不可用入口

不要在正式菜单中随意保留“暂不可用，入口已保留”的半成品入口。

处理原则：

- 不属于 MVP 的功能，优先隐藏入口。
- 如果必须展示规划能力，文案应明确为“后续版本开放”，并说明当前替代路径。
- 不要让用户误以为系统故障。

例如组织 / 部门：

```text
后续版本开放，用于租户内部组织架构、成员归属、预算分摊和审批流配置。
当前版本请通过“租户 / 项目 / 应用”完成成本归因和权限管理。
```

成员 / 角色比组织 / 部门更重要，MVP 至少应具备基础成员管理和固定角色分配。自定义角色权限矩阵可以后置。

---

## 7. 产品功能优先级判断

### 7.1 MVP 必须优先保证

```text
统一 API 调用
供应商模板
供应商渠道
供应商 Key 托管
连接测试
模型发现
模型部署审核
企业服务模型
路由策略
Virtual Key
租户 / 项目 / 应用
基础成员管理
固定角色分配
用量记录
request attempt
成本快照
预算预警 / 阻断
调用日志
操作审计
开发者快速开始
Playground
Docker Compose 私有化部署
```

### 7.2 可放到 V1 企业增强版

```text
组织 / 部门树
成本中心
自定义角色权限矩阵
字段级权限配置页面
SSO
供应商合同附件管理
供应商发票导入
自动对账增强
内容安全策略
数据保留策略
SLA 报表
```

### 7.3 可放到 V2 增强版

```text
智能语义路由
模型质量评测平台
成本预测
异常检测
私有模型 GPU 成本自动核算
复杂 Agent 平台能力
```

---

## 8. 后端与数据规则

- 数据库迁移使用 Flyway。已经发布或执行过的迁移文件不要修改，应新增更高版本迁移。
- 不要直接修改历史 V1、V2、V3 等迁移文件来修生产问题。
- usage_record、request_attempt、cost_snapshot 等事实记录应保持不可变；差异通过调整记录处理。
- 价格版本不应原地修改，调价应创建新版本。
- 公共参考价格不能自动覆盖渠道实际成本。
- 供应商原始 Key 不得明文返回给前端。
- Prompt/Response 默认不落全文，除非租户策略明确允许并完成权限控制。
- 所有高风险操作应写审计：Key、模型、价格、预算、路由、敏感查看、供应商密钥。

---

## 9. Gateway 与调用链路规则

业务调用主链路应保持：

```text
业务系统
→ TokenSea Gateway
→ 校验 Virtual Key
→ 解析租户 / 项目 / 应用
→ 校验模型范围
→ 校验预算和限流
→ 服务模型解析
→ 路由到实际模型部署
→ Runtime Core / LiteLLM 调用供应商
→ 记录 attempt、usage、cost snapshot
→ 返回响应
```

不要让业务系统直接使用供应商 Key。

不要让业务系统直接依赖供应商真实模型名，推荐使用企业服务模型名，例如：

```text
chat-standard
chat-pro
reasoning-pro
embedding-standard
code-fast
```

如用户要求用 `deepseek-v4-pro` 作为服务模型名，可以支持，但建议说明使用稳定业务别名更利于后续替换底层模型。

---

## 10. 验证要求

根据改动范围选择最小验证。

### 10.1 前端修改

优先运行：

```bash
npm run console:build
```

如只是文案或样式调整，也可以只做静态检查，但必须说明没有部署。

### 10.2 Java 控制面修改

优先运行：

```bash
cd services/control-plane
mvn test
```

注意：控制面需要 JDK 21。若 Maven 使用 JDK 17，会导致测试失败。不要把 JDK 版本问题说成代码失败。

### 10.3 Gateway Runtime 修改

优先运行：

```bash
cd services/gateway-runtime
python -m pytest -q
```

### 10.4 Egress Proxy 修改

优先运行：

```bash
cd services/egress-proxy
python -m pytest -q
```

### 10.5 Compose 配置修改

优先运行配置解析：

```bash
cd deploy/compose
docker compose -p tokensea --env-file ./.env.example config --quiet
```

不要因为配置解析通过就声称全栈运行通过。只有实际 `up` 并完成端到端调用，才可说 Docker 全栈验证通过。

---

## 11. 文档输出规范

- 项目文档放到 `docs/` 下。
- 评估报告可放到 `docs/assessment/`。
- 流程说明可放到 `docs/` 或 `docs/process/`。
- 开发记录可放到 `docs/record/`。
- 文档标题、版本、日期、依据、结论要清晰。
- 不要写“已完成全部 MVP”这类未经验证的结论。
- 功能完成度文档应区分：已完成、部分完成、未完成、未验证。

---

## 12. Git 与交付规则

- 修改前可查看 `git status --short`，识别已有用户改动。
- 不要主动提交 commit。
- 不要主动 push。
- 不要执行 `git reset --hard`、`git clean -fd`、rebase、force push 等破坏性操作。
- 如果需要交付补丁，说明文件路径和修改点即可。
- 不要把以下内容提交：

```text
node_modules/
dist/
target/
__pycache__/
.pytest_cache/
.env
真实密钥
真实供应商 API Key
数据库 dump
运行时 outbox 数据
```

---

## 13. 常见判断口径

### 13.1 “流程是否打通”

不能仅因为代码存在就说流程已经打通。

只有完成以下端到端验证，才可认定打通：

```text
配置供应商渠道
→ 托管供应商 Key
→ 连接测试成功
→ 发现模型
→ 生成模型部署
→ 能力探测通过
→ 审核模型部署
→ 配置价格版本并激活
→ 创建企业服务模型
→ 配置路由策略
→ 创建租户 / 项目 / 应用
→ 创建 Virtual Key
→ 用 OpenAI SDK 调用 TokenSea Gateway
→ Gateway 成功调用上游模型
→ 写入 usage_record
→ 写入 request_attempt
→ 写入 cost snapshot
→ 成本与预算统计可见
```

### 13.2 “完成度”

完成度应分为：

```text
页面入口完成
数据表完成
后端接口完成
业务规则完成
前后端联调完成
端到端验证完成
生产可用完成
```

不要把“页面入口完成”当成“功能完成”。

---

## 14. 当前项目特别注意事项

- 根目录 `.env.example` 与 `deploy/compose/.env.example` 曾出现不一致，Compose 部署应优先使用 `deploy/compose/.env.example` 复制出的 `.env`。
- 本地可能存在旧容器占用 39210-39218 端口，部署前要检查 Compose 项目名和容器状态。
- 如果只是前端样式修改，明确提示用户没有部署 Docker，由用户自行部署。
- 状态、类型、角色、来源、环境等字段，前端应优先做中文枚举下拉框。
- 组织 / 部门可后置；成员管理 + 固定角色分配建议补进 MVP。
- 平台管理员工作台、租户工作台、开发者门户是一套平台下的不同角色视图，不是三套独立平台。
- 真实 DeepSeek 等供应商全链路是否打通，需要以实际供应商 Key 和端到端调用验证为准。
