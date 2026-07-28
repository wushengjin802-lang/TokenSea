# TokenSea 接入 Kimi 并生成可用 Virtual Key 操作指导书

> 文档版本：V1.1  
> 原始版本：V1.0（2026-07-21）  
> 修订日期：2026-07-24  
> 适用对象：TokenSea 平台管理员、实施人员、联调测试人员、运维人员  
> 适用环境：TokenSea 当前研发版本（Console `39210`、Control Plane `39211`、Gateway Runtime `39212`）  
> 示例供应商：Moonshot AI / Kimi  
> 示例上游模型：`kimi-k2.6`  
> 示例企业服务模型：`kimi-enterprise`  

---

## 0. V1.1 关键变化

V1.0 将“人工核验并录入 Kimi 官方价格”作为主流程。当前 TokenSea 已完成 Kimi 官方动态价格页适配、独立 Headless Fetcher、价格差异审核、新模型候选和部署生产准入治理，因此 V1.1 调整为以下新流程：

```text
Kimi 官方动态价格页
→ 独立 Headless Fetcher 安全渲染
→ 测试获取 / 测试解析
→ 每日价格同步
→ 价格差异审核
→ 供应商官方价格目录
→ 官方价格页模型候选
→ Kimi 渠道 /v1/models 真实发现
→ 渠道级模型部署
→ 真实能力探测
→ 人工确认生产准入
→ 路由策略
→ 企业服务模型发布
→ Virtual Key
→ 真实调用、用量与成本快照
```

本次修订重点取消以下旧口径：

1. 不再默认要求管理员手工录入 Kimi 官方价格。
2. 不再允许用 LiteLLM 或 models.dev 公共参考价直接替代生产成本价。
3. 不再把“价格页出现模型”视为“渠道已经可调用”。
4. 不再依赖路由生效时自动完成全部探测和生产批准。
5. 模型真实探测通过后，仍需管理员单独确认进入生产。
6. 新模型首价、页面结构变化和高风险价格变化必须人工审核。

---

## 1. 文档目标

本文指导管理员在 TokenSea 中完成 Kimi 的完整接入闭环，包括：

- Kimi 官方价格自动获取和定时同步；
- 官方价格差异审核和价格版本生成；
- Kimi 新模型候选自动发现；
- 供应商渠道和上游密钥托管；
- 渠道级 `/v1/models` 真实模型发现；
- 模型部署真实能力探测和生产准入；
- 企业服务模型和路由策略配置；
- 租户、项目、应用和 Virtual Key 创建；
- OpenAI-compatible API 真实调用；
- 调用日志、Token、成本快照、预算和审计核验。

完整链路如下：

```mermaid
flowchart LR
    A[Kimi 官方价格页] --> B[Headless Fetcher 安全渲染]
    B --> C[测试解析与结构指纹]
    C --> D[每日自动同步]
    D --> E[价格差异审核]
    E --> F[供应商官方价格目录]
    D --> G[模型候选 PRICE_ONLY]
    H[Kimi 供应商渠道] --> I[托管上游 API Key]
    I --> J[连接测试]
    J --> K[调用 /v1/models]
    K --> L[渠道模型部署 DISCOVERED]
    F --> M[正式成本价格匹配]
    L --> M
    M --> N[真实 LIVE_PROBE]
    N --> O[管理员确认生产准入]
    O --> P[路由策略 ACTIVE]
    P --> Q[企业服务模型已发布]
    Q --> R[租户 / 项目 / 应用]
    R --> S[创建 Virtual Key]
    S --> T[/v1/models 权限验证]
    T --> U[/v1/chat/completions 真实调用]
    U --> V[usage / attempt / cost snapshot / audit]
```

最终成功标准：

```text
业务应用只持有 TokenSea Virtual Key，
只使用企业服务模型名 kimi-enterprise，
不接触 Kimi 上游 API Key 和真实渠道配置，
通过 TokenSea Gateway 成功调用 Kimi，
平台能够记录实际渠道、实际模型、Token、价格版本、成本快照和审计。
```

---

## 2. 三类对象和两类 Key 必须区分

### 2.1 三类模型对象

| 对象 | 示例 | 作用 |
|---|---|---|
| 官方模型候选 | `kimi-k2.6`，状态 `PRICE_ONLY` | 价格页或官方文档发现，尚未证明当前渠道可调用 |
| 渠道模型部署 | Kimi 生产渠道 + `kimi-k2.6` | 由具体账号调用 `/v1/models` 真实发现，可继续探测和生产审核 |
| 企业服务模型 | `kimi-enterprise` | 业务方稳定调用名，可在不改业务代码的情况下替换底层渠道或模型 |

关键原则：

```text
价格页发现 ≠ 渠道可调用
渠道可调用 ≠ 已批准生产
已批准生产 ≠ 已向某个租户和 Key 授权
```

### 2.2 两类 Key

| 类型 | 示例 | 使用位置 | 是否交付给业务方 |
|---|---|---|---|
| Kimi 上游 API Key | Kimi 开放平台生成的密钥 | TokenSea“供应商渠道”内部托管 | 否 |
| TokenSea Virtual Key | `ts_xxxxxxxxx...` | 业务应用调用 TokenSea Gateway | 是 |

安全规则：

1. Kimi API Key 只进入 TokenSea 凭据托管。
2. 业务方只能获得 TokenSea Virtual Key。
3. Virtual Key 可以限制租户、项目、应用、服务模型、预算、RPM、TPM、QPS、IP 白名单和有效期。
4. Virtual Key 明文只显示一次，数据库只保存哈希。
5. Kimi 上游 Key 和 TokenSea Virtual Key 都不得写入 Git、文档正文、截图或公开日志。

---

## 3. 新 Kimi 接入架构

### 3.1 价格链路

当前内置 Kimi 官方价格源：

```text
价格源 ID：builtin_kimi_cn_official_price
价格源名称：Kimi 中国站官方价格
来源类别：供应商官方价格 / OFFICIAL
适配器：KIMI_OFFICIAL_PAGE
供应商类型：moonshot
区域：cn
默认币种：CNY
同步周期：P1D
默认获取模式：AUTO，新环境首次启用前应测试；动态页面实际可切换为 HEADLESS
官方域名：platform.kimi.com
```

