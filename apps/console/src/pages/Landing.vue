<template>
  <div class="landing-page">
    <header class="landing-nav">
      <router-link class="landing-brand" to="/" aria-label="TokenSea 首页">
        <img src="../assets/TokenSea_logo_simple.png" alt="" />
        <span><strong>TokenSea</strong><em>Enterprise LLM Gateway</em></span>
      </router-link>
      <nav class="landing-links" aria-label="产品导航">
        <a href="#product">产品</a><button class="landing-console-link" type="button" @click="openConsole">控制台</button><a href="#docs">文档</a><a href="#capabilities">页面清单</a>
      </nav>
      <div class="landing-actions"><router-link class="landing-docs-button" to="/quick-start">开发者文档</router-link><button v-if="!loggedIn" class="landing-login-button" type="button" @click="loginOpen = true">登录</button><a-dropdown v-else :trigger="['click']"><button class="landing-user-avatar" type="button" :title="sessionName">{{ initials }}</button><template #overlay><a-menu><a-menu-item key="logout" @click="logout">退出登录</a-menu-item></a-menu></template></a-dropdown></div>
    </header>

    <main>
      <section id="product" class="landing-hero">
        <div class="landing-copy">
          <span class="landing-eyebrow">Enterprise LLM API Gateway</span>
          <h1>让企业以 <b>SaaS 化方式</b><br />管理多模型 API</h1>
          <p>TokenSea 面向企业内部与外部客户，统一管理模型接入、Virtual Key、预算与账单、路由与降级、审计和开发者门户，让多模型调用可运营、可治理、可交付。</p>
          <div class="landing-facts"><div><strong>{{ stats.providers ?? 0 }}</strong><span>已登记供应商渠道</span></div><div><strong>{{ stats.models ?? 0 }}</strong><span>平台模型记录</span></div><div><strong>{{ stats.keys ?? 0 }}</strong><span>已创建 Virtual Key</span></div></div>
        </div>

        <section id="console" class="landing-preview" aria-label="控制台实时摘要">
          <div class="preview-head"><div class="preview-brand"><img src="../assets/TokenSea_logo_simple.png" alt="" /><span><strong>TokenSea Console</strong><em>实时网关运营总览</em></span></div><b>实时</b></div>
          <div v-if="loading" class="preview-state">正在读取控制台数据</div>
          <div v-else-if="error" class="preview-state">控制台数据暂不可用</div>
          <template v-else>
            <div class="preview-metrics"><div><strong>{{ stats.requests ?? 0 }}</strong><span>累计请求</span></div><div><strong>{{ stats.providerHealth ?? 0 }}</strong><span>健康渠道</span></div><div><strong>{{ stats.tokens ?? 0 }}</strong><span>累计 Token</span></div></div>
            <div class="preview-runtime"><div class="runtime-title"><span>资源运行摘要</span><small>来自控制面</small></div><div class="runtime-bars"><i v-for="item in barValues" :key="item.label" :style="{ height: item.height + '%' }" :title="item.label"></i></div></div>
            <div class="preview-flow"><span>API 调用</span><i></i><span>路由校验</span><i></i><span>模型路由</span><i></i><span>计费审计</span></div>
            <div class="preview-list"><div v-for="row in providerRows" :key="row.id"><i :class="healthClass(row)"></i><strong>{{ row.instanceName || row.name || row.id }}</strong><span>{{ healthLabel(row) }}</span></div><div v-if="!providerRows.length" class="preview-empty">当前没有已登记的供应商渠道</div></div>
          </template>
        </section>
      </section>

      <section id="capabilities" class="landing-ecosystem"><p>典型接入 / 部署生态</p><div><span>LiteLLM</span><span>DeepSeek</span><span>Qwen</span><span>Azure OpenAI</span><span>Anthropic</span><span>vLLM</span></div></section>
      <section id="docs" class="sr-only">开发者可在登录后访问快速开始和模型目录。</section>
    </main>
    <a-modal v-model:open="loginOpen" title="登录 TokenSea" :footer="null" :mask-closable="!loginLoading" class="landing-login-modal">
      <p class="landing-login-tip">使用管理员分配的账号登录控制台。</p>
      <div v-if="loginError" class="inline-alert danger" role="alert">{{ loginError }}</div>
      <a-form layout="vertical" @submit.prevent="submitLogin">
        <a-form-item label="账号"><a-input v-model:value="username" autocomplete="username" autofocus placeholder="请输入账号" /></a-form-item>
        <a-form-item label="密码"><a-input-password v-model:value="password" autocomplete="current-password" placeholder="请输入密码" /></a-form-item>
        <a-button type="primary" html-type="submit" block :loading="loginLoading">登录</a-button>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage, get, identity, list } from '../api/client'

const router = useRouter()
const stats = ref<Record<string, any>>({})
const providers = ref<any[]>([])
const loading = ref(true)
const error = ref(false)
const loginOpen = ref(false)
const username = ref('')
const password = ref('')
const loginLoading = ref(false)
const loginError = ref('')
const session = ref(identity())
const loggedIn = computed(() => Boolean(session.value.userId || session.value.username))
const sessionName = computed(() => session.value.username || session.value.userId || '已登录用户')
const initials = computed(() => sessionName.value.slice(0, 2).toUpperCase())
const providerRows = computed(() => providers.value.slice(0, 3))
const barValues = computed(() => {
  const values = [stats.value.providers, stats.value.models, stats.value.keys, stats.value.providerHealth].map(value => Math.max(0, Number(value || 0)))
  const maximum = Math.max(...values, 1)
  return values.map((value, index) => ({ label: ['供应商渠道', '平台模型', 'Virtual Key', '健康渠道'][index], height: value ? Math.max(24, Math.round(value / maximum * 100)) : 12 }))
})
function healthLabel(row: any) { return row.healthStatus || row.lastConnectionTestStatus || '未检测' }
function healthClass(row: any) { return /HEALTHY|SUCCESS|正常|健康/i.test(healthLabel(row)) ? 'healthy' : 'unknown' }
function logout() { localStorage.removeItem('tokensea_token'); session.value = identity() }
function openConsole() { if (!loggedIn.value) { loginOpen.value = true; return }; router.push('/dashboard') }
async function submitLogin() {
  if (!username.value || !password.value) { loginError.value = '请输入账号和密码'; return }
  loginLoading.value = true
  loginError.value = ''
  try {
    const response = await api.post('/api/auth/login', { username: username.value, password: password.value })
    const token = response.data?.data?.token
    if (!token) throw new Error('登录响应缺少访问令牌')
    localStorage.setItem('tokensea_token', token)
    loginOpen.value = false
    username.value = ''
    password.value = ''
    session.value = identity()
  } catch (exception) { loginError.value = errorMessage(exception) } finally { loginLoading.value = false }
}
async function load() {
  loading.value = true
  error.value = false
  try {
    const [dashboard, channels] = await Promise.all([get('/api/dashboard/stats'), list('/api/provider-instances')])
    stats.value = dashboard || {}
    providers.value = channels || []
  } catch { error.value = true } finally { loading.value = false }
}
onMounted(load)
</script>
