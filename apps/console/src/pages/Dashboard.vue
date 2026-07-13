<template><div class="page console-page"><header class="page-header"><div><div class="eyebrow">平台工作台</div><h1 class="page-title">运行与治理概览</h1><p class="page-desc">以下指标直接读取控制面聚合接口。</p></div><button class="btn" :disabled="loading" @click="load">刷新</button></header><div v-if="error" class="state-panel error-state" role="alert"><strong>概览加载失败</strong><p>{{error}}</p><button class="btn" @click="load">重试</button></div><div v-else-if="loading" class="state-panel"><span class="loading-mark"></span><strong>正在读取平台指标</strong></div><template v-else><section class="metric-grid"><article v-for="item in metrics" :key="item.key" class="metric-card"><span>{{item.label}}</span><strong>{{stats[item.key]??'—'}}</strong></article></section></template></div></template>
<script setup lang="ts">
import{computed,onMounted,ref}from'vue';import{errorMessage,get}from'../api/client'
const stats=ref<Record<string,any>>({}),loading=ref(false),error=ref('')
const defs:[string,string][]=[['tenants','租户'],['providers','供应商渠道'],['models','平台模型'],['keys','API Key'],['requests','请求'],['errors','错误'],['tokens','Token'],['providerHealth','健康渠道']]
const metrics=computed(()=>defs.map(([key,label])=>({key,label})))
async function load(){loading.value=true;error.value='';try{stats.value=await get('/api/dashboard/stats')}catch(e){error.value=errorMessage(e)}finally{loading.value=false}}
onMounted(load)
</script>