Kimi 价格页为动态页面。TokenSea 使用独立的 `tokensea-headless-fetcher` 服务渲染页面，而不是在 Control Plane 内嵌浏览器。

Headless Fetcher 具备以下限制：

- 内部令牌认证；
- 只允许价格源声明的官方域名；
- 拒绝回环、私网和保留地址；
- 通过 TokenSea Egress Proxy 出口；
- 限制渲染超时、等待时间、重定向和页面大小；
- 拦截不在官方域名白名单中的第三方请求；
- 容器只读并删除额外 Linux capabilities；
- 正式 Compose 环境仅暴露在内部网络。

### 3.2 模型发现链路

TokenSea 同时使用两类发现来源：

```text
官方价格页 / 官方文档
→ 产生模型候选
→ 状态通常为 PRICE_ONLY

具体 Kimi 供应商渠道 /v1/models
→ 证明当前账号和渠道真实可见
→ 产生或更新渠道模型部署
→ 候选状态可变为 CHANNEL_VERIFIED
```

模型是否存在的生产事实以具体渠道调用以下接口的返回为准：

```http
GET https://api.moonshot.cn/v1/models
Authorization: Bearer <MOONSHOT_API_KEY>
```

官方价格页只负责价格和候选证据，不能直接生成可路由生产部署。

### 3.3 价格优先级

TokenSea 运行时有效成本价格优先级为：

```text
合同价 CONTRACT_PRICE
> 渠道实际成本 CHANNEL_ACTUAL
> 供应商官方价 PROVIDER_OFFICIAL
> 无正式价格，不允许进入生产路由
```

官方价格自动同步不会覆盖合同价或渠道实际成本价，只会更新官方目录、差异审核和价格匹配结果。

---

## 4. 为什么仍以 `kimi-k2.6` 为示例

本指南继续使用 `kimi-k2.6`，原因是该模型已经被 TokenSea 当前 Kimi 价格适配器和验收数据验证过，能够完整展示：

- 官方动态价格页解析；
- 缓存命中价格；
- 缓存未命中输入价格；
- 输出价格；
- 每百万 Token 计费；
- 渠道发现、真实探测和生产准入流程。

Kimi 官方模型会持续更新。当前官方文档提供 `/v1/models` 接口，并明确账号应通过该接口获取当前可用模型。实际接入时必须使用当前渠道真实返回的模型 ID。

若价格页先出现 `kimi-k3`、`kimi-k2.7-code` 或其他新模型，而渠道 `/v1/models` 尚未返回，TokenSea 只会将其记录为 `PRICE_ONLY` 候选，不允许直接创建生产路由。

---

## 5. 操作前准备

### 5.1 Kimi 侧准备

需要准备：

- Kimi 开放平台账号；
- 已完成必要的认证或充值；
- 一个有效的 Kimi API Key；
- 账号具备目标模型调用权限；
- 账号余额充足；
- 已确认账号 RPM、TPM、并发和区域限制。

Kimi 中国区 OpenAI-compatible API Base：

```text
https://api.moonshot.cn/v1
```

### 5.2 TokenSea 服务检查

浏览器访问：

```text
http://localhost:39210
```

PowerShell：

```powershell
Invoke-RestMethod http://localhost:39211/actuator/health
Invoke-RestMethod http://localhost:39212/health
Invoke-RestMethod http://localhost:39212/health/readiness
```

本地混合开发模式中，Headless Fetcher 可额外检查：

```powershell
Invoke-RestMethod http://localhost:39219/health
```

说明：正式 Compose 部署中，Headless Fetcher 默认只连接内部网络，不要求对宿主机公开 `39219`。

预期：

- Control Plane 为 `UP`；
- Gateway Runtime 健康；
- Readiness 为 `ready`；
- Headless Fetcher 为 `UP` 或内部健康；
- 管理员可以登录 Console。

### 5.3 出口域名

至少需要允许访问：

```text
api.moonshot.cn
platform.kimi.com
```

用途：

| 域名 | 用途 |
|---|---|
| `api.moonshot.cn` | 连接测试、模型发现、真实探测、业务调用 |
| `platform.kimi.com` | 官方价格页渲染、价格同步和新价格页发现 |

---

## 6. 阶段一：启用 Kimi 官方价格自动同步

### 6.1 找到内置 Kimi 价格源

菜单：

```text
高级治理 → 价格源管理
```

搜索：

```text
Kimi 中国站官方价格
```

首次部署或数据库升级后，内置价格源通常处于：

```text
状态：暂停 / PAUSED
```

这是安全设计。必须先完成测试获取和测试解析，再允许启用自动同步。

### 6.2 检查价格源配置

建议值：

| 字段 | 建议值 |
|---|---|
| 价格源类别 | 供应商官方价格 `OFFICIAL` |
| 适配器 | Kimi 中国站官方价格页 `KIMI_OFFICIAL_PAGE` |
| 供应商类型 | `moonshot` |
| 认证方式 | 无需认证 `NONE` |
| 官方来源地址 | `https://platform.kimi.com/docs/pricing/chat-k26` |
| 官方域名 | `platform.kimi.com` |
| 区域 | 中国 `cn` |
| 默认币种 | 人民币 `CNY` |
| 同步周期 | 每天 `P1D` |
| 获取模式 | 无头浏览器 `HEADLESS`；也可先用 `AUTO` 测试 |
| 低风险自动发布 | 可启用，但新模型首价和高风险变化仍需审核 |
| 最大自动变动比例 | `0.10` |
| 连续确认次数 | `2` |
| 来源优先级 | `100` |
| 价格性质 | 官方原价 `ORIGINAL` |

不要把 models.dev 或 LiteLLM 公共参考源改成供应商官方源。

### 6.3 执行“测试获取”

点击：

```text
测试获取
```

检查：

