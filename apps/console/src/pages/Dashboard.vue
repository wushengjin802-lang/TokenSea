<template>
  <section class="page dashboard-page">
    <header class="page-header dashboard-header">
      <div class="dashboard-heading">
        <div class="breadcrumb">工作台</div>
        <div class="dashboard-title-row">
          <h1 class="page-title">企业模型 API 运行总览</h1>
          <span :class="['dashboard-state-badge', loading ? 'neutral' : operationSummary.tone]">{{ loading ? '加载中' : operationSummary.badge }}</span>
        </div>
        <p class="page-desc">集中查看平台资源、调用质量、渠道健康与待处理风险，异常项可直接下钻到对应治理页面。</p>
      </div>
      <div class="header-actions">
        <router-link class="btn" to="/alerts">告警中心</router-link>
        <router-link class="btn primary" to="/keys">管理 API Key</router-link>
        <button class="btn" :disabled="loading" @click="load">刷新</button>
      </div>
    </header>

    <div v-if="error" class="state-panel error-state" role="alert">
      <strong>概览加载失败</strong>
      <p>{{ error }}</p>
      <button class="btn" @click="load">重试</button>
    </div>
    <div v-else-if="loading" class="state-panel">
      <span class="loading-mark"></span>
      <strong>正在读取平台指标</strong>
    </div>

    <template v-else>
      <section class="dashboard-overview card">
        <div class="overview-lead">
          <span class="dashboard-badge">运行态摘要</span>
          <h2>{{ operationSummary.title }}</h2>
          <p>{{ operationSummary.description }}</p>
          <div class="overview-links">
            <router-link to="/logs">查看调用日志</router-link>
            <router-link to="/usage">分析用量</router-link>
            <router-link to="/provider-health">检查渠道</router-link>
          </div>
        </div>
        <div class="overview-insights">
          <div v-for="item in derivedIndicators" :key="item.label" class="overview-insight">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
            <em>{{ item.note }}</em>
          </div>
        </div>
      </section>

      <section class="metric-grid dashboard-metrics">
        <article v-for="item in metrics" :key="item.key" :class="['metric-card', item.tone]">
          <div class="metric-card-head">
            <span>{{ item.label }}</span>
            <i>{{ item.scope }}</i>
          </div>
          <strong>{{ formatMetric(item) }}</strong>
          <em>{{ item.note }}</em>
        </article>
      </section>

      <section class="dashboard-content-grid">
        <article class="card dashboard-runtime-panel">
          <div class="card-title dashboard-card-title">
            <div>
              <strong>运行态与资源覆盖</strong>
              <span>渠道、模型、告警与服务入口的汇总状态</span>
            </div>
            <router-link class="btn small" to="/model-deployments">模型部署</router-link>
          </div>

          <div class="runtime-status-grid">
            <router-link
              v-for="item in runtimeSummary"
              :key="item.label"
              :class="['runtime-status-card', item.tone]"
              :to="item.path"
            >
              <div class="runtime-status-head">
                <span>{{ item.label }}</span>
                <i>{{ item.status }}</i>
              </div>
              <strong>{{ formatNumber(item.value) }}</strong>
              <em>{{ item.note }}</em>
            </router-link>
          </div>

          <div class="dashboard-quick-section">
            <div class="dashboard-section-label">常用操作</div>
            <div class="dashboard-quick-grid">
              <router-link v-for="item in quickActions" :key="item.path" class="quick-action" :to="item.path">
                <strong>{{ item.title }}</strong>
                <span>{{ item.desc }}</span>
                <i>进入</i>
              </router-link>
            </div>
          </div>
        </article>

        <div class="dashboard-side-stack">
          <article class="card dashboard-list-card">
            <div class="card-title dashboard-card-title">
              <div>
                <strong>待处理风险</strong>
                <span>展示未关闭的非信息类告警</span>
              </div>
              <router-link class="text-link" to="/alerts">全部告警</router-link>
            </div>
            <div v-if="openAlerts.length" class="task-group-list risk-alert-list">
              <div v-for="row in openAlerts" :key="row.id" class="risk-alert-row">
                <time>{{ formatDateTime(row.createdAt || row.created_at) || '—' }}</time>
                <span class="risk-alert-type">{{ resourceTypeLabel(row.resourceType) }}</span>
                <em :class="severityClass(row.severity)">{{ severityLabel(row.severity) }}</em>
                <strong :title="row.title || '未命名告警'">{{ row.title || '未命名告警' }}</strong>
              </div>
            </div>
            <div v-else class="state-panel compact-state empty-state">{{ alertError || '当前没有未关闭告警' }}</div>
          </article>

          <article class="card dashboard-list-card">
            <div class="card-title dashboard-card-title">
              <div>
                <strong>渠道健康</strong>
                <span>最近连接测试与可用状态</span>
              </div>
              <router-link class="text-link" to="/provider-health">查看全部</router-link>
            </div>
            <div v-if="providerRows.length" class="summary-list provider-summary-list">
              <div v-for="row in providerRows" :key="row.id">
                <strong>{{ row.instanceName || row.name || row.id }}</strong>
                <span :class="providerStatusClass(row)"><i></i>{{ providerStatusLabel(row) }}</span>
              </div>
            </div>
            <div v-else class="state-panel compact-state empty-state">当前没有渠道健康记录</div>
          </article>
        </div>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { errorMessage, get, list } from '../api/client'
