<template>
  <div class="landing-page">
    <header class="landing-nav">
      <router-link class="landing-brand" to="/" aria-label="TokenSea 首页">
        <img src="../assets/TokenSea_logo_simple.png" alt="" />
        <span><strong>TokenSea</strong><em>Enterprise LLM Gateway</em></span>
      </router-link>
      <nav class="landing-links" aria-label="产品导航">
        <a href="#product-capabilities">产品能力</a>
        <a href="#solutions">解决方案</a>
        <a href="#developer-docs">开发者文档</a>
      </nav>
      <div class="landing-actions">
        <button v-if="loggedIn" class="landing-console-button" type="button" @click="openConsole">控制台</button>
        <button v-if="!loggedIn" class="landing-login-button" type="button" @click="loginOpen = true">登录</button>
        <a-dropdown v-if="loggedIn" :trigger="['click']"><button class="landing-user-avatar" type="button" :title="sessionName">{{ initials }}</button><template #overlay><a-menu><a-menu-item key="logout" @click="logout">退出登录</a-menu-item></a-menu></template></a-dropdown>
      </div>
    </header>

    <main>
      <section class="landing-hero">
        <div class="landing-copy">
          <span class="landing-eyebrow">Enterprise LLM API Gateway</span>
          <h1>让企业以 <b>SaaS 化方式</b><br />管理多模型 API</h1>
          <p>TokenSea 面向企业内部与外部客户，统一管理模型接入、Virtual Key、预算与账单、路由与降级、审计和开发者门户，让多模型调用可运营、可治理、可交付。</p>
          <div class="landing-facts"><div><strong>统一 API</strong><span>兼容 OpenAI 标准接口</span></div><div><strong>企业级 Key</strong><span>支持权限、额度与有效期控制</span></div><div><strong>智能路由</strong><span>支持负载均衡、Fallback 与限流</span></div></div>
        </div>

        <section id="console" class="landing-preview" :aria-label="loggedIn && !error ? '控制台实时摘要' : 'TokenSea 企业网关调用链路演示'">
          <div class="preview-head"><div class="preview-brand"><img src="../assets/TokenSea_logo_simple.png" alt="" /><span><strong>TokenSea Console</strong><em>{{ loggedIn && !error ? '实时网关运营总览' : '企业网关调用链路' }}</em></span></div><b :class="{ demo: !loggedIn || error }">{{ loggedIn && !error ? '实时' : '产品演示' }}</b></div>
          <div v-if="loggedIn && loading" class="preview-state">正在读取控制台数据</div>
          <template v-else-if="loggedIn && !error">
            <div class="preview-metrics"><div><strong>{{ stats.requests ?? 0 }}</strong><span>累计请求</span></div><div><strong>{{ stats.providerHealth ?? 0 }}</strong><span>健康渠道</span></div><div><strong>{{ stats.tokens ?? 0 }}</strong><span>累计 Token</span></div></div>
            <div class="preview-runtime"><div class="runtime-title"><span>资源运行摘要</span><small>来自控制面</small></div><div class="runtime-bars"><i v-for="item in barValues" :key="item.label" :style="{ height: item.height + '%' }" :title="item.label"></i></div></div>
            <div class="preview-flow"><span>API 调用</span><i></i><span>路由校验</span><i></i><span>模型路由</span><i></i><span>计费审计</span></div>
            <div class="preview-list"><div v-for="row in providerRows" :key="row.id"><i :class="healthClass(row)"></i><strong>{{ row.instanceName || row.name || row.id }}</strong><span>{{ healthLabel(row) }}</span></div><div v-if="!providerRows.length" class="preview-empty">当前没有已登记的供应商渠道</div></div>
          </template>
          <template v-else>
            <div v-if="loggedIn && error" class="preview-demo-note">实时数据读取失败，已切换为产品演示</div>
            <div class="preview-metrics preview-demo-metrics"><div><strong>01</strong><span>Virtual Key 鉴权</span></div><div><strong>02</strong><span>预算与限流校验</span></div><div><strong>03</strong><span>模型路由与降级</span></div></div>
            <div class="preview-runtime preview-demo-runtime">
              <div class="runtime-title"><span>示例请求</span><small>演示数据</small></div>
              <div class="preview-demo-request"><div><span>企业服务模型</span><strong>chat-standard</strong></div><div><span>路由策略</span><strong>质量优先</strong></div><div><span>预算校验</span><strong>额度充足</strong></div><div><span>调用结果</span><strong>请求成功</strong></div></div>
            </div>
            <div class="preview-flow"><span>API 请求</span><i></i><span>Key 鉴权</span><i></i><span>智能路由</span><i></i><span>用量审计</span></div>
            <div class="preview-list preview-demo-list"><div><i class="healthy"></i><strong>主渠道</strong><span>健康</span></div><div><i class="healthy"></i><strong>备用渠道</strong><span>可切换</span></div><div><i class="healthy"></i><strong>成本记录</strong><span>已归因</span></div></div>
          </template>
        </section>
      </section>

      <section id="product-capabilities" class="landing-section landing-capabilities">
        <div class="landing-section-head">
          <span>Product Capabilities</span>
          <h2>把多模型接入升级为可运营、可治理的企业服务</h2>
          <p>围绕模型、密钥、路由、价格、权限和运营六个核心域，形成统一的 LLM API 管理闭环。</p>
        </div>
        <div class="landing-capability-grid">
          <article><span>01</span><h3>统一模型接入</h3><p>统一管理 DeepSeek、Qwen、Kimi、OpenAI、Anthropic、Azure OpenAI、vLLM 及 OpenAI Compatible API。</p><small>供应商渠道 · 模型目录 · 模型部署</small></article>
          <article><span>02</span><h3>Virtual Key 管理</h3><p>面向租户、项目、应用和团队创建独立 Virtual Key，隔离真实供应商密钥并控制模型范围与额度。</p><small>密钥隔离 · 有效期 · 配额控制</small></article>
          <article><span>03</span><h3>智能路由与降级</h3><p>通过模型别名、优先级、权重、健康状态和成本策略，在多个供应商渠道之间完成路由和故障切换。</p><small>健康检查 · 重试 · 限流 · 容灾</small></article>
          <article><span>04</span><h3>价格与成本治理</h3><p>同步官方价格，管理企业合同价、缓存价格组件和多币种汇率，完成差异审核、预算和成本账单。</p><small>价格同步 · 差异审核 · 成本核算</small></article>
          <article><span>05</span><h3>安全与审计</h3><p>提供供应商密钥托管、角色权限、租户数据范围、调用审计和操作留痕，降低模型接入安全风险。</p><small>RBAC · 密钥托管 · 审计追踪</small></article>
          <article><span>06</span><h3>可观测与运营</h3><p>持续观察请求量、Token、成功率、延迟、渠道健康、成本趋势和告警事件，支撑日常运营决策。</p><small>监控指标 · 运营看板 · 告警事件</small></article>
        </div>
      </section>

      <section id="solutions" class="landing-section landing-solutions">
        <div class="landing-section-head">
          <span>Solutions</span>
          <h2>面向企业真实使用场景的统一模型网关</h2>
          <p>不只是转发模型请求，而是把接入、交付、容灾和成本治理组合为可落地的企业方案。</p>
        </div>
        <div class="landing-solution-grid">
          <article><b>企业内部统一模型网关</b><p>多个部门共享模型能力，同时实现租户隔离、额度控制、统一审计和标准化服务出口。</p><em>适合集团、研发中心和共享能力平台</em></article>
          <article><b>AI 开发工具统一接入</b><p>为 Cursor、Dify、LangChain、Open WebUI 等工具提供统一 API 地址和 Virtual Key。</p><em>适合研发团队和 AI 应用交付团队</em></article>
          <article><b>多供应商容灾与路由</b><p>同一企业服务模型配置多个供应商渠道，根据健康、优先级和成本自动选择或切换。</p><em>适合高可用和跨供应商治理场景</em></article>
          <article><b>AI 成本治理</b><p>统一管理官方价、合同价、Token 用量、项目预算和成本账单，让费用可追溯、可审核。</p><em>适合财务、采购和平台运营团队</em></article>
        </div>
      </section>

      <section id="developer-docs" class="landing-section landing-docs-overview">
        <div class="landing-docs-copy">
          <span>Developer Documentation</span>
          <h2>从供应商接入到 Virtual Key 调用，一套文档走完整流程</h2>
          <p>开发者文档统一提供快速开始、API 调用、工具接入、管理员配置、部署运维和错误码说明。</p>
          <router-link to="/quick-start">查看开发者文档</router-link>
        </div>
        <div class="landing-docs-grid">
          <article><strong>快速开始</strong><span>创建供应商渠道、发现模型、发布企业服务模型并生成 Virtual Key。</span></article>
          <article><strong>API 与工具接入</strong><span>Chat Completions、Responses、流式调用，以及 Cursor、Dify、LangChain 接入。</span></article>
          <article><strong>管理员与运维指南</strong><span>路由、价格、预算、权限、审计、Docker 部署、迁移和常见故障处理。</span></article>
        </div>
      </section>

      <section class="landing-ecosystem"><p>典型接入 / 部署生态</p><div><span>LiteLLM</span><span>DeepSeek</span><span>Qwen</span><span>Azure OpenAI</span><span>Anthropic</span><span>vLLM</span></div></section>
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
import { stableSortRows } from '../listSort'