- HTTP 状态为 `200`；
- Content-Type 为 HTML；
- 响应字节数大于 0；
- 最终地址仍属于 `platform.kimi.com`；
- 没有非官方域名、私网地址、重定向或页面大小安全错误。

“测试获取”成功只证明页面可以安全取得，不代表价格可以正确使用。

### 6.4 执行“测试解析”

点击：

```text
测试解析
```

重点检查：

| 字段 | 成功标准 |
|---|---|
| 标准化记录数 | 大于 0 |
| 结构指纹 | 非空 SHA-256 |
| 解析警告 | 无阻断性警告 |
| 建议使用无头浏览器 | 在 HEADLESS 成功时应为否 |
| 发现的官方定价子页面 | 仅包含独立页面，不应包含 `#fragment` 锚点 |
| 标准化样例 | 模型名、币种、计费单位、输入/缓存/输出价格结构正确 |

以 `kimi-k2.6` 为例，标准化记录应满足：

```text
providerType = moonshot
providerModelName = kimi-k2.6
currency = CNY
billingBasis = TOKEN
billingQuantity = 1000000
requestMode = STANDARD
serviceTier = DEFAULT
priceCompletenessStatus = COMPLETE
```

价格字段应区分：

```text
INPUT_TOKEN：缓存未命中输入价
CACHE_READ_TOKEN：缓存命中价
CACHE_WRITE_TOKEN：当前 Kimi 规则下沿用普通输入价 / INHERIT_INPUT
OUTPUT_TOKEN：输出价
```

不要把模型版本号中的数字误认为价格，也不要把 Batch 价格解析成标准实时价格。

### 6.5 启用价格源

测试解析通过后点击：

```text
启用
```

启用后：

- 状态应为 `ACTIVE`；
- 同步周期为 `P1D`；
- `nextRunAt` 应有下一次执行时间；
- 可以点击“立即同步”执行首轮正式同步。

### 6.6 查看价格同步任务

价格源执行后，进入价格同步任务详情，确认：

```text
状态：SUCCEEDED、NO_CHANGE 或 REVIEW_REQUIRED
HTTP：200
标准化记录：大于 0
```

新模型首价通常显示：

```text
状态：REVIEW_REQUIRED
风险：HIGH
自动发布：0
待审核：大于 0
```

这是正常结果，不要通过数据库直接改为 ACTIVE。

### 6.7 审核价格差异

菜单：

```text
高级治理 → 价格差异审核
```

搜索目标模型，例如：

```text
kimi-k2.6
```

审核前必须核对：

- 来源是 Kimi 官方价格页；
- 供应商类型为 `moonshot`；
- 区域为 `cn`；
- 币种为 `CNY`；
- 调用模式为 `STANDARD`；
- 服务层级和上下文阶梯正确；
- 计费基数为 `1000000`；
- 输入缓存未命中、缓存命中、缓存写入和输出价格关联正确；
- 页面结构指纹没有异常变化；
- 不是活动价、Batch 价或功能附加费误识别。

确认无误后点击：

```text
批准
```

批准会生成或更新“供应商官方价格”目录记录，并保留原始快照、证据哈希、解析器版本和审核信息。

### 6.8 检查供应商官方价格

菜单：

```text
高级治理 → 供应商官方价格
```

搜索：

```text
kimi-k2.6
```

预期：

| 字段 | 预期值 |
|---|---|
| 供应商 | `moonshot` |
| 供应商模型 | `kimi-k2.6` |
| 币种 | `CNY` |
| 计费对象 | `TOKEN` |
| 计费基数 | `1000000` |
| 调用模式 | `STANDARD` |
| 输入价格 | 官方缓存未命中输入价 |
| 缓存命中价格 | 官方缓存命中价 |
| 缓存写入模式 | `INHERIT_INPUT` 或当前官方规则 |
| 输出价格 | 官方输出价 |
| 价格完整性 | `COMPLETE` |
| 状态 | `ACTIVE` |

### 6.9 页面异常时的处理原则

| 现象 | 平台行为 | 管理员处理 |
|---|---|---|
| Headless Fetcher 不可用 | 同步失败或价格源降级 | 修复服务，不覆盖旧价格 |
| 页面结构指纹变化 | 生成高风险差异 | 人工核对新页面结构 |
| 解析记录为 0 | 不发布新价格 | 保持旧价格，检查页面和适配器 |
| 币种冲突 | 拒绝同步 | 检查价格源默认币种和页面区域 |
| 页面出现新模型 | 生成首价差异和模型候选 | 先审核价格，再等待渠道真实发现 |
| 价格页面消失或临时错误 | 保留旧有效价格 | 不把公共参考价自动转为生产价 |

人工维护官方目录只作为受控应急方案，必须保存官方证据、核验人和生效时间；不应重新成为日常主流程。

---

## 7. 阶段二：检查 Kimi 模型候选

菜单：

```text
模型配置 → 模型候选
```

价格同步完成后，官方价格页中出现的模型会进入候选池。

典型状态：

| 状态 | 含义 | 能否进入生产路由 |
|---|---|---|
| `PRICE_ONLY` / 仅价格页发现 | 官方价格页或官方子页面出现，但当前渠道尚未验证 | 否 |
| `CHANNEL_VERIFIED` / 渠道已验证 | 至少一个具体供应商渠道真实发现该模型 | 仍需价格、探测和生产审核 |
| `IGNORED` / 已忽略 | 管理员确认不纳入治理 | 否 |

候选页可查看：

- 首次发现来源；
- 官方证据地址；
- 价格源发现次数；
- 渠道验证次数；
- 当前渠道部署数；
- 有效官方价格数；
- 首次和最近发现时间。

若候选为 `PRICE_ONLY`，点击“验证渠道”只能检查是否已有匹配渠道，不会绕过 Kimi `/v1/models` 直接创建生产部署。

---

## 8. 阶段三：创建 Moonshot / Kimi 供应商渠道

### 8.1 新建供应商渠道

菜单：

```text
模型配置 → 供应商渠道
```

点击“新建”，填写：

