# TokenSea `deepseek-v4-flash` 端到端测试记录

> 测试日期：2026-07-16  
> 测试环境：TokenSea 本地混合研发环境  
> 测试范围：官方价格生成 → 模型部署 → 能力验证 → 生效价格 → 路由策略 → 企业服务模型 → 租户体系 → Virtual Key → Gateway 调用 → 用量与成本  
> 最终结论：**TokenSea 平台端到端闭环通过；外部 DeepSeek 生产 API 未验证。**

---

## 1. 测试结论

本次以 `deepseek-v4-flash` 为目标模型，按照《TokenSea 从价格生成到 Virtual Key 可用的端到端测试操作流程》执行了完整测试。

最终形成以下闭环：

```text
DeepSeek 官方价格页
→ 价格同步与标准化
→ 价格差异审核
→ 供应商官方价格目录
→ PROVIDER_OFFICIAL 模型生效价格
→ 测试供应商渠道
→ 模型发现与部署审核
→ LIVE_PROBE 能力验证
→ 路由策略审批与生效
→ 企业服务模型审批与发布
→ 租户、项目和应用
→ Virtual Key 审批与生成
→ /v1/models 模型权限验证
→ /v1/chat/completions Gateway 调用
→ usage_record
→ usage_cost_snapshot
→ 预算结算与审计记录
```

### 1.1 已通过范围

- DeepSeek 官方价格页真实获取和解析；
- `deepseek-v4-flash` 官方价格差异审核和发布；
- 官方价格目录与模型部署自动匹配；
- 模型生效价格生成；
- TokenSea Control Plane 配置闭环；
- TokenSea Gateway 鉴权、路由、限流和预算链路；
- LiteLLM Runtime Core 动态模型注册和持久化模型复用；
- Virtual Key 模型范围校验；
- OpenAI 兼容对话请求；
- 用量、成本快照、预算状态和操作审计落库。

### 1.2 未验证范围

当前环境没有可用的 DeepSeek 原始 API Key，旧恢复库中也没有可迁移的 DeepSeek 渠道凭据。因此：

```text
未验证 TokenSea → api.deepseek.com 的真实生产请求
未验证真实 DeepSeek 账号是否开通 deepseek-v4-flash
未验证真实 DeepSeek 账单与 TokenSea 成本快照的一致性
```

本次最终模型响应由明确标识的本地 OpenAI 兼容测试上游提供：

```text
http://host.docker.internal:39301/v1
```

该测试上游仅用于验证 TokenSea 平台内部链路，不能视为真实 DeepSeek 服务验证。

---

## 2. 最终成功结果

### 2.1 最终 Gateway 请求

| 项目 | 结果 |
|---|---|
| 企业服务模型 | `deepseek-v4-flash` |
| 请求接口 | `POST /v1/chat/completions` |
| 请求状态 | `SUCCESS` |
| 请求 ID | `86f7d86fc1ef4da6a841162052f5b9ec` |
| Prompt Token | 12 |
| Completion Token | 8 |
| Total Token | 20 |
| 实际运行模型 | `openai/deepseek-v4-flash` |
| 供应商渠道 | E2E DeepSeek Flash Mock |
| 延迟 | 1468 ms |
| Fallback 次数 | 0 |

### 2.2 成本与记账结果

| 项目 | 结果 |
|---|---|
| 价格层级 | `PROVIDER_OFFICIAL` |
| 价格版本 ID | `0fc7978f91864f1bbbc2b77cb1c34b5e` |
| 币种 | USD |
| 输入价格/千 Token | `0.00014000` |
| 输出价格/千 Token | `0.00028000` |
| 输入成本 | `0.00000168` |
| 输出成本 | `0.00000224` |
| 实际总成本 | `0.00000392` |
| 预算状态 | `SETTLED` |
| 记账状态 | `COMMITTED` |
| 价格计算器版本 | `2.0.0` |
| 价格证据哈希 | `5ed7309f6b8bf5dbae559a012341aa604d02b0cce2e20c48aaa6f0a0bf287f89` |

### 2.3 最终对象 ID

