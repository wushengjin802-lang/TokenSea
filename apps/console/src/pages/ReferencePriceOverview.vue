<template>
  <main class="reference-price-page">
    <header class="page-header">
      <div>
        <h1>参考价格状态</h1>
        <p>平台部署后自动导入并每日更新公开参考价格，仅用于模型选型和成本估算，不作为实际结算依据。</p>
      </div>
      <a-button :loading="loading" @click="loadAll">刷新</a-button>
    </header>

    <section class="metric-grid">
      <article class="metric-card">
        <span>公共模型</span><strong>{{ number(overview.modelCount) }}</strong>
      </article>
      <article class="metric-card">
        <span>已有参考价</span><strong>{{ number(overview.pricedModelCount) }}</strong>
      </article>
      <article class="metric-card">
        <span>价格覆盖率</span><strong>{{ percent(overview.coverageRatio) }}</strong>
      </article>
      <article class="metric-card">
        <span>系统来源</span><strong>{{ number(overview.sourceCount) }}</strong>
      </article>
      <article class="metric-card">
        <span>过期记录</span><strong>{{ number(overview.staleCount) }}</strong>
      </article>
      <article class="metric-card">
        <span>最近成功更新</span><strong class="time-value">{{ dateTime(overview.lastSuccessAt) }}</strong>
      </article>
    </section>

    <section class="panel">
      <div class="panel-title">
        <div>
          <h2>自动价格来源</h2>
        </div>
        <a-tag :color="overview.usingBootstrapSnapshot ? 'gold' : 'green'">
          {{ overview.usingBootstrapSnapshot ? '包含离线快照' : '在线参考价' }}
        </a-tag>
      </div>
      <div class="source-table-scroll">
        <a-table
          size="small"
          row-key="id"
          :loading="loading"
          :data-source="sources"
          :pagination="false"
          :scroll="{ x: 980 }"
        >
        <a-table-column title="来源" data-index="name" :width="220" />
        <a-table-column title="管理方式" :width="100">
          <template #default>系统自动</template>
        </a-table-column>
        <a-table-column title="状态" :width="100">
          <template #default="{ record }">
            <a-tag :color="sourceStatusColor(record.status)">{{ sourceStatus(record.status) }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="模型数" data-index="modelCount" :width="90" />
        <a-table-column title="过期" data-index="staleCount" :width="80" />
        <a-table-column title="最近成功" :width="180">
          <template #default="{ record }">{{ dateTime(record.lastGoodSyncAt || record.lastSuccessAt) }}</template>
        </a-table-column>
        <a-table-column title="下次同步" :width="180">
          <template #default="{ record }">{{ dateTime(record.nextRunAt) }}</template>
        </a-table-column>
        <a-table-column title="最近结果" :width="110">
          <template #default="{ record }">{{ runStatus(record.lastRunStatus) }}</template>
        </a-table-column>
        <a-table-column title="操作" fixed="right" :width="100">
          <template #default="{ record }">
            <a-button
              v-if="record.adapterCode !== 'BUNDLED_REFERENCE'"
              size="small"
              :loading="retrying === record.id"
              @click="retry(record)"
            >立即重试</a-button>
            <span v-else class="muted">随版本更新</span>
          </template>
        </a-table-column>
        </a-table>
      </div>
    </section>

    <section class="panel model-panel">
      <div class="panel-title model-toolbar">
        <div>
          <h2>当前模型参考价格</h2>
        </div>
        <a-input-search
          v-model:value="keyword"
          allow-clear
          placeholder="搜索供应商或模型"
          style="width: 280px"
          @search="searchModels"
        />
      </div>
      <div ref="modelTableViewport" class="model-table-scroll">
        <a-table
          size="small"
          row-key="id"
          :loading="modelLoading"
          :data-source="models"
          :pagination="false"
          :scroll="modelTableScroll"
        >
          <a-table-column title="供应商" data-index="providerType" :width="180" />
          <a-table-column title="模型" data-index="providerModelName" :width="250" />
          <a-table-column title="输入参考价" :width="140">
            <template #default="{ record }">{{ price(record.inputUnitPrice, record.currency) }}</template>
          </a-table-column>
          <a-table-column title="输出参考价" :width="140">
            <template #default="{ record }">{{ price(record.outputUnitPrice, record.currency) }}</template>
          </a-table-column>
          <a-table-column title="计费单位" :width="150">
            <template #default="{ record }">{{ billingUnit(record) }}</template>
          </a-table-column>
          <a-table-column title="区域" data-index="region" :width="90" />
          <a-table-column title="来源" data-index="sourceName" :width="210" />
          <a-table-column title="价格状态" :width="100">
            <template #default="{ record }">
              <a-tag :color="record.priceStatus === 'CURRENT' ? 'green' : 'gold'">
                {{ record.priceStatus === 'CURRENT' ? '当前' : '可能过期' }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="更新时间" :width="180">
            <template #default="{ record }">{{ dateTime(record.observedAt) }}</template>
          </a-table-column>
        </a-table>
      </div>
      <div class="pager">
        <span>共 {{ total }} 条</span>
        <a-pagination
          v-model:current="page"
          :page-size="pageSize"
          :total="total"
          :show-size-changer="false"
          @change="loadModels"
        />
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { errorMessage, get, postAction, queryPage } from '../api/client'

const loading = ref(false)
const modelLoading = ref(false)
const retrying = ref('')
const overview = ref<Record<string, any>>({})
const sources = ref<any[]>([])
const models = ref<any[]>([])
const keyword = ref('')
const page = ref(1)
const pageSize = 20
const total = ref(0)
const modelTableViewport = ref<HTMLElement>()
const modelTableBodyHeight = ref<number>()
let modelTableResizeObserver: ResizeObserver | undefined

const modelTableScroll = computed(() => modelTableBodyHeight.value
  ? { x: 1330, y: modelTableBodyHeight.value }
  : { x: 1330 })

async function loadAll() {
  loading.value = true
  try {
    const [summary, sourceRows] = await Promise.all([
      get<Record<string, any>>('/api/reference-prices/overview'),
      get<any[]>('/api/reference-prices/sources'),
      loadModels(),
    ])
    overview.value = summary || {}
    sources.value = sourceRows || []
  } catch (error) {
    message.error(errorMessage(error, '读取参考价格状态'))
  } finally {
    loading.value = false
  }
}

async function loadModels() {
  modelLoading.value = true
  try {
    const result = await queryPage<any>('/api/reference-prices/models', {
      page: page.value,
      size: pageSize,
      keyword: keyword.value || undefined,
      sort: 'updatedAt',
      order: 'desc',
    })
    models.value = result.items
    total.value = result.total
  } catch (error) {
    message.error(errorMessage(error, '读取模型参考价格'))
  } finally {
    modelLoading.value = false
  }
}

function searchModels() {
  page.value = 1
  loadModels()
}

async function retry(source: any) {
  retrying.value = source.id
  try {
    await postAction(`/api/reference-prices/sources/${source.id}/retry`)
    message.success('已加入后台重试队列')
    await loadAll()
  } catch (error) {
    message.error(errorMessage(error, '重试参考价格来源'))
  } finally {
    retrying.value = ''
  }
}

function number(value: unknown) {
  return Number(value || 0).toLocaleString('zh-CN')
}
function percent(value: unknown) {
  return `${(Number(value || 0) * 100).toFixed(1)}%`
}
function dateTime(value: unknown) {
  if (!value) return '—'
  const date = new Date(String(value))
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false })
}
function price(value: unknown, currency: string) {
  if (value === null || value === undefined || value === '') return '暂无'
  return `${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 8 })} ${currency || ''}`.trim()
}
function billingUnit(record: any) {
  if (record.billingBasis === 'TOKEN' && Number(record.billingQuantity) === 1_000_000) return '每百万 Token'
  return `${record.billingQuantity || 1} ${record.billingBasis || ''}`.trim()
}
function sourceStatus(value: string) {
  return ({ ACTIVE: '正常', DEGRADED: '降级', PAUSED: '暂停', DISABLED: '停用' } as Record<string, string>)[value] || value || '未知'
}
function sourceStatusColor(value: string) {
  return ({ ACTIVE: 'green', DEGRADED: 'gold', PAUSED: 'default', DISABLED: 'red' } as Record<string, string>)[value] || 'default'
}
function runStatus(value: string) {
  return ({ SUCCEEDED: '成功', NO_CHANGE: '无变化', FAILED: '失败', PENDING: '等待中', RUNNING: '执行中' } as Record<string, string>)[value] || value || '未执行'
}