| 字段 | 建议值 |
|---|---|
| 渠道名称 | Kimi 生产渠道 |
| 供应商模板 | Moonshot / Kimi |
| 协议 | OpenAI-compatible |
| API 地址 | `https://api.moonshot.cn/v1` |
| 区域 | 中国 `CN` |
| 环境 | 生产 |
| 负责人 | 实际负责人 |
| 每分钟请求 | 不高于 Kimi 账号 RPM |
| 每分钟 Token | 不高于 Kimi 账号 TPM |
| 状态 | 启用 |

注意：

- API 地址必须包含 `/v1`；
- 不要填写 TokenSea Gateway 地址；
- 不要把 Kimi API Key 拼接到 URL；
- RPM、TPM 应保守配置，不能高于供应商账号真实限制。

### 8.2 托管 Kimi 上游密钥

在渠道记录上点击：

```text
托管密钥
```

填写：

| 字段 | 建议值 |
|---|---|
| 密钥名称 | Kimi 生产 API Key |
| 密钥值 | Kimi 开放平台生成的完整 API Key |

保存后：

- 页面不能再次显示完整上游密钥；
- Key 状态应显示已托管或有效；
- 禁止从数据库或日志恢复明文。

### 8.3 执行连接测试

点击：

```text
连接测试
```

连接测试主要验证：

- API 地址格式；
- DNS 与出口策略；
- 目标主机；
- 上游鉴权；
- `/v1/models` 可访问性；
- 密钥状态。

成功标准：

```text
健康状态：正常
密钥状态：有效或已托管
最近连接测试：成功
```

常见错误：

| 状态 | 常见原因 | 处理方式 |
|---|---|---|
| 401 | Kimi Key 错误或失效 | 重新托管正确密钥 |
| 403 | 账号、项目或模型权限不足 | 在 Kimi 平台确认权限 |
| 429 | RPM、TPM、并发或余额限制 | 降低流量或提升额度 |
| DNS/SSRF 拒绝 | 域名白名单或 DNS 快照异常 | 修复出口策略并重新测试 |
| 连接超时 | 代理、防火墙或网络不可达 | 检查 Egress Proxy 和网络 |

---

## 9. 阶段四：由具体渠道真实发现模型

### 9.1 手工发现

在“Kimi 生产渠道”上点击：

```text
发现模型
```

TokenSea 会调用：

```http
GET https://api.moonshot.cn/v1/models
Authorization: Bearer <MOONSHOT_API_KEY>
```

对返回的每个模型创建或更新渠道模型部署。

### 9.2 自动发现

启用渠道会按当前治理配置周期性发现模型，默认建议每 6 小时执行一次。

自动发现必须满足：

- 每个渠道独立执行；
- 同一模型在不同账号可能有不同可见性；
- 发现任务幂等；
- 不能因为官方价格页出现模型，就伪造渠道部署；
- 模型恢复后必须重新审核生产准入。

### 9.3 检查渠道模型部署

菜单：

```text
模型配置 → 模型部署
```

搜索：

```text
kimi-k2.6
```

首次真实发现后的典型状态：

| 字段 | 初始或目标状态 |
|---|---|
| 发现状态 | `DISCOVERED` |
| 技术健康 | 初始 `UNKNOWN`，探测后目标 `HEALTHY` |
| 成本价格 | 有正式目录时目标 `MATCHED_OFFICIAL` |
| 生产准入 | 初始 `PENDING_REVIEW` |
| 最近探测 | 探测后目标 `PASSED` |

此时即使已经 `DISCOVERED + MATCHED_OFFICIAL`，也不能直接进入生产路由。

### 9.4 检查模型生效价格

菜单：

```text
模型配置 → 模型生效价格
```

搜索 `kimi-k2.6`，确认：

| 字段 | 预期值 |
|---|---|
| 供应商类型 | `moonshot` |
| 供应商渠道 | Kimi 生产渠道 |
| 供应商模型 | `kimi-k2.6` |
| 价格层 | `PROVIDER_OFFICIAL`，除非存在更高优先级渠道价或合同价 |
| 币种 | `CNY` |
| 计费对象 | `TOKEN` |
| 计费基数 | `1000000` |
| 输入、缓存和输出价格 | 与已审核官方目录一致 |
| 状态 | `ACTIVE` |

若价格状态仍为 `MISSING`：

1. 检查供应商官方价格目录是否 `ACTIVE`；
2. 检查 `providerType=moonshot`；
3. 检查模型名是否与渠道 `/v1/models` 完全一致；
4. 检查区域、调用模式、服务层级和上下文阶梯；
5. 检查是否存在待审核模型别名；
6. 执行重新匹配或再次发现模型；
7. 查看模型部署“有效成本价格”。

---

## 10. 阶段五：真实探测和生产准入

### 10.1 执行真实探测

菜单：

```text
模型配置 → 模型部署
```

在目标部署上点击：

```text
真实探测
```

第一次普通对话接入选择：

```text
能力：对话 / CHAT
```

探测会真实调用 Kimi，因此需要：

- 有效上游 Key；
- 账号余额；
- 目标模型真实可用；
- 出口网络正常；
- 模型请求参数与供应商兼容。

成功标准：

```text
技术健康：HEALTHY
最近探测：PASSED
能力验证：CHAT + LIVE_PROBE + PASSED
```

真实探测只确认技术可用，不会自动批准生产。

### 10.2 确认进入生产

确认以下条件全部满足：

```text
发现状态：DISCOVERED
技术健康：HEALTHY
价格状态：MATCHED_OFFICIAL、MATCHED_CHANNEL 或 MATCHED_CONTRACT
最近探测：PASSED
```

然后点击：

```text
确认进入生产
```

填写批准说明，例如：

```text
已完成 Kimi 渠道模型发现、CHAT 真实探测和正式 CNY 成本价格核验，同意进入测试生产路由。
```

成功标准：

```text
生产准入：APPROVED
路由资格：ELIGIBLE
```

若价格缺失、探测失败或模型疑似消失，平台应拒绝批准。

### 10.3 模型消失治理

