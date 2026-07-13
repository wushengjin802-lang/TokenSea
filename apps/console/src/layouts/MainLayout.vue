<template><div class="app-layout industrial-shell">
  <header class="topbar"><router-link :to="home" class="brand logo-link" aria-label="返回工作台"><img class="brand-logo" src="../assets/TokenSea_logo_simple.png" alt=""/><div class="brand-copy"><strong>TokenSea</strong><span>模型网关运营控制台</span></div></router-link><div class="top-context"><span class="role-mark">{{ admin?'平台管理员':'租户成员' }}</span><span>{{ session.username || session.userId }}</span><button class="btn small" @click="logout">退出登录</button></div></header>
  <aside class="sidebar" aria-label="主导航"><router-link :to="home" class="sidebar-home"><IconSvg name="home"/><span>{{ admin?'平台工作台':'租户工作台' }}</span></router-link><section v-for="group in groups" :key="group.key" class="nav-group"><div class="nav-heading"><IconSvg :name="group.icon"/><span>{{ group.title }}</span></div><router-link v-for="item in group.items" :key="item.path" :to="item.path" class="nav-subitem"><span>{{ item.title }}</span></router-link></section></aside>
  <main class="main" tabindex="-1"><router-view/></main>
</div></template>
<script setup lang="ts">
import { computed } from 'vue';import { useRouter } from 'vue-router';import { identity, isAdmin } from '../api/client';import { visibleMenuGroups } from '../config/menu';import IconSvg from '../components/IconSvg.vue'
const router=useRouter(),session=identity(),admin=isAdmin(),home=admin?'/dashboard':'/workspace';const groups=computed(()=>visibleMenuGroups(session.roles))
function logout(){localStorage.removeItem('tokensea_token');router.replace('/login')}
</script>