| 对象 | ID |
|---|---|
| 供应商渠道 | `1e2636250650eb4c62cf66fb0f73108c` |
| 模型部署 | `e0f70f4756c049928d19802b18d12915` |
| 模型生效价格 | `0fc7978f91864f1bbbc2b77cb1c34b5e` |
| 企业服务模型 | `782eda9634b7073ffe59d2a51d8bfd82` |
| 路由策略 | `e79616e133fc6e98d7eaa0f3249c0837` |
| 租户 | `44ba129653d2f7ab1f659e8c69f8f4f8` |
| Virtual Key | `9e2bfeb8c9764e5944ac10bdec18c1cc` |
| 最终请求 | `86f7d86fc1ef4da6a841162052f5b9ec` |

Virtual Key 明文未写入日志、测试报告或 Git。测试结果仅保留前缀：

```text
ts_nd8RCcTE6…
```

---

## 3. 实际执行过程

### 3.1 服务健康检查

测试期间确认：

| 服务 | 地址 | 结果 |
|---|---|---|
| Console | `http://localhost:39210` | HTTP 200 |
| Control Plane | `http://localhost:39211/actuator/health` | UP |
| Gateway Runtime | `http://localhost:39212/health` | ok |
| Runtime Core | `http://localhost:39218/health/liveliness` | alive |
| 本地测试上游 | `http://localhost:39301/health` | ok |

### 3.2 官方价格生成

使用现有 DeepSeek 官方价格源：

```text
价格适配器：DEEPSEEK_OFFICIAL_PAGE
官方来源：https://api-docs.deepseek.com/quick_start/pricing/
```

解析结果：

```text
模型：deepseek-v4-flash
币种：USD
输入价格/千 Token：0.00014
输出价格/千 Token：0.00028
缓存读取价格/千 Token：0.0000028
```

审核发布后生成：

```text
priceLayer=PROVIDER_OFFICIAL
status=ACTIVE
version=2
```

版本 2 是测试过程中发现并修复“小数精度 scale 误判”为价格变化之前产生的历史记录。修复后重复同步未再生成版本 3。

### 3.3 模型渠道、部署与能力

由于没有真实 DeepSeek Key，本次建立了明确的测试渠道：

```text
渠道名称：E2E DeepSeek Flash Mock
API Base：http://host.docker.internal:39301/v1
供应商类型：deepseek
API Style：openai_compatible
```

测试过程：

```text
托管测试凭据
→ 连接测试成功
→ 渠道启用
→ /v1/models 发现 deepseek-v4-flash
→ 模型部署审核通过
→ CHAT LIVE_PROBE 通过
→ routingStatus=ELIGIBLE
```

### 3.4 路由与企业服务模型

路由策略：

```text
名称：E2E DeepSeek V4 Flash Route
服务模型：deepseek-v4-flash
策略：priority
Fallback：关闭
候选渠道：E2E DeepSeek Flash Mock
实际模型：deepseek-v4-flash
价格版本：0fc7978f91864f1bbbc2b77cb1c34b5e
状态：ACTIVE
```

企业服务模型：

```text
服务模型名：deepseek-v4-flash
供应商渠道：E2E DeepSeek Flash Mock
实际模型：deepseek-v4-flash
路由策略：E2E DeepSeek V4 Flash Route
状态：已发布
```

### 3.5 租户与 Virtual Key

测试数据：

```text
租户：E2E Flash Test Tenant
项目：E2E Flash Test Project
应用：E2E Flash Test App
Key：E2E Flash Virtual Key
模型范围：["deepseek-v4-flash"]
```

验证结果：

```text
Key 审批通过
Key 状态 ACTIVE
/v1/models 返回 deepseek-v4-flash
/v1/chat/completions 返回成功
```

---

## 4. 发现的异常与修复

### 4.1 当前数据库缺少完整供应商和 Key 数据

**现象**

主数据库中存在部分价格差异和企业服务模型草稿，但供应商渠道、模型部署、供应商密钥、租户和 Virtual Key 均为空。旧恢复数据库也是旧版结构，且没有可迁移的 DeepSeek 凭据。

**处理**