渠道自动发现时，模型单次未返回不会立即删除或中断生产。

当前治理建议：

```text
连续缺失 1～3 次：MISSING_SUSPECTED
连续缺失达到阈值：MISSING_CONFIRMED
确认消失后：暂停生产和路由资格
重新出现：RECOVERED，但必须重新探测和审核
```

不要手工删除历史模型部署、价格版本或历史调用成本快照。

---

## 11. 阶段六：创建企业服务模型和路由策略

### 11.1 创建企业服务模型草稿

业务系统应使用稳定业务名，而不是直接依赖供应商模型名。

推荐：

```text
企业服务模型：kimi-enterprise
上游实际模型：kimi-k2.6
供应商渠道：Kimi 生产渠道
```

菜单：

```text
模型配置 → 企业服务模型
```

点击“新建草稿”，填写：

| 字段 | 建议值 |
|---|---|
| 服务模型名 | `kimi-enterprise` |
| 展示名称 | Kimi 企业通用模型 |
| 可见范围 | 内部租户或指定测试租户 |
| 需要审批 | 测试环境可按治理要求设置 |
| 状态 | 草稿 |

先创建服务模型草稿，路由页面才能选择该企业服务模型。

### 11.2 创建路由策略

菜单：

```text
模型配置 → 路由策略
```

点击“新建策略”，填写：

| 字段 | 建议值 |
|---|---|
| 策略名称 | Kimi 企业模型主路由 |
| 企业服务模型 | `kimi-enterprise` |
| 策略类型 | 优先级 |
| 故障切换 | 单渠道首次测试可关闭 |

添加候选链路：

| 字段 | 建议值 |
|---|---|
| 供应商渠道 | Kimi 生产渠道 |
| 实际模型 | `kimi-k2.6` |
| 价格版本 | 对应的有效成本价格版本 |
| 优先级 | `1` |

路由候选必须来自：

```text
DISCOVERED
+ HEALTHY
+ 正式价格已匹配
+ APPROVED
+ ELIGIBLE
+ LIVE_PROBE PASSED
```

### 11.3 校验并生效路由

点击：

```text
校验并生效
```

当前校验重点包括：

- 企业服务模型存在；
- 供应商渠道有效；
- 候选实际模型来自该渠道；
- 模型部署已经批准生产；
- 价格版本有效且满足成本优先级；
- 币种和预算规则可用；
- 渠道连接和 DNS 状态有效；
- 上游密钥可用；
- 模型没有确认消失或暂停生产。

成功标准：

```text
路由状态：ACTIVE
```

### 11.4 绑定并发布企业服务模型

返回：

```text
模型配置 → 企业服务模型
```

编辑 `kimi-enterprise`：

- 路由策略选择“Kimi 企业模型主路由”；
- 可见范围覆盖测试租户；
- 保存后按治理要求提交审批或直接发布。

成功标准：

```text
企业服务模型：kimi-enterprise
状态：已发布
路由：ACTIVE
```

---

## 12. 阶段七：创建租户、项目和应用

### 12.1 创建或选择租户

菜单：

```text
组织与权限 → 租户管理
```

建议：

| 字段 | 示例值 |
|---|---|
| 租户名称 | Kimi 测试租户 |
| 租户类型 | 内部租户 |
| 可用服务模型 | `kimi-enterprise` |
| 月预算 | 按测试要求填写 |
| 状态 | 启用 |

启用租户只改变租户状态，不会创建 Virtual Key。租户可先保存为草稿，但启用前必须至少配置一个可用服务模型。Virtual Key 必须在“日常运营 → API Key”页面单独创建，默认采用应用级归属。

### 12.2 创建项目

菜单：

```text
组织与权限 → 项目管理
```

示例：

| 字段 | 示例值 |
|---|---|
| 所属租户 | Kimi 测试租户 |
| 项目名称 | Kimi 接入验证项目 |
| 负责人 | 测试负责人 |
| 月预算 | 按要求填写 |
| 状态 | 启用 |

### 12.3 创建应用

菜单：

```text
组织与权限 → 应用管理
```

示例：

| 字段 | 示例值 |
|---|---|
| 所属租户 | Kimi 测试租户 |
| 所属项目 | Kimi 接入验证项目 |
| 应用名称 | Kimi API 验证应用 |
| 负责人 | 测试负责人 |
| 环境 | 测试或生产 |
| 状态 | 启用 |

配置项目和应用后，调用记录才能稳定归因到租户、项目、应用和 Key。

---

## 13. 阶段八：创建应用级 Virtual Key

菜单：

```text
日常运营 → API Key
```

### 13.1 新建 Key

| 字段 | 建议值 |
|---|---|
| 租户 | Kimi 测试租户 |
| Key 归属层级 | 应用级（推荐） |
| 项目 | Kimi 接入验证项目 |
| 应用 | Kimi API 验证应用 |
| Key 名称 | Kimi 应用测试 Key |
| 允许调用的服务模型 | `kimi-enterprise` |
| 预算 | 按测试要求设置 |
| RPM | 不高于租户、路由和渠道限制 |
| TPM | 不高于租户、路由和渠道限制 |
| QPS | 按测试要求设置 |
| IP 白名单 | 本机测试可留空，生产填写可信出口 IP |
| 有效期 | 未来时间 |

保存后通常显示：

```text
状态：PENDING
Key 前缀：pending
```

### 13.2 生成密钥

点击：

```text
生成密钥
```

系统返回一次完整明文：

```text
ts_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

必须立即：

1. 复制完整明文；
2. 保存到密码管理器或安全环境变量；
3. 确认没有多余空格和换行；
4. 关闭弹窗前完成保存。

明文遗失后应新建 Key 并禁用旧 Key，不能从数据库恢复。

---

## 14. 阶段九：验证 Virtual Key 模型权限

### 14.1 Console 验证

菜单：

```text
开发者门户 → 服务模型列表
```

使用完整 Virtual Key 查询，预期包含：

```text
kimi-enterprise
```

业务方不应看到或依赖 `kimi-k2.6`。

### 14.2 PowerShell 验证 `/v1/models`

```powershell
$env:TOKENSEA_API_KEY = "ts_这里填写完整VirtualKey"

