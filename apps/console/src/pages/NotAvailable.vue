<template><section class="not-found" role="alert"><span>404</span><h1>页面不存在</h1><p>请求的地址不属于当前控制台。</p><router-link class="btn primary" :to="home">返回工作台</router-link></section></template>
<script setup lang="ts">
import { isAdmin } from '../api/client'
const home = isAdmin() ? '/dashboard' : '/workspace'
</script>
