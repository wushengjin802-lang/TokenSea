import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const resources = readFileSync(resolve(here, '../src/config/resources.ts'), 'utf8')
const menu = readFileSync(resolve(here, '../src/config/menu.ts'), 'utf8')
const router = readFileSync(resolve(here, '../src/router.ts'), 'utf8')
const dataPage = readFileSync(resolve(here, '../src/pages/DataPage.vue'), 'utf8')
const quickStart = readFileSync(resolve(here, '../src/pages/QuickStart.vue'), 'utf8')
const developerModels = readFileSync(resolve(here, '../src/pages/DeveloperModels.vue'), 'utf8')
const extractionReview = readFileSync(resolve(here, '../src/pages/PriceExtractionReview.vue'), 'utf8')
const referencePriceOverview = readFileSync(resolve(here, '../src/pages/ReferencePriceOverview.vue'), 'utf8')
const landing = readFileSync(resolve(here, '../src/pages/Landing.vue'), 'utf8')
const prototypeCss = readFileSync(resolve(here, '../src/prototype.css'), 'utf8')

for (const contract of [
  'apiPath: "/api/model-deployment-governance"',
  '"QWEN_OFFICIAL_PAGE"',
  '"KIMI_OFFICIAL_PAGE"',
  '"XIAOMI_MIMO_OFFICIAL_PAGE"',
  '"ZHIPU_OFFICIAL_PAGE"',
  '测试解析: "POST_SHOW :id/test-parse"',
  '变更生产状态: "POST :id/production-transition"',
  '查看参考价格: "GET :id/reference-price"',
  'referencePriceStatus: "参考价格"',
  '["官方参考价", "OFFICIAL_REFERENCE"]',
  '["厂商参考价", "VENDOR_REFERENCE"]',
  '["聚合参考价", "AGGREGATOR_REFERENCE"]',
  '["内置参考价", "BUNDLED_REFERENCE"]',
  '["暂无参考价", "MISSING_REFERENCE"]',
  'referenceMatchType: "参考价匹配类型"',
  'referenceMatchConfidence: "匹配置信度"',
  'referenceMatchReason: "匹配依据"',
  '["确认进入生产", "APPROVE"]',
  '["拒绝进入生产", "REJECT"]',
  '["暂停生产", "SUSPEND"]',
  'apiPath: "/api/model-discovery-candidates"',
  'apiPath: "/api/provider-model-aliases"',
  '批准: ":id/approve"',
  '拒绝: ":id/reject"',
  'title: "连接测试详情"',
  'refreshOnErrorActions: ["连接测试"]',
  'lastConnectionTestError: "异常原因"',
  'autofill: { apiStyle: "protocol", apiBase: "defaultApiBase" }',
  'readonlyFields: ["apiStyle"]',
  'immutableFields: ["providerTemplateId"]',
  '撤销发布: ":id/revoke"',
  '["已撤销", "REVOKED"]',
  'connectorCode: "连接器"',
  'apiPath: "/api/provider-price-mappings"',
  'apiPath: "/api/provider-price-unmapped-records"',
  'credentialPurpose: "凭据用途"',
  'credentialRef: "价格只读凭据"',
  'documentType: "文档类型"',
  'extractionMode: "提取模式"',
  'minimumConfidence: "最低置信度"',
  'requireManualReview: "强制人工审核"',
  'apiPath: "/api/provider-billing-snapshots"',
  '["Azure Retail Prices API", "AZURE_RETAIL_PRICES"]',
  '["AWS Price List Bulk", "AWS_PRICE_LIST_BULK"]',
  '["Google Cloud Billing Catalog", "GOOGLE_CLOUD_CATALOG"]',
  '["通用价格文档（HTML/CSV/JSON/PDF）", "GENERIC_DOCUMENT"]',
  'advancedFormLabel: "采集与治理高级配置"',
  'path: "/api/provider-price-connectors/provider-options"',
  'endpoint: "endpoint"',
  'officialHosts: "officialHosts"',
  'apiPath: "/api/provider-billing-sources"',
  'apiPath: "/api/provider-billing-sync-runs"',
  'apiPath: "/api/provider-billing-records"',
  '["OpenAI Costs API", "OPENAI_COSTS_API"]',
]) {
  assert.ok(resources.includes(contract), `资源契约缺失: ${contract}`)
}

