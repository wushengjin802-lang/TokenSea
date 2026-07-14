<template>
  <section class="page dashboard-page">
    <header class="page-header">
      <div>
        <div class="breadcrumb">工作台</div>
        <div class="eyebrow">Enterprise Operations Overview</div>
        <h1 class="page-title">企业模型 API 运行与成本总览</h1>
        <p class="page-desc">汇总控制面的真实运行、资源、调用和风险数据；具体配置请进入对应专业页面处理。</p>
      </div>
      <div class="header-actions"><router-link class="btn" to="/alerts">查看告警</router-link><router-link class="btn primary" to="/model-deployments">查看模型部署</router-link><button class="btn" :disabled="loading" @click="load">刷新</button></div>
    </header>

    <div v-if="error" class="state-panel error-state" role="alert"><strong>概览加载失败</strong><p>{{ error }}</p><button class="btn" @click="load">重试</button></div>
    <div v-else-if="loading" class="state-panel"><span class="loading-mark"></span><strong>正在读取平台指标</strong></div>
    <template v-else>
      <section class="dashboard-hero card">
        <div class="dashboard-hero-main"><span class="dashboard-badge">Operational Control Center</span><h2>先判断平台是否正常，<span>再下钻定位并处置</span></h2><p>当前页面只展示真实聚合值与异常入口，不使用演示趋势、虚构 SLA 或静态待办。</p><div class="dashboard-hero-actions"><router-link class="btn primary" to="/alerts">处理风险与告警</router-link><router-link class="btn" to="/usage">查看用量分析</router-link></div></div>
        <div class="dashboard-hero-side"><div class="hero-stat-card primary"><strong>{{ stats.providerHealth ?? 0 }}</strong><span>健康渠道</span><em>近 30 分钟内连接测试成功</em></div><div class="hero-stat-grid"><div class="hero-stat-card"><strong>{{ stats.errors ?? 0 }}</strong><span>错误请求</span></div><div class="hero-stat-card"><strong>{{ stats.requests ?? 0 }}</strong><span>累计请求</span></div><div class="hero-stat-card"><strong>{{ stats.tokens ?? 0 }}</strong><span>累计 Token</span></div><div class="hero-stat-card"><strong>{{ openAlerts.length }}</strong><span>未关闭告警</span></div></div></div>
      </section>

      <section class="metric-grid dashboard-metrics"><article v-for="item in metrics" :key="item.key" class="metric-card"><span>{{ item.label }}</span><strong>{{ stats[item.key] ?? 0 }}</strong><em>{{ item.note }}</em></article></section>

      <section class="dashboard-grid">
        <article class="card"><div class="card-title">模型运行态摘要 <router-link class="btn small" to="/model-deployments">进入模型部署</router-link></div><div class="runtime-status-grid"><router-link v-for="item in runtimeSummary" :key="item.label" :class="['runtime-status-card', { warning: item.warning }]" :to="item.path"><span>{{ item.label }}</span><strong>{{ item.value }}</strong><em>{{ item.note }}</em></router-link></div><p class="card-subtitle">部署级模型、渠道、能力和价格状态均从控制面实际记录汇总。</p></article>
        <article class="card"><div class="card-title">待处理风险 <router-link class="btn small" to="/alerts">全部告警</router-link></div><div v-if="openAlerts.length" class="task-group-list"><div v-for="row in openAlerts" :key="row.id"><strong>{{ row.title || row.alertType || '告警事件' }}</strong><span>{{ row.detail || row.resourceId || '未提供详情' }}</span><em :class="severityClass(row.severity)">{{ severityLabel(row.severity) }}</em></div></div><div v-else class="state-panel compact-state empty-state">{{ alertError || '当前没有未关闭告警' }}</div></article>
        <article class="card"><div class="card-title">渠道健康 <router-link class="btn small" to="/provider-health">查看渠道</router-link></div><div v-if="providerRows.length" class="summary-list"><div v-for="row in providerRows" :key="row.id"><strong>{{ row.instanceName || row.name || row.id }}</strong><span>{{ row.healthStatus || row.lastConnectionTestStatus || '未检测' }}</span></div></div><div v-else class="state-panel compact-state empty-state">当前没有渠道健康记录</div></article>
      </section>

      <section class="quick-grid"><router-link v-for="item in quickActions" :key="item.path" class="quick-action" :to="item.path"><strong>{{ item.title }}</strong><span>{{ item.desc }}</span></router-link></section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { errorMessage, get, list } from '../api/client'