import { formatDateTime } from '../format'
import { stableSortRows } from '../listSort'

const stats = ref<Record<string, any>>({})
const providers = ref<any[]>([])
const alerts = ref<any[]>([])
const loading = ref(false)
const error = ref('')
const alertError = ref('')

const requestCount = computed(() => Number(stats.value.requests || 0))
const errorCount = computed(() => Number(stats.value.errors || 0))
const providerCount = computed(() => Number(stats.value.providers || 0))
const healthyProviderCount = computed(() => Number(stats.value.providerHealth || 0))
const tokenCount = computed(() => Number(stats.value.tokens || 0))
const successRate = computed(() => requestCount.value > 0 ? Math.max(0, ((requestCount.value - errorCount.value) / requestCount.value) * 100) : 100)
const providerHealthRate = computed(() => providerCount.value > 0 ? (healthyProviderCount.value / providerCount.value) * 100 : 0)
const averageTokens = computed(() => requestCount.value > 0 ? tokenCount.value / requestCount.value : 0)

const metrics = computed(() => [
  { key: 'tenants', label: '租户', scope: '资源', note: '已登记组织单元', tone: 'neutral', compact: false },
  { key: 'providers', label: '供应商渠道', scope: '资源', note: '已登记渠道总数', tone: healthyProviderCount.value < providerCount.value ? 'warning' : 'neutral', compact: false },
  { key: 'models', label: '平台模型', scope: '资源', note: '可管理模型记录', tone: 'neutral', compact: false },
  { key: 'keys', label: 'Virtual Key', scope: '访问', note: '已创建平台密钥', tone: 'neutral', compact: false },
  { key: 'requests', label: '累计请求', scope: '调用', note: '真实调用记录', tone: 'accent', compact: true },
  { key: 'errors', label: '错误请求', scope: '调用', note: errorCount.value > 0 ? '需要结合调用日志排查' : '当前无错误记录', tone: errorCount.value > 0 ? 'danger' : 'success', compact: true },
  { key: 'tokens', label: '累计 Token', scope: '用量', note: '输入与输出 Token 汇总', tone: 'accent', compact: true },
  { key: 'providerHealth', label: '健康渠道', scope: '运行', note: '连接测试仍在有效期内', tone: healthyProviderCount.value < providerCount.value ? 'warning' : 'success', compact: false },
])

const unresolvedAlerts = computed(() => alerts.value.filter(
  (row) => !['RESOLVED', '已解决', 'CLOSED', '已关闭'].includes(String(row.status || '').toUpperCase()),
))
const openAlertCount = computed(() => unresolvedAlerts.value.length)
const openAlerts = computed(() => stableSortRows(unresolvedAlerts.value, 'createdAt', 'desc'))
const providerRows = computed(() => stableSortRows(providers.value, 'id', 'asc').slice(0, 6))

const operationSummary = computed(() => {
  if (openAlertCount.value > 0 || successRate.value < 98) {
    return {
      badge: '需关注',
      tone: 'danger',
      title: '平台存在待处理异常',
      description: `当前汇总发现 ${openAlertCount.value} 条未关闭告警，调用成功率为 ${formatPercent(successRate.value)}。建议优先查看告警与调用日志。`,
    }
  }
  if (providerCount.value > 0 && healthyProviderCount.value < providerCount.value) {
    return {
      badge: '部分降级',
      tone: 'warning',
      title: '调用整体稳定，部分渠道需检查',
      description: `${healthyProviderCount.value}/${providerCount.value} 个渠道处于健康状态，建议检查连接测试过期或已降级渠道。`,
    }
  }
  return {
    badge: '运行稳定',
    tone: 'success',
    title: '核心调用链当前运行稳定',
    description: '未发现明显的调用异常或渠道降级，可继续关注用量、成本与访问范围变化。',
  }
})

const derivedIndicators = computed(() => [
  { label: '调用成功率', value: formatPercent(successRate.value), note: `${formatNumber(Math.max(0, requestCount.value - errorCount.value))} 次成功` },
  { label: '渠道健康率', value: formatPercent(providerHealthRate.value), note: `${healthyProviderCount.value}/${providerCount.value || 0} 个健康` },
  { label: '单请求平均 Token', value: formatNumber(averageTokens.value, true), note: '按累计用量折算' },
])

