<template><DataPage :title="mode==='logs'?'调用日志':'用量'" :desc="mode==='logs'?'查看真实请求状态、错误码、耗时和回退链路。':'查看真实 Token、成本和请求归因。'" api-path="/api/usage" :fields="fields" :labels="labels" readonly/></template>
<script setup lang="ts">
import DataPage from './DataPage.vue'
defineProps<{mode?:string}>()
const fields=['requestId','tenantId','projectId','appId','apiKeyId','modelAlias','promptTokens','completionTokens','totalTokens','costAmount','currency','status','errorCode','latencyMs','createdAt']
const labels:Record<string,string>={requestId:'请求 ID',tenantId:'租户',projectId:'项目',appId:'应用',apiKeyId:'API Key',modelAlias:'模型',promptTokens:'输入 Token',completionTokens:'输出 Token',totalTokens:'总 Token',costAmount:'成本',currency:'币种',status:'状态',errorCode:'错误码',latencyMs:'耗时（毫秒）',createdAt:'时间'}
</script>
