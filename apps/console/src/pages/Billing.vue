
<template><DataPage title="账单" desc="按租户账期读取真实 Token、成本、销售额与账单状态。" api-path="/api/billing" :fields="fields" :labels="labels" readonly /></template>
<script setup lang="ts">
import DataPage from './DataPage.vue'

const fields = ['tenantId', 'periodStart', 'periodEnd', 'totalTokens', 'totalCost', 'totalSales', 'status']
const labels: Record<string, string> = {
  tenantId: '租户 ID',
  periodStart: '账期开始',
  periodEnd: '账期结束',
  totalTokens: 'Token 总量',
  totalCost: '总成本',
  totalSales: '销售额',
  status: '状态',
}
</script>
