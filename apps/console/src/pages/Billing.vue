
<template><DataPage title="账单管理" eyebrow="Billing Center" desc="按租户账期汇总 Token、成本、销售额与对账状态，支持后续导出与锁定。" api-path="/api/billing" :fields="fields" :labels="labels" /></template>
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