$headers = @{
  Authorization = "Bearer $env:TOKENSEA_API_KEY"
}

Invoke-RestMethod `
  -Uri "http://localhost:39212/v1/models" `
  -Headers $headers `
  -Method Get
```

成功标准：返回模型列表中包含 `kimi-enterprise`。

测试完成后：

```powershell
Remove-Item Env:TOKENSEA_API_KEY
```

### 14.3 使用项目验证脚本

进入项目目录：

```powershell
cd D:\12_其他项目\30_APIGateway\tokensea
```

仅验证健康、鉴权和模型范围：

```powershell
python .\scripts\dev\verify-virtual-key.py `
  --model kimi-enterprise `
  --skip-chat
```

脚本会安全提示输入 Virtual Key，或读取 `TOKENSEA_API_KEY` 环境变量。

---

## 15. 阶段十：执行 Kimi 真实调用

### 15.1 TokenSea Playground

菜单：

```text
开发者门户 → Playground
```

填写：

| 字段 | 值 |
|---|---|
| API Key | TokenSea Virtual Key，不是 Kimi Key |
| 平台模型 | `kimi-enterprise` |
| 消息 | `请只回复：TokenSea Kimi 调用成功` |

成功标准：

- 返回非空内容；
- 没有 401、403、429、502 或 503；
- 服务模型为 `kimi-enterprise`；
- 实际路由到 Kimi 渠道和对应供应商模型。

### 15.2 PowerShell 调用

```powershell
$env:TOKENSEA_API_KEY = "ts_这里填写完整VirtualKey"

$headers = @{
  Authorization = "Bearer $env:TOKENSEA_API_KEY"
  "Content-Type" = "application/json"
}

$body = @{
  model = "kimi-enterprise"
  messages = @(
    @{
      role = "user"
      content = "请只回复：TokenSea Kimi 调用成功"
    }
  )
  temperature = 0
  max_tokens = 64
  stream = $false
} | ConvertTo-Json -Depth 10

$response = Invoke-RestMethod `
  -Uri "http://localhost:39212/v1/chat/completions" `
  -Method Post `
  -Headers $headers `
  -Body $body

$response | ConvertTo-Json -Depth 20
Remove-Item Env:TOKENSEA_API_KEY
```

### 15.3 Python OpenAI SDK

```python
import os
from openai import OpenAI

client = OpenAI(
    api_key=os.environ["TOKENSEA_API_KEY"],
    base_url="http://localhost:39212/v1",
)

response = client.chat.completions.create(
    model="kimi-enterprise",
    messages=[
        {
            "role": "user",
            "content": "请只回复：TokenSea Kimi 调用成功",
        }
    ],
    temperature=0,
    max_tokens=64,
)

print(response.choices[0].message.content)
print(response.usage)
```

### 15.4 Kimi 特有参数

第一次端到端验证只使用标准 OpenAI Chat Completions 字段。

普通调用稳定后再单独验证：

- `thinking`；
- 流式输出；
- Tool Use；
- 图片或视频输入；
- JSON Mode；
- 超长上下文。

Kimi `thinking` 是供应商扩展参数，使用 OpenAI SDK 时通常通过 `extra_body` 传递。扩展参数失败不能直接判定 Virtual Key、渠道或路由失败。

---

## 16. 阶段十一：核验用量、成本和审计闭环

### 16.1 调用日志

菜单：

```text
日常运营 → 调用日志
```

确认：

- 请求状态成功；
- 租户、项目、应用和 Key 归属正确；
- 服务模型为 `kimi-enterprise`；
- 实际模型和供应商渠道正确；
- 请求 ID、延迟和 attempt 记录完整。

### 16.2 用量分析

菜单：

```text
日常运营 → 用量分析
```

确认：

- 输入、输出和总 Token 非空；
- 成本金额非空；
- 币种为 CNY；
- 价格版本 ID 非空；
- 预算状态和记账状态正常；
- Virtual Key、应用、项目和租户归因正确。

### 16.3 成本快照

在调用链详情中检查成本快照，至少应包含：

```text
priceLayer
priceVersionId
currency
billingBasis
billingQuantity
inputUnitPrice
outputUnitPrice
priceComponents
costComponents
sourceRef
evidenceHash
```

若实际调用返回缓存 Token，还应检查缓存读取、缓存写入和缓存成本组件。

### 16.4 内部成本单

菜单：

```text
成本管理 → 内部成本单
```

确认本次调用可以按以下维度统计：

- 租户；
- 项目；
- 应用；
- Virtual Key；
- 企业服务模型；
- 供应商渠道；
- 原币种成本和 CNY 汇总。

### 16.5 操作审计

菜单：

```text
高级治理 → 操作审计
```

至少应能追踪：

- Kimi 价格源配置、启用和暂停；
- 价格同步和价格差异审批；
- Kimi 渠道创建；
- 上游密钥托管或轮换；
- 连接测试和模型发现；
- 真实探测；
- 生产准入批准或拒绝；
- 路由策略生效；
- 企业服务模型发布；
- Virtual Key 创建、生成和禁用。

### 16.6 告警事件

菜单：

```text
日常运营 → 告警事件
```

确认没有新增以下异常：

- Headless Fetcher 不可用；
- 官方价格页结构变化；
- 价格解析为空；
- 模型正式价格缺失；
- Kimi 渠道不可用；
- 上游 Key 无效；
- DNS 或出口策略变化；
- 模型疑似消失；
- 路由无有效部署；
- 预算、RPM、TPM 或 QPS 超限。

---

## 17. 新模型自动发现后的处理流程

当 Kimi 发布新模型时，管理员不需要先手工创建模型目录。

标准流程：

