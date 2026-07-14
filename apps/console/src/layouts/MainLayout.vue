<template>
  <router-view v-if="route.path === '/'" />
  <div v-else class="app-layout prototype-shell">
    <header class="topbar prototype-topbar">
      <router-link to="/" class="brand logo-link" aria-label="返回产品首页">
        <img class="brand-logo" src="../assets/TokenSea_logo_simple.png" alt="" />
        <div class="brand-copy">
          <strong>TokenSea</strong>
          <span>Enterprise LLM Control Plane</span>
        </div>
      </router-link>
      <label class="global-search">
        <span class="sr-only">全局搜索</span>
        <input v-model.trim="search" placeholder="搜索模型、渠道、Key、租户、请求 ID、同步任务" @keyup.enter="goSearch" />
      </label>
      <div class="top-actions">
        <span :class="['pill', health === 'ok' ? 'healthy' : '']"><i class="dot"></i>{{ healthText }}</span>
        <span class="pill">{{ admin ? '全局视图' : '租户视图' }}</span>
        <a-dropdown :trigger="['click']">
          <button class="avatar avatar-button" type="button" :aria-label="session.username || '当前用户'">{{ initials }}</button>
          <template #overlay><a-menu @click="onAccountMenuClick"><a-menu-item key="logout">退出登录</a-menu-item></a-menu></template>
        </a-dropdown>
      </div>
    </header>
    <aside class="sidebar prototype-sidebar" aria-label="主导航">
      <router-link :to="home" class="sidebar-home"><IconSvg name="home" /><span>{{ admin ? '工作台' : '租户工作台' }}</span></router-link>
      <section v-for="group in groups" :key="group.key" :class="['nav-group', { expanded: expanded[group.key] }]">
        <button class="nav-group-header" type="button" @click="toggle(group.key)">
          <span class="nav-group-meta"><IconSvg :name="group.icon" /><span>{{ group.title }}</span></span>
          <span aria-hidden="true">{{ expanded[group.key] ? '⌃' : '⌄' }}</span>
        </button>
        <nav class="nav-submenu">
          <router-link v-for="item in group.items" :key="item.path" :to="item.path" class="nav-subitem"><span class="nav-bullet"></span>{{ item.title }}</router-link>
        </nav>
      </section>
    </aside>
    <main class="main prototype-main" tabindex="-1"><router-view /></main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, identity, isAdmin } from '../api/client'
import { visibleMenuGroups } from '../config/menu'
import IconSvg from '../components/IconSvg.vue'

const router = useRouter()
const route = useRoute()
const session = identity()
const admin = isAdmin()
const home = admin ? '/dashboard' : '/workspace'
const groups = computed(() => visibleMenuGroups(session.roles))
const expanded = reactive<Record<string, boolean>>({})
const health = ref<'ok' | 'error' | 'checking'>('checking')
const search = ref('')
const initials = computed(() => (session.username || session.userId || 'TS').slice(0, 2).toUpperCase())
const healthText = computed(() => health.value === 'ok' ? '生产集群正常' : health.value === 'error' ? '控制面不可用' : '正在检查集群')

function toggle(key: string) { expanded[key] = !expanded[key] }
function expandCurrent() {
  groups.value.forEach((group) => { if (group.items.some((item) => route.path === item.path)) expanded[group.key] = true })
}
function goSearch() {
  const keyword = search.value
  if (!keyword) return
  router.push({ path: '/logs', query: { keyword } })
}
function onAccountMenuClick({ key }: { key: string }) { if (key === 'logout') { localStorage.removeItem('tokensea_token'); router.replace('/') } }
onMounted(async () => { expandCurrent(); try { await api.get('/actuator/health'); health.value = 'ok' } catch { health.value = 'error' } })
watch(() => route.path, expandCurrent)
</script>
