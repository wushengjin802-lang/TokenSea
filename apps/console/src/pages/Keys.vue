
<template>
  <DataPage title="Key 列表" eyebrow="Key Center" desc="申请、审批、生成、禁用 TokenSea Virtual Key，并配置模型范围、预算和限流策略。" api-path="/api/keys" :fields="fields" :labels="labels" />
</template>
<script setup lang="ts">
import DataPage from './DataPage.vue'
const fields = ['tenantId','projectId','appId','name','keyPrefix','status','approvalStatus','modelScope','budgetAmount','rpmLimit','tpmLimit','qpsLimit','expiresAt']
const labels:any = {tenantId:'租户 ID',projectId:'项目 ID',appId:'应用 ID',name:'Key 名称',keyPrefix:'Key 前缀',status:'状态',approvalStatus:'审批状态',modelScope:'模型范围',budgetAmount:'预算',rpmLimit:'RPM',tpmLimit:'TPM',qpsLimit:'QPS',expiresAt:'有效期'}
</script>