```text
1. Kimi 官方价格页或独立定价子页面出现新模型
2. 每日价格同步抓取并解析新模型价格
3. 系统生成 HIGH 风险“新增模型”价格差异
4. 系统创建 PRICE_ONLY 模型候选
5. 管理员核验并批准官方首价
6. Kimi 渠道每 6 小时调用 /v1/models
7. 账号真实返回新模型后创建渠道模型部署
8. 正式价格自动匹配
9. 管理员执行 CHAT/STREAM 等真实探测
10. 探测通过后人工确认生产准入
11. 创建或更新路由策略
12. 发布企业服务模型或将新模型加入既有服务模型路由
13. Virtual Key 仍调用稳定企业服务模型名
```

禁止的捷径：

```text
价格页出现新模型
→ 直接创建生产路由
```

也禁止：

```text
models.dev / LiteLLM 出现价格
→ 自动替代 Kimi 官方价格
→ 直接进入生产
```

---

## 18. 完整验收清单

### 18.1 官方价格链路

- [ ] 内置 Kimi 价格源存在
- [ ] 官方域名仅包含 `platform.kimi.com`
- [ ] 测试获取返回 HTTP 200
- [ ] 测试解析标准化记录数大于 0
- [ ] 结构指纹非空
- [ ] 发现的子页面不包含 URL fragment
- [ ] Kimi 价格源已启用为 `ACTIVE`
- [ ] 同步周期为 `P1D`
- [ ] 首价或高风险变化已人工审核
- [ ] `moonshot + 目标模型 + CNY` 官方目录为 `ACTIVE`
- [ ] 价格完整性为 `COMPLETE`
- [ ] 计费基数为 `1000000`

### 18.2 候选和渠道发现链路

- [ ] 官方页发现的模型进入“模型候选”
- [ ] 仅官方页发现时状态为 `PRICE_ONLY`
- [ ] Moonshot / Kimi 渠道已创建
- [ ] API Base 为 `https://api.moonshot.cn/v1`
- [ ] Kimi 上游 Key 已安全托管
- [ ] 渠道连接测试成功
- [ ] 渠道 `/v1/models` 返回目标模型
- [ ] 渠道模型部署为 `DISCOVERED`
- [ ] 候选状态可变为 `CHANNEL_VERIFIED`

### 18.3 价格、探测和生产准入

- [ ] 模型部署价格状态为正式价格已匹配
- [ ] 有效成本价格层正确
- [ ] CHAT 真实探测通过
- [ ] 技术健康为 `HEALTHY`
- [ ] 最近探测为 `PASSED`
- [ ] 管理员已填写生产批准说明
- [ ] 生产准入为 `APPROVED`
- [ ] 路由资格为 `ELIGIBLE`

### 18.4 服务模型与路由

- [ ] 企业服务模型草稿 `kimi-enterprise` 已创建
- [ ] 路由候选为 Kimi 渠道 + 真实发现模型
- [ ] 路由价格版本有效
- [ ] 路由已校验并生效
- [ ] 企业服务模型绑定 ACTIVE 路由
- [ ] 企业服务模型已发布

### 18.5 租户与 Virtual Key

- [ ] 租户可用服务模型包含 `kimi-enterprise`
- [ ] 租户、项目和应用均已启用
- [ ] 应用级 Key 允许调用的服务模型包含 `kimi-enterprise`
- [ ] Virtual Key 已生成并安全保存
- [ ] `/v1/models` 返回 `kimi-enterprise`
- [ ] `/v1/chat/completions` 调用成功

### 18.6 运营闭环

- [ ] usage_record 已写入
- [ ] request_attempt 已写入
- [ ] 成本快照已写入
- [ ] Token 用量非空
- [ ] 价格版本和价格层非空
- [ ] 成本金额非空
- [ ] 预算状态正常
- [ ] 记账状态正常
- [ ] 内部成本单可归因
- [ ] 操作审计完整
- [ ] 无阻断性告警

全部完成后，才可以认定：

```text
Kimi 官方价格自动同步
→ 新模型候选
→ 渠道真实发现
→ 正式价格匹配
→ 真实能力探测
→ 人工生产准入
→ 路由和企业服务模型
→ Virtual Key
→ 真实调用、用量和成本闭环
```

已经打通。

---

## 19. 常见问题与处理方法

| 现象 | 主要原因 | 处理方式 |
|---|---|---|
| Kimi 价格源测试获取 200，但解析为 0 | 普通 HTTP 没有渲染动态表格，或页面结构变化 | 切换 `HEADLESS`，检查 Headless Fetcher 和解析警告 |
| Headless Fetcher 返回 401 | Control Plane 与 Fetcher 内部令牌不一致 | 检查内部环境变量，不要输出令牌 |
| Headless Fetcher 返回 400 | 目标地址不在官方域名白名单或解析到非公网地址 | 检查来源地址和 `officialHosts` |
| Headless Fetcher 返回 502/504 | 页面渲染、Egress Proxy 或上游网络失败 | 检查 Fetcher、Egress Proxy 和官方站点 |
| 价格解析把模型版本数字当成价格 | 解析器版本过旧 | 升级当前 Kimi 适配器并重新测试解析 |
| 价格源发现大量 `#fragment` 子页面 | 未执行 V24 或代码版本过旧 | 升级到 V24，确认 fragment 来源已禁用 |
| 首轮同步为 REVIEW_REQUIRED | 新模型首价属于高风险 | 在“价格差异审核”核对后批准 |
| 公共参考有价格但官方目录为空 | 公共参考不能替代生产价格 | 等待官方同步或人工核验官方证据 |
| 模型候选为 PRICE_ONLY | 只有价格页证据，没有渠道发现 | 创建或选择 Kimi 渠道并执行发现模型 |
| `/v1/models` 没有目标模型 | 当前账号未开放、区域不同或模型已变化 | 使用账号实际返回模型，不要伪造部署 |
| 模型部署已发现但价格缺失 | 模型名、区域、调用模式或别名不匹配 | 检查官方目录、别名审核和有效成本价格 |
| 真实探测失败 | Key、余额、权限、模型、参数或网络异常 | 查看能力验证记录和供应商错误 |
| 探测通过但仍不可路由 | 尚未人工确认生产准入 | 点击“确认进入生产”并填写理由 |
| 路由生效失败 | 部署未 APPROVED/ELIGIBLE、价格无效或渠道异常 | 逐项检查部署四维状态和有效价格 |
| Key 允许调用的模型超出租户授权池 | 租户可用服务模型没有 `kimi-enterprise` | 先修改租户可用服务模型，或从 Key 中移除该模型 |
| `/v1/models` 返回 401 | 使用了错误 Key | 使用完整 TokenSea `ts_...` Virtual Key |
| `/v1/models` 不含 `kimi-enterprise` | 服务模型未发布、可见范围或 Key 范围错误 | 检查企业服务模型、租户和 Key 范围 |
| Chat 返回 502/503 | 上游、Runtime Core、路由或出口异常 | 使用 request_id 查看调用链和渠道日志 |
| 调用成功但成本为空 | 未绑定有效价格版本或成本快照写入失败 | 检查路由价格、request_attempt 和成本快照 |
| Kimi 扩展参数报错 | `thinking` 等参数传递方式不兼容 | 先移除扩展字段，验证标准调用 |

