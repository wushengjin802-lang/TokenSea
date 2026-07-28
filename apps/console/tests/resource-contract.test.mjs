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

for (const contract of [
  'apiPath: "/api/model-deployment-governance"',
  '"QWEN_OFFICIAL_PAGE"',
  '"KIMI_OFFICIAL_PAGE"',
  '"XIAOMI_MIMO_OFFICIAL_PAGE"',
  '"ZHIPU_OFFICIAL_PAGE"',
  '测试解析: "POST_SHOW :id/test-parse"',
  '变更生产状态: "POST :id/production-transition"',
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
  '["Azure Retail Prices API", "AZURE_RETAIL_PRICES"]',
  '["AWS Price List Bulk", "AWS_PRICE_LIST_BULK"]',
  '["Google Cloud Billing Catalog", "GOOGLE_CLOUD_CATALOG"]',
  '["通用价格文档（HTML/CSV/JSON/PDF）", "GENERIC_DOCUMENT"]',
  'apiPath: "/api/provider-billing-sources"',
  'apiPath: "/api/provider-billing-sync-runs"',
  'apiPath: "/api/provider-billing-records"',
  '["OpenAI Costs API", "OPENAI_COSTS_API"]',
]) {
  assert.ok(resources.includes(contract), `资源契约缺失: ${contract}`)
}

for (const route of [
  "{path:'/model-discovery-candidates',title:'模型候选'}",
  "{path:'/provider-model-aliases',title:'模型别名审核'}",
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
assert.ok(dataPage.includes("type=\"datetime-local\""), '动作表单必须支持账单同步时间范围')
assert.ok(dataPage.includes('applySourceAutofill'), 'DataPage 必须支持选择业务模板后自动填充默认字段')
assert.ok(dataPage.includes('fieldDisabled(field)'), 'DataPage 必须支持模板驱动字段只读和不可变约束')
assert.ok(!menu.includes("title:'Playground'"), '开发者门户不应保留重复的 Playground 菜单')
assert.ok(router.includes("{path:'playground',redirect:'/quick-start'}"), '旧 Playground 路由必须兼容跳转到快速开始')
assert.ok(quickStart.includes('v-model.number="temperature"'), '快速开始必须包含 Playground 的 Temperature 调试参数')
assert.ok(quickStart.includes('v-model.number="maxTokens"'), '快速开始必须包含 Playground 的最大输出 Token 参数')
assert.ok(developerModels.includes('.developer-models-page .page-header'), '服务模型列表必须压缩标题区与内容卡片间距')
console.log('Console resource contracts passed')