const stats = ref<Record<string, any>>({}), providers = ref<any[]>([]), alerts = ref<any[]>([]), loading = ref(false), error = ref(''), alertError = ref('')
const metrics = computed(() => [
  { key: 'tenants', label: '租户', note: '已登记组织单元' }, { key: 'providers', label: '供应商渠道', note: '已登记渠道' },
  { key: 'models', label: '平台模型', note: '可管理模型' }, { key: 'keys', label: 'Virtual Key', note: '已创建密钥' },
  { key: 'requests', label: '累计请求', note: '真实调用记录' }, { key: 'errors', label: '错误请求', note: '真实异常记录' },
  { key: 'tokens', label: '累计 Token', note: '真实用量汇总' }, { key: 'providerHealth', label: '健康渠道', note: '近 30 分钟检测' },
])
const providerRows = computed(() => providers.value.slice(0, 5))
const openAlerts = computed(() => alerts.value.filter((row) => !['RESOLVED', '已解决', 'CLOSED', '已关闭'].includes(String(row.status || '').toUpperCase())).slice(0, 5))
const runtimeSummary = computed(() => [
  { label: '已登记渠道', value: stats.value.providers ?? 0, note: '控制面渠道总数', path: '/provider-channels', warning: false },
  { label: '健康渠道', value: stats.value.providerHealth ?? 0, note: '近 30 分钟连接成功', path: '/provider-health', warning: Number(stats.value.providers || 0) > Number(stats.value.providerHealth || 0) },
  { label: '平台模型', value: stats.value.models ?? 0, note: '可管理模型记录', path: '/service-models', warning: false },
  { label: '未关闭告警', value: openAlerts.value.length, note: '需进入告警中心处置', path: '/alerts', warning: openAlerts.value.length > 0 },
])
const quickActions = [
  { title: '处理模型部署异常', desc: '查看渠道发现、部署审核和可路由状态。', path: '/model-deployments' },
  { title: '审核模型变化', desc: '核对来源差异、影响范围与发布结果。', path: '/discovery-diffs' },
  { title: '查看成本与预算', desc: '按真实调用记录查看用量和预算规则。', path: '/usage' },
  { title: '排查调用链', desc: '查看请求结果、错误码和渠道健康。', path: '/logs' },
]
function severityLabel(value: string) { return ({ HIGH: '高', CRITICAL: '严重', MEDIUM: '中', LOW: '低' } as Record<string, string>)[String(value || '').toUpperCase()] || value || '待处理' }
function severityClass(value: string) { const v = String(value || '').toUpperCase(); return v === 'CRITICAL' || v === 'HIGH' ? 'danger' : v === 'MEDIUM' ? 'warn' : 'info' }
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [dashboard, channels, riskEvents] = await Promise.allSettled([get('/api/dashboard/stats'), list('/api/provider-instances'), list('/api/alerts')])
    if (dashboard.status !== 'fulfilled') throw dashboard.reason
    stats.value = dashboard.value || {}
    providers.value = channels.status === 'fulfilled' ? channels.value || [] : []
    alerts.value = riskEvents.status === 'fulfilled' ? riskEvents.value || [] : []
    alertError.value = riskEvents.status === 'fulfilled' ? '' : '当前账号无法读取告警数据，请进入告警中心查看权限。'
  } catch (e) {
    error.value = errorMessage(e)
    stats.value = {}
    providers.value = []
    alerts.value = []
    alertError.value = ''
  } finally { loading.value = false }
}
onMounted(load)
</script>