---

## 20. 最小可执行路径

在 TokenSea 服务正常启动的前提下，最快路径为：

```text
1. 高级治理 → 价格源管理，找到 Kimi 中国站官方价格
2. 检查官方域名、CNY、P1D 和 HEADLESS/AUTO 配置
3. 执行测试获取
4. 执行测试解析，确认标准化记录大于 0、结构指纹正常
5. 启用价格源并立即同步
6. 在价格差异审核中批准目标模型官方首价
7. 在供应商官方价格中确认 ACTIVE 且价格完整
8. 在模型候选中确认目标模型出现为 PRICE_ONLY
9. 创建 Moonshot / Kimi 供应商渠道
10. 托管 Kimi 上游 API Key
11. 执行连接测试
12. 执行发现模型，确认账号真实返回目标模型
13. 在模型部署中确认 DISCOVERED 和正式价格已匹配
14. 执行 CHAT 真实探测
15. 探测通过后点击“确认进入生产”
16. 确认部署 APPROVED + ELIGIBLE
17. 创建企业服务模型草稿 kimi-enterprise
18. 创建 Kimi 路由，选择渠道、实际模型和有效价格版本
19. 校验并生效路由
20. 绑定路由并发布 kimi-enterprise
21. 创建或选择启用的租户、项目和应用
22. 租户可用服务模型加入 kimi-enterprise
23. 在 API Key 页面创建应用级 Virtual Key
24. 生成并安全保存 ts_... 明文
25. 使用 Virtual Key 查询 /v1/models
26. 使用 kimi-enterprise 调用 /v1/chat/completions
27. 检查 usage_record、request_attempt、成本快照、预算、成本单和审计
```

---

## 21. 当前实现依据

### 21.1 价格与 Headless

- `services/headless-fetcher/app/main.py`
- `services/headless-fetcher/Dockerfile`
- `services/control-plane/src/main/java/com/tokensea/governance/ProviderPriceSyncService.java`
- `services/control-plane/src/main/java/com/tokensea/governance/pricing/adapter/KimiOfficialPriceAdapter.java`
- `services/control-plane/src/main/resources/db/migration/V23__qwen_kimi_official_pricing_and_model_discovery_governance.sql`
- `services/control-plane/src/main/resources/db/migration/V24__deduplicate_kimi_fragment_price_sources.sql`

### 21.2 模型发现与生产准入

- `services/control-plane/src/main/java/com/tokensea/governance/ModelDiscoveryController.java`
- `services/control-plane/src/main/java/com/tokensea/governance/CapabilityProbeService.java`
- `apps/console/src/config/resources.ts`
- `apps/console/src/config/menu.ts`

### 21.3 Virtual Key 和调用闭环

- `services/control-plane/src/main/java/com/tokensea/apikey/controller/ApiKeyController.java`
- `scripts/dev/verify-virtual-key.py`
- `apps/console/src/pages/Keys.vue`
- `apps/console/src/pages/Playground.vue`

---

## 22. Kimi 官方资料

- API 概述：<https://platform.kimi.com/docs/api/overview>
- 列出模型：<https://platform.kimi.com/docs/api/list-models>
- 模型列表：<https://platform.kimi.com/docs/models>
- Chat Completions：<https://platform.kimi.com/docs/api/chat>
- 模型推理价格：<https://platform.kimi.com/docs/pricing/chat>
- Kimi K2.6 定价：<https://platform.kimi.com/docs/pricing/chat-k26>
- Kimi K2.6 使用指南：<https://platform.kimi.com/docs/guide/kimi-k2-6-quickstart>

官方接口事实：

```text
API Base：https://api.moonshot.cn/v1
模型列表：GET /v1/models
对话接口：POST /v1/chat/completions
认证：Authorization: Bearer <MOONSHOT_API_KEY>
```

---

## 23. 安全与运维要求

1. Kimi 上游 API Key 只允许进入 TokenSea 凭据托管。
2. TokenSea Virtual Key 不得写入 Git、镜像、前端代码、公开日志或截图。
3. Headless Fetcher 只允许访问价格源声明的官方域名。
4. 生产环境不要把 Headless Fetcher 端口暴露到公网。
5. 官方价格同步失败时保留旧有效价格，不自动回退公共参考价。
6. 价格变化必须保存原始快照、结构指纹、证据哈希、审核人和版本。
7. 新模型首价、结构变化、币种变化和大幅调价必须人工审核。
8. 模型价格页出现不等于渠道可用，必须由具体渠道 `/v1/models` 验证。
9. 真实探测通过不等于生产批准，必须由管理员确认进入生产。
10. 模型恢复后必须重新探测和审核，不能自动恢复生产流量。
11. 生产 Virtual Key 必须设置合理预算、有效期、RPM、TPM、QPS 和 IP 白名单。
12. usage_record、request_attempt 和成本快照属于事实记录，不得原地修改或删除。
13. 供应商模型名、渠道模型部署、企业服务模型和 Virtual Key 模型范围不得混用。