const router = useRouter()
const stats = ref<Record<string, any>>({})
const providers = ref<any[]>([])
const loading = ref(false)
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
const providerRows = computed(() => stableSortRows(providers.value, 'id', 'asc').slice(0, 3))
const barValues = computed(() => {
  const values = [stats.value.providers, stats.value.models, stats.value.keys, stats.value.providerHealth].map(value => Math.max(0, Number(value || 0)))
  const maximum = Math.max(...values, 1)
  return values.map((value, index) => ({ label: ['供应商渠道', '平台模型', 'Virtual Key', '健康渠道'][index], height: value ? Math.max(24, Math.round(value / maximum * 100)) : 12 }))
})
function healthLabel(row: any) { return row.healthStatus || row.lastConnectionTestStatus || '未检测' }
function healthClass(row: any) { return /HEALTHY|SUCCESS|正常|健康/i.test(healthLabel(row)) ? 'healthy' : 'unknown' }
function logout() { localStorage.removeItem('tokensea_token'); session.value = identity(); stats.value = {}; providers.value = []; loading.value = false; error.value = false }
function openConsole() { if (!loggedIn.value) { loginOpen.value = true; return }; router.push('/dashboard') }
async function submitLogin() {
  if (!username.value || !password.value) { loginError.value = '请输入账号和密码'; return }
  loginLoading.value = true
  loginError.value = ''
  try {
    const response = await api.post('/api/auth/login', { username: username.value, password: password.value })
    if (response.data?.success === false) throw new Error(response.data.message || '登录失败')
    const token = response.data?.data?.token
    if (!token) throw new Error('登录响应缺少访问令牌')
    localStorage.setItem('tokensea_token', token)
    loginOpen.value = false
    username.value = ''
    password.value = ''
    session.value = identity()
    await load()
  } catch (exception) { loginError.value = errorMessage(exception) } finally { loginLoading.value = false }
}
async function load() {
  if (!loggedIn.value) {
    loading.value = false
    error.value = false
    return
  }
  loading.value = true
  error.value = false
  try {
    const [dashboard, channels] = await Promise.all([get('/api/dashboard/stats'), list('/api/provider-instances')])
    stats.value = dashboard || {}
    providers.value = channels || []
  } catch { error.value = true } finally { loading.value = false }
}
onMounted(() => { if (loggedIn.value) load() })
</script>