- 未伪造真实 DeepSeek 凭据；
- 建立明确标识的本地 OpenAI 兼容测试上游；
- 通过 Control Plane API 从渠道开始重建测试闭环；
- 报告中明确标记真实 DeepSeek 外部 API 未验证。

### 4.2 同一价格范围重复产生待审核差异

**现象**

`deepseek-v4-flash` 连续同步产生 6 条相同的：

```text
PENDING / MODEL_ADDED
```

**根因**

价格同步逻辑每次都新增差异，没有复用相同来源、模型、区域和计费范围的待审核记录。

**修复**

- 同范围存在 PENDING 差异时更新最新记录；
- 旧重复差异标记为 `IGNORED`；
- 审核意见写入“同一价格范围的待审核差异已合并”；
- 审核人使用 `SYSTEM`。

**最终结果**

```text
待审核差异数量：0
五条历史重复记录：IGNORED
有效审核记录：APPROVED
```

### 4.3 相同价格被误判为 PRICE_CHANGED

**现象**

输入、输出价格完全相同，第二次同步仍生成 `PRICE_CHANGED`，并创建 V2。

**根因**

JSONB 读取后的 `BigDecimal` 为：

```text
0.000140000000
```

新解析值为：

```text
0.00014
```

代码使用 `Map.equals`，把数值相等但 scale 不同的 BigDecimal 判断为不同。

**修复**

新增递归 JSON 数值语义比较：

- 数字统一使用 `BigDecimal.compareTo`；
- Map 按 Key 递归比较；
- List 按索引递归比较；
- 字符串和布尔值保持严格比较。

**验证**

修复后多次同步：

```text
没有新的待审核差异
没有生成 V3
仍保持 V2 ACTIVE
```

### 4.4 企业服务模型误填新版价格版本导致 500

**现象**

创建企业服务模型时，把新版 `price_version.id` 写入旧字段 `pricePolicyId`，数据库外键指向旧表 `model_price`，导致 500。

**根因**

新版供应商官方价格应绑定在路由候选的 `priceVersionId`，不应写入旧版企业售价字段。

**修复**

- E2E 流程不再给企业服务模型填写旧 `pricePolicyId`；
- 新版生效价格只绑定在路由候选；
- Control Plane 对无效旧价格 ID 提前返回明确的 400：

```text
pricePolicyId 仅支持旧版模型售价；供应商生效价格请在路由策略中选择价格版本
```

避免数据库外键异常伪装成 500。

### 4.5 Runtime Core 重启后动态模型重复注册失败

**现象**

Gateway 重启后内存注册缓存为空，但 LiteLLM 数据库仍有相同动态模型。再次调用 `/model/new` 返回：

```text
Unique constraint failed on model_id
```

**修复**

- 动态模型注册非 200/201 时先查询模型是否已持久化；
- 支持 LiteLLM v1.91 的 `modelId`、`search` 和分页查询；
- 支持识别 `model_name`、顶层 `id` 和 `model_info.id`；
- 已存在时视为幂等成功，不再中断业务调用。

### 4.6 本地 Runtime Engine Key 可能继承旧值

**现象**

`.env` 只配置 Runtime Core Key，但父 PowerShell 进程可能残留旧 `TOKENSEA_RUNTIME_ENGINE_KEY`，导致 Gateway 查询 Runtime Core 返回 401。

**修复**

`Import-TokenSeaDevEnvironment.ps1` 现在：

- 记录 `.env` 中明确配置的变量；
- `.env` 未明确配置 Engine Key 时，始终用当前 Runtime Core Key 覆盖父进程残留值；
- `.env` 明确配置 Engine Key 时才保留独立值。

验证结果：

```text
ENGINE_MATCHES_CORE=True
```

### 4.7 Windows 下残留多套 Uvicorn Gateway 进程

**现象**

存在两套 `uvicorn app.main:app --port 39212` 进程树，旧进程仍在处理请求，新代码看似重启但未真正接管端口。

**处理**

- 仅识别并递归停止命令行明确属于 TokenSea Gateway 的进程树；
- 保留其他 Python 进程，包括占用 39300 的独立 HTTP Server；
- 确认 39212 释放后只启动一套 Gateway。

**结果**

新 Gateway 正确加载修复代码，最终 E2E 成功。