const runtimeSummary = computed(() => [
  {
    label: '已登记渠道',
    value: providerCount.value,
    note: '控制面渠道总数',
    path: '/provider-channels',
    status: providerCount.value > 0 ? '已配置' : '待配置',
    tone: providerCount.value > 0 ? 'normal' : 'warning',
  },
  {
    label: '健康渠道',
    value: healthyProviderCount.value,
    note: '连接测试仍在有效期内',
    path: '/provider-health',
    status: healthyProviderCount.value === providerCount.value && providerCount.value > 0 ? '正常' : '需检查',
    tone: healthyProviderCount.value === providerCount.value && providerCount.value > 0 ? 'success' : 'warning',
  },
  {
    label: '平台模型',
    value: Number(stats.value.models || 0),
    note: '企业可治理模型记录',
    path: '/service-models',
    status: Number(stats.value.models || 0) > 0 ? '已登记' : '待配置',
    tone: Number(stats.value.models || 0) > 0 ? 'normal' : 'warning',
  },
  {
    label: '未关闭告警',
    value: openAlertCount.value,
    note: '需进入告警中心处置',
    path: '/alerts',
    status: openAlertCount.value > 0 ? '待处理' : '无异常',
    tone: openAlertCount.value > 0 ? 'danger' : 'success',
  },
])

const quickActions = [
  { title: '管理 API Key', desc: '创建、生成、禁用与核对模型范围', path: '/keys' },
  { title: '发布服务模型', desc: '维护企业模型别名与访问范围', path: '/service-models' },
  { title: '校验路由策略', desc: '检查部署、能力与生效价格', path: '/routes' },
  { title: '排查调用链', desc: '按请求记录定位渠道与错误原因', path: '/logs' },
]

function formatNumber(value: unknown, compact = false) {
  const number = Number(value || 0)
  if (!Number.isFinite(number)) return '0'
  return new Intl.NumberFormat('zh-CN', compact ? { notation: 'compact', maximumFractionDigits: 1 } : { maximumFractionDigits: 0 }).format(number)
}

function formatPercent(value: number) {
  return `${Number.isFinite(value) ? value.toFixed(value >= 99.95 ? 0 : 1) : '0'}%`
}

function formatMetric(item: { key: string; compact: boolean }) {
  return formatNumber(stats.value[item.key], item.compact)
}

function resourceTypeLabel(value: string) {
  const labels: Record<string, string> = {
    MODEL_DEPLOYMENT: '模型部署',
    PRICE_SOURCE: '价格源',
    PROVIDER_INSTANCE: '供应商渠道',
    PLATFORM_MODEL: '企业服务模型',
    BUDGET_RULE: '预算规则',
    API_KEY: 'API Key',
  }
  return labels[String(value || '').toUpperCase()] || '其他对象'
}

function severityLabel(value: string) {
  const labels: Record<string, string> = {
    CRITICAL: '严重',
    HIGH: '高',
    WARNING: '警告',
    MEDIUM: '警告',
    LOW: '低',
    INFO: '信息',
  }
  return labels[String(value || '').toUpperCase()] || '未知'
}

function severityClass(value: string) {
  const normalized = String(value || '').toUpperCase()
  if (['CRITICAL', 'HIGH'].includes(normalized)) return 'danger'
  if (['WARNING', 'MEDIUM', 'LOW'].includes(normalized)) return 'warning'
  return 'info'
}

function isInformationalAlert(row: any) {
  return ['INFO', '信息'].includes(String(row?.severity || '').toUpperCase())
}

function providerStatusLabel(row: any) {
  const value = String(row.healthStatus || row.lastConnectionTestStatus || row.status || '').toUpperCase()
  const labels: Record<string, string> = {
    HEALTHY: '健康',
    SUCCESS: '成功',
    ENABLED: '启用',
    DEGRADED: '降级',
    UNHEALTHY: '不健康',
    FAILED: '失败',
    DISABLED: '停用',
    UNKNOWN: '未检测',
  }
  return labels[value] || row.healthStatus || row.lastConnectionTestStatus || row.status || '未检测'
}

function providerStatusClass(row: any) {
  const label = providerStatusLabel(row)
  if (['健康', '成功', '启用'].includes(label)) return 'provider-status success'
  if (['降级', '观察中'].includes(label)) return 'provider-status warning'
  if (['不健康', '失败', '停用', '不可用'].includes(label)) return 'provider-status danger'
  return 'provider-status neutral'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [dashboard, channels, riskEvents] = await Promise.allSettled([
      get('/api/dashboard/stats'),
      list('/api/provider-instances'),
      list('/api/alerts'),
    ])
    if (dashboard.status !== 'fulfilled') throw dashboard.reason
    stats.value = dashboard.value || {}
    providers.value = channels.status === 'fulfilled' ? channels.value || [] : []
    alerts.value = riskEvents.status === 'fulfilled'
      ? (riskEvents.value || []).filter((row: any) => !isInformationalAlert(row))
      : []
    alertError.value = riskEvents.status === 'fulfilled' ? '' : '当前账号无法读取告警数据，请进入告警中心查看权限。'
  } catch (e) {
    error.value = errorMessage(e)
    stats.value = {}
    providers.value = []
    alerts.value = []
    alertError.value = ''
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
