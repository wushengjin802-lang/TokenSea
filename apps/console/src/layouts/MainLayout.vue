
<template>
  <div class="app-shell">
    <div class="app-layout">
      <header class="topbar">
        <div class="brand logo-link" @click="router.push('/dashboard')">
          <img class="brand-logo" src="../assets/TokenSea_logo_simple.png" alt="TokenSea" />
          <div class="brand-copy"><div class="brand-title">TokenSea</div><div class="brand-subtitle">LLM Gateway</div></div>
        </div>
        <div class="global-search"><span>⌕</span><input placeholder="搜索租户、Key、模型、请求 ID" /></div>
        <div class="top-actions">
          <span class="pill"><span class="dot"></span>网关运行中</span>
          <span class="pill">企业内网</span>
          <button class="btn small" @click="logout">退出</button>
          <div class="avatar">TS</div>
        </div>
      </header>
      <aside class="sidebar">
        <div class="sidebar-home" :class="{active: route.path === '/dashboard'}" @click="router.push('/dashboard')"><IconSvg name="home" /><span>总览</span></div>
        <details v-for="group in menuGroups" :key="group.key" class="sidebar-section" :open="isGroupOpen(group)">
          <summary class="section-summary"><IconSvg :name="group.icon" /><span>{{ group.title }}</span><span class="chev">›</span></summary>
          <div class="nav-submenu" style="display:block">
            <div v-for="item in group.items" :key="item.path" class="nav-subitem" :class="{active: route.path === item.path}" @click="go(item)">
              <span class="nav-bullet"></span><span>{{ item.title }}</span>
            </div>
          </div>
        </details>
      </aside>
      <main class="main"><router-view /></main>
    </div>
  </div>
</template>
<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { menuGroups, type MenuGroup, type MenuItem } from '../config/menu'
import IconSvg from '../components/IconSvg.vue'
const router = useRouter(); const route = useRoute()
function isGroupOpen(group: MenuGroup){ return group.items.some(i => i.path === route.path) }
function go(item: MenuItem){
  if (!item.implemented) return message.warning(`${item.title} 暂不可用，入口已保留`)
  router.push(item.path)
}
function logout(){ localStorage.removeItem('tokensea_token'); router.push('/login') }
</script>
