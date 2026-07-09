
<template><div class="card not-available"><div class="eyebrow">Reserved</div><h1 class="page-title">{{ title }}</h1><p class="page-desc">该能力暂不可用，入口已保留。后续启用时会接入权限、审计、配置与接口联调。</p><button class="btn primary" @click="router.push('/dashboard')">返回总览</button></div></template>
<script setup lang="ts">
import { useRouter } from 'vue-router'

defineProps<{ title?: string }>()
const router = useRouter()
</script>
