
<template>
  <div class="page">
    <div class="page-header"><div><div class="eyebrow">Usage Center</div><h1 class="page-title">{{ mode==='logs'?'调用日志':'用量统计' }}</h1><p class="page-desc">记录请求 ID、租户、Key、模型、供应商、Token、费用、状态、错误、耗时与 Fallback 链路。</p></div><div class="header-actions"><button class="btn" @click="load">刷新</button></div></div>
    <div class="card"><div class="toolbar"><input class="input" v-model="keyword" placeholder="搜索 request_id / 模型 / 状态" /><span class="muted">{{ filtered.length }} 条记录</span></div><div class="table-wrap"><table class="data-table"><thead><tr><th>请求 ID</th><th>租户</th><th>Key</th><th>模型</th><th>输入</th><th>输出</th><th>总量</th><th>成本</th><th>销售额</th><th>状态</th><th>耗时</th><th>时间</th></tr></thead><tbody v-if="filtered.length"><tr v-for="r in filtered" :key="r.id"><td class="mono">{{ r.requestId }}</td><td>{{ r.tenantId || '-' }}</td><td>{{ r.apiKeyId || '-' }}</td><td>{{ r.modelAlias || '-' }}</td><td>{{ r.promptTokens }}</td><td>{{ r.completionTokens }}</td><td>{{ r.totalTokens }}</td><td>{{ r.costAmount }}</td><td>{{ r.salesAmount }}</td><td><span :class="['status', r.status==='SUCCESS'?'ok':'danger']">{{ r.status }}</span></td><td>{{ r.latencyMs || '-' }} ms</td><td>{{ r.createdAt }}</td></tr></tbody></table><div v-if="!filtered.length" class="table-empty"><strong>暂无调用记录</strong><p>完成真实模型调用后，用量记录会在此处展示。</p></div><div class="table-footer-note">Prompt 和 Response 默认不保存完整内容。</div></div></div>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { list } from '../api/client'
defineProps<{mode?: string}>()
const rows = ref<any[]>([]); const keyword = ref('')
const filtered = computed(()=>rows.value.filter(r=>!keyword.value || JSON.stringify(r).toLowerCase().includes(keyword.value.toLowerCase())))
async function load(){ rows.value = await list('/api/usage') }
onMounted(load)
</script>
