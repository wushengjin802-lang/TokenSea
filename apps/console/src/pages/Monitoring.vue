
<template>
  <div class="page"><div class="page-header"><div><div class="eyebrow">Observability</div><h1 class="page-title">指标监控</h1><p class="page-desc">监控 QPS、错误率、延迟、供应商健康、预算使用率和运行组件状态。</p></div><div class="header-actions"><button class="btn" @click="load">刷新</button></div></div>
  <div class="grid cols-4"><div class="card kpi"><div><div class="kpi-label">请求数</div><div class="kpi-value zero">{{ stats.requests || 0 }}</div></div></div><div class="card kpi"><div><div class="kpi-label">错误数</div><div class="kpi-value zero">{{ stats.errors || 0 }}</div></div></div><div class="card kpi"><div><div class="kpi-label">Token</div><div class="kpi-value zero">{{ stats.tokens || 0 }}</div></div></div><div class="card kpi"><div><div class="kpi-label">供应商健康记录</div><div class="kpi-value zero">{{ stats.providerHealth || 0 }}</div></div></div></div>
  <div class="card page-section"><div class="card-title">Prometheus / Grafana</div><p class="card-subtitle">部署文件已预留指标采集和看板服务。生产环境请导入正式告警规则并对接企业通知渠道。</p></div></div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get } from '../api/client'

const stats = ref<Record<string, number>>({})

async function load() {
  try {
    stats.value = await get('/api/dashboard/stats') || {}
  } catch {
    stats.value = {}
  }
}

onMounted(load)
</script>
