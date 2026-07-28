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
import { api, identity } from '../api/client'
import { visibleMenuGroups } from '../config/menu'
import IconSvg from '../components/IconSvg.vue'

const router = useRouter()
const route = useRoute()
const session = ref(identity())
const admin = computed(() => session.value.roles.includes('ADMIN'))
const home = computed(() => admin.value ? '/dashboard' : '/workspace')
const groups = computed(() => visibleMenuGroups(session.value.roles))
const expanded = reactive<Record<string, boolean>>({})
const health = ref<'ok' | 'error' | 'checking'>('checking')
const initials = computed(() => (session.value.username || session.value.userId || 'TS').slice(0, 2).toUpperCase())
const healthText = computed(() => health.value === 'ok' ? '生产集群正常' : health.value === 'error' ? '控制面不可用' : '正在检查集群')

function toggle(key: string) { expanded[key] = !expanded[key] }
function expandCurrent() {
  groups.value.forEach((group) => {
    if (!(group.key in expanded) && group.defaultExpanded) expanded[group.key] = true
    if (group.items.some((item) => route.path === item.path)) expanded[group.key] = true
  })
}
function onAccountMenuClick({ key }: { key: string }) { if (key === 'logout') { localStorage.removeItem('tokensea_token'); session.value = identity(); router.replace('/') } }
onMounted(async () => { expandCurrent(); try { await api.get('/actuator/health'); health.value = 'ok' } catch { health.value = 'error' } })
watch(() => route.path, () => { session.value = identity(); expandCurrent() })
</script>
