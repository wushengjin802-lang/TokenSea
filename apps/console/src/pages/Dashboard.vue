
<template>
  <div class="page">
    <div class="page-header">
      <div><div class="eyebrow">Overview</div><h1 class="page-title">控制台总览</h1><p class="page-desc">集中查看网关运行状态、租户归因、模型接入、Key、用量、计费和审计健康度。所有指标来自后台接口与实际数据库。</p></div>
      <div class="header-actions"><button class="btn" @click="load">刷新</button><button class="btn primary" @click="router.push('/providers')">配置供应商</button></div>
    </div>
    <section class="hero" style="grid-template-columns:1fr 420px;margin-bottom:16px">
      <div><div class="eyebrow">TokenSea Gateway</div><h1>统一模型出口与成本治理</h1><p>业务系统只需要维护统一 Base URL 和 TokenSea Virtual Key，即可接入已配置模型，调用记录将写入不可变用量明细。</p></div>
      <div class="card"><div class="card-title">运行摘要</div><div class="detail-row"><span>数据来源</span><span>控制面数据库 / 网关指标</span></div><div class="detail-row"><span>日志策略</span><span>默认不保存完整 Prompt / Response</span></div><div class="detail-row"><span>端口策略</span><span>使用高位端口</span></div></div>
    </section>
    <div class="grid cols-4">
      <div class="card kpi"><div><div class="kpi-label">租户数</div><div class="kpi-value zero">{{ stats.tenants ?? 0 }}</div></div><span class="status info">实时</span></div>
      <div class="card kpi"><div><div class="kpi-label">供应商数</div><div class="kpi-value zero">{{ stats.providers ?? 0 }}</div></div><span class="status info">实时</span></div>
      <div class="card kpi"><div><div class="kpi-label">模型数</div><div class="kpi-value zero">{{ stats.models ?? 0 }}</div></div><span class="status info">实时</span></div>
      <div class="card kpi"><div><div class="kpi-label">活跃 Key</div><div class="kpi-value zero">{{ stats.keys ?? 0 }}</div></div><span class="status info">实时</span></div>
    </div>
    <div class="grid cols-3 page-section">
      <div class="card"><div class="card-title">接入链路</div><p class="card-subtitle">业务系统 → TokenSea Gateway → 路由策略 → 供应商模型 → 用量归集。</p></div>
      <div class="card"><div class="card-title">配置建议</div><p class="card-subtitle">请先创建租户、供应商、模型、价格和 Key，然后执行真实调用验证。</p></div>
      <div class="card"><div class="card-title">审计策略</div><p class="card-subtitle">Key、模型、价格、预算、路由策略变更均应写入操作审计。</p></div>
    </div>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { get } from '../api/client'
const router = useRouter(); const stats = ref<any>({})
async function load(){ try { stats.value = await get('/api/dashboard/stats') || {} } catch { stats.value = {} } }
onMounted(load)
</script>