for (const route of [
  "{path:'/reference-prices',title:'参考价格状态'}",
  "{path:'/provider-billing-snapshots',title:'账单原始快照'}",
  "{path:'/provider-billing-sources',title:'供应商账单源'}",
  "{path:'/provider-billing-sync-runs',title:'账单同步任务'}",
  "{path:'/provider-billing-records',title:'供应商账单明细'}",
]) {
  assert.ok(menu.includes(route), `菜单契约缺失: ${route}`)
}

assert.ok(dataPage.includes('POST_SHOW'), 'DataPage 必须支持 POST_SHOW 结果展示动作')
assert.ok(dataPage.includes('refreshOnErrorActions'), 'DataPage 必须支持动作失败后刷新最新状态')
assert.ok(dataPage.includes('action === "撤销发布"'), '价格差异必须支持已发布记录撤销')
assert.ok(dataPage.includes('applyFieldPreset'), 'DataPage 必须支持适配器选择后的安全默认配置')
assert.ok(dataPage.includes('formDisplayFields'), 'DataPage 必须支持基础配置与高级配置分组')
assert.ok(dataPage.includes('展开${advancedFormLabel}'), 'DataPage 必须提供高级配置折叠入口')
assert.ok(dataPage.includes('endpointHostAutofilled'), 'DataPage 必须从官方来源地址安全推荐官方域名')
assert.ok(dataPage.includes('config.llmEnabled = form.extractionMode === "DETERMINISTIC_LLM"'), '通用文档的 LLM 提取模式必须同步 llmEnabled')
assert.ok(dataPage.includes('nameManuallyEdited'), '供应商推荐名称不得覆盖用户手工修改')
assert.ok(resources.includes('editableFields: [\n      "providerType",\n      "name",'), '价格源表单必须先选择供应商类型再填写名称')
assert.ok(dataPage.includes("type=\"datetime-local\""), '动作表单必须支持账单同步时间范围')
assert.ok(dataPage.includes('applySourceAutofill'), 'DataPage 必须支持选择业务模板后自动填充默认字段')
assert.ok(dataPage.includes('fieldDisabled(field)'), 'DataPage 必须支持模板驱动字段只读和不可变约束')
assert.ok(!menu.includes("title:'Playground'"), '开发者门户不应保留重复的 Playground 菜单')
assert.ok(!menu.includes("path:'/model-discovery-candidates'"), '模型候选属于后台中间记录，不应展示在日常模型配置菜单')
assert.ok(!menu.includes("path:'/provider-model-aliases'"), '模型别名审核属于异常治理，不应展示在日常模型配置菜单')
assert.ok(!menu.includes("path:'/provider-price-sources'"), '自动参考价格模式下不应展示人工价格源管理入口')
assert.ok(!menu.includes("path:'/price-document-extractions'"), '自动参考价格模式下不应展示日常文档提取审核入口')
assert.ok(!menu.includes("path:'/provider-price-mappings'"), '自动参考价格模式下不应展示日常映射配置入口')
assert.ok(!menu.includes("path:'/provider-price-diffs'"), '公共参考价不应要求逐模型价格差异审核')
assert.ok(!menu.includes("path:'/price-versions'"), '公共参考价模式下不应要求逐模型维护生效价格')
assert.ok(router.includes("{path:'playground',redirect:'/quick-start'}"), '旧 Playground 路由必须兼容跳转到快速开始')
assert.ok(quickStart.includes('v-model.number="temperature"'), '快速开始必须包含 Playground 的 Temperature 调试参数')
assert.ok(quickStart.includes('v-model.number="maxTokens"'), '快速开始必须包含 Playground 的最大输出 Token 参数')
assert.ok(quickStart.includes('正在发送真实请求，请等待网关响应'), '快速开始请求中必须展示与真实状态一致的网关响应文案')
assert.ok(quickStart.includes('requestLoading" class="status warn">请求中'), '快速开始网关响应区必须展示请求中状态')
assert.ok(developerModels.includes('.developer-models-page .page-header'), '服务模型列表必须压缩标题区与内容卡片间距')
assert.ok(dataPage.includes('actionPayload.secretPurpose = "INFERENCE"'), '托管供应商密钥必须显式区分密钥用途')
assert.ok(router.includes("{path:'price-document-extractions',component:PriceExtractionReview,meta:admin}"), '价格文档提取审核必须注册管理员路由')
assert.ok(router.includes("{path:'reference-prices',component:ReferencePriceOverview,meta:admin}"), '参考价格状态必须注册管理员路由')
assert.ok(referencePriceOverview.includes('/api/reference-prices/overview'), '参考价格状态页必须读取自动价格总览')
assert.ok(referencePriceOverview.includes('/api/reference-prices/sources'), '参考价格状态页必须展示系统自动来源')
assert.ok(referencePriceOverview.includes('不作为实际结算依据'), '参考价格页面必须明确非结算口径')
assert.ok(resources.includes('暂无参考价不影响模型生产准入'), '模型部署页必须明确参考价缺失不阻断生产')
assert.ok(!resources.includes('有效成本价格: "GET :id/effective-cost-price"'), '模型部署页不应再将正式成本价格作为日常操作')
assert.ok(resources.includes('builtinActions: ["配置路由", "发布检查", "提交审批", "发布"]'), '企业服务模型必须在当前页面提供路由配置入口')
assert.ok(resources.includes('editableFields: [\n      "platformModelName",\n      "displayName",\n      "providerInstanceIds",\n      "actualModels",\n      "visibilityScope",\n      "approvalRequired",\n    ]'), '企业服务模型草稿表单不应要求先选择尚不存在的路由策略')
assert.ok(dataPage.includes('/api/platform-models/${row.id}/route-draft'), '企业服务模型必须自动确保并绑定路由草稿')
assert.ok(dataPage.includes('saveRouteConfiguration(true)'), '企业服务模型必须支持在当前页面保存并生效路由')
assert.ok(dataPage.includes('/route-policy`, {'), '新路由生效后必须自动绑定回企业服务模型')
assert.ok(dataPage.includes('当前已发布模型仍使用原生效路由'), '仅保存新路由草稿不得中断已发布模型调用')
assert.ok(dataPage.includes('草稿已保存，继续配置路由'), '新建企业服务模型后必须直接进入路由配置流程')
assert.ok(!referencePriceOverview.includes('新建价格源'), '参考价格状态页不应要求管理员新建价格源')
assert.ok(referencePriceOverview.includes('source-table-scroll'), '自动价格来源必须使用独立数据滚动区')
assert.ok(referencePriceOverview.includes('model-table-scroll'), '模型参考价格必须使用独立数据滚动区')
assert.ok(referencePriceOverview.includes('position: sticky'), '自动价格来源表头必须在数据滚动时保持固定')
assert.ok(referencePriceOverview.includes(':scroll="modelTableScroll"'), '当前模型参考价格必须使用表格原生固定表头滚动')
assert.ok(referencePriceOverview.includes('.ant-table-body'), '当前模型参考价格滚动条必须仅作用于数据行区域')
assert.ok(referencePriceOverview.includes('overflow: hidden;'), '当前模型参考价格外层容器不得整体滚动')
assert.ok(extractionReview.includes("/api/price-document-extracted-records/${reviewRecord.value.id}/review"), '审核工作台必须调用记录级审核接口')
assert.ok(extractionReview.includes("/api/price-document-extraction-runs/${selectedRun.value.id}/submit"), '审核工作台必须调用抽取运行提交接口')
assert.ok(extractionReview.includes('sourceText'), '审核工作台必须展示原始证据文本')
const usageAnalysis = readFileSync(resolve(here, '../src/pages/UsageAnalysis.vue'), 'utf8')
for (const scrollContainer of ['.donut-legend', '.horizontal-bars', '.tenant-ranking', '.project-bars', '.ranking-table']) {
  assert.ok(usageAnalysis.includes(scrollContainer), `用量分析排行容器缺失: ${scrollContainer}`)
}
assert.ok(usageAnalysis.includes('overflow-y: auto;'), '用量分析排行型卡片必须支持内部垂直滚动')
assert.ok(usageAnalysis.includes('position: sticky;\n  top: 0;'), 'Virtual Key 排名表头必须在内部滚动时保持固定')
assert.ok(
  landing.includes('<button v-if="loggedIn" class="landing-console-button" type="button" @click="openConsole">控制台</button>'),
  '首页“控制台”按钮只能在登录后显示',
)
assert.ok(!landing.includes('>进入控制台</button>'), '首页控制台按钮文案必须精简为“控制台”')
for (const anchor of ['href="#product-capabilities"', 'href="#solutions"', 'href="#developer-docs"']) {
  assert.ok(landing.includes(anchor), `首页导航必须使用页内锚点: ${anchor}`)
}
assert.ok(landing.includes('id="developer-docs"'), '首页开发者文档区必须提供页内锚点')
assert.ok(prototypeCss.includes('.landing-nav{position:sticky;top:0;'), '首页顶部导航必须在滚动后保持可见')
assert.ok(prototypeCss.includes('#product-capabilities,#solutions,#developer-docs{scroll-margin-top:'), '首页页内锚点必须避开固定导航栏')
console.log('Console resource contracts passed')