### 4.8 Runtime Core 访问本地测试上游被代理拦截

**现象**

Runtime Core 容器访问 `host.docker.internal` 时经过 Egress Proxy，返回 403。

**修复**

- 增加可配置的 Runtime Core `NO_PROXY` 追加项；
- 当前研发环境仅追加精确主机：`host.docker.internal`；
- 生产默认追加值为空；
- 本地测试模式默认关闭。

---

## 5. 新增的测试工具

### 5.1 本地 OpenAI 兼容测试上游

文件：

```text
scripts/dev/mock-openai-upstream.py
```

支持：

- `GET /health`
- `GET /v1/models`
- `POST /v1/chat/completions`
- 流式对话响应
- `POST /v1/embeddings`

默认端口：

```text
39301
```

### 5.2 一键端到端脚本

文件：

```text
scripts/dev/run-flash-e2e.py
```

脚本特点：

- 使用 Control Plane API 完成配置；
- 使用 Gateway API 完成最终调用；
- 不打印管理员 JWT；
- 不打印供应商测试密钥；
- 不打印完整 Virtual Key；
- 配置对象可重复复用；
- 每次测试轮换专用 E2E Virtual Key；
- 自动核验 `/v1/models`、对话响应、用量和成本；
- 结果写入：

```text
docs/testing/deepseek-v4-flash-e2e-result.json
```

执行命令：

```powershell
py -3 .\scripts\dev\run-flash-e2e.py
```

---

## 6. 自动化测试结果

### 6.1 Control Plane

执行：

```text
ControlPlaneContractTests
Phase1GContractTests
ProviderPriceSyncIntegrationTests
```

结果：

```text
Tests run: 13
Failures: 0
Errors: 0
Skipped: 1
```

跳过项是需要显式传入独立 PostgreSQL 集成测试数据库的 Flyway 全量测试，不是代码失败。

### 6.2 Gateway Runtime

执行：

```text
python -m unittest tests.test_runtime_rules
```

结果：

```text
Ran 27 tests
OK
```

覆盖：

- 本地测试主机精确匹配；
- SSRF 默认拒绝；
- Runtime Core 持久化模型复用；
- LiteLLM v1.91 模型查询兼容；
- 预算、成本、WAL 和密钥解密既有规则。

### 6.3 实时端到端

一键 E2E 共 12 个步骤：

```text
12 PASS
0 WARN
0 FAIL
```

---

## 7. 当前保留的测试数据

以下 E2E 数据暂时保留为 ACTIVE，便于在 Console 中检查：

```text
E2E DeepSeek Flash Mock
E2E DeepSeek V4 Flash Route
deepseek-v4-flash 企业服务模型
E2E Flash Test Tenant
E2E Flash Test Project
E2E Flash Test App
E2E Flash Virtual Key
```

本地 Mock 上游仍运行于 39301。它只用于研发验证，不应作为生产供应商渠道。

生产部署前必须：

1. 将 `TOKENSEA_LOCAL_TEST_UPSTREAM_ENABLED` 设置为 `false`；
2. 从出口白名单移除 `host.docker.internal` 和 39301；
3. 移除 Runtime Core 的本地 `NO_PROXY` 追加项；
4. 停止本地 Mock 进程；
5. 使用真实 DeepSeek 渠道凭据重新执行连接测试和模型调用；
6. 对真实供应商账单进行成本对账。

---

## 8. 最终验收判断

### TokenSea 平台闭环

```text
通过
```

验证了价格、模型、能力、路由、企业服务模型、租户、Virtual Key、Gateway、Runtime Core、用量、成本、预算和审计的完整链路。

### DeepSeek 外部生产服务

```text
未验证
```

缺少真实 DeepSeek API Key。待提供真实渠道凭据后，只需替换测试渠道并执行以下步骤：

```text
托管真实 DeepSeek Key
→ 连接测试
→ 模型发现
→ LIVE_PROBE
→ 路由切换
→ 生成测试 Key
→ /v1/models
→ /v1/chat/completions
→ 供应商账单对账
```

真实 DeepSeek 调用通过后，才能将整体结论升级为“生产外部链路通过”。