function updateModelTableBodyHeight() {
  if (window.innerWidth < 981 || !modelTableViewport.value) {
    modelTableBodyHeight.value = undefined
    return
  }
  modelTableBodyHeight.value = Math.max(120, Math.floor(modelTableViewport.value.clientHeight - 40))
}

onMounted(async () => {
  await loadAll()
  await nextTick()
  updateModelTableBodyHeight()
  modelTableResizeObserver = new ResizeObserver(updateModelTableBodyHeight)
  if (modelTableViewport.value) modelTableResizeObserver.observe(modelTableViewport.value)
  window.addEventListener('resize', updateModelTableBodyHeight)
})

onBeforeUnmount(() => {
  modelTableResizeObserver?.disconnect()
  window.removeEventListener('resize', updateModelTableBodyHeight)
})
</script>

<style scoped>
.reference-price-page { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
h1 { margin: 0; font-size: 28px; font-weight: 600; }
h2 { margin: 0; font-size: 16px; line-height: 22px; }
p { margin: 5px 0 0; color: #64748b; }
.metric-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 10px; }
.metric-card { min-height: 94px; padding: 16px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; display: flex; flex-direction: column; justify-content: space-between; }
.metric-card span { color: #64748b; font-size: 13px; }
.metric-card strong { color: #0f172a; font-size: 24px; font-weight: 650; }
.metric-card .time-value { font-size: 14px; line-height: 1.5; }
.panel { border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; overflow: hidden; }
.panel-title { position: relative; z-index: 3; min-height: 44px; padding: 8px 14px; border-bottom: 1px solid #e2e8f0; background: #fff; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-title p { font-size: 12px; }
.model-toolbar { align-items: center; }
.source-table-scroll { max-height: 176px; overflow: auto; }
.model-panel { display: flex; min-height: 0; flex: 1 1 auto; flex-direction: column; }
.model-table-scroll { min-height: 0; flex: 1 1 auto; overflow: hidden; }
.source-table-scroll :deep(.ant-table-thead > tr > th) { position: sticky; top: 0; z-index: 2; background: #fafafa; }
.model-table-scroll :deep(.ant-table-header) { overflow: hidden !important; }
.model-table-scroll :deep(.ant-table-body) { overflow-y: auto !important; scrollbar-gutter: stable; }
.pager { display: flex; align-items: center; justify-content: flex-end; gap: 16px; padding: 12px 16px; border-top: 1px solid #e2e8f0; color: #64748b; }
.muted { color: #94a3b8; font-size: 12px; }
@media (min-width: 981px) {
  .reference-price-page { height: calc(100vh - 136px); height: calc(100dvh - 136px); overflow: hidden; }
  .reference-price-page > :not(.model-panel) { flex: 0 0 auto; }
}
@media (max-width: 1300px) { .metric-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
</style>
