
<template>
  <div class="app-shell" style="display:grid;place-items:center;min-height:100vh;padding:30px;background:linear-gradient(135deg,#f7fbff,#eefdfb)">
    <div class="hero" style="width:min(1120px,100%);grid-template-columns:1fr 420px">
      <section>
        <img src="../assets/TokenSea_logo.png" alt="TokenSea" class="hero-logo" style="width:210px;margin-bottom:22px" />
        <h1>企业级统一 <span>LLM API Gateway</span></h1>
        <p>统一模型接入、Virtual Key、租户归因、计费、预算、路由、审计与运维治理。</p>
        <div class="hero-metrics">
          <div class="hero-metric"><strong>统一出口</strong><span>OpenAI-compatible</span></div>
          <div class="hero-metric"><strong>密钥隔离</strong><span>供应商 Key 托管</span></div>
          <div class="hero-metric"><strong>费用归因</strong><span>租户 / 项目 / 应用</span></div>
        </div>
      </section>
      <section class="card" style="position:relative;z-index:2">
        <div class="card-title" style="font-size:18px;margin-bottom:16px">登录控制台</div>
        <a-form layout="vertical" @submit.prevent="login">
          <a-form-item label="账号"><a-input v-model:value="username" placeholder="请输入账号" /></a-form-item>
          <a-form-item label="密码"><a-input-password v-model:value="password" placeholder="请输入密码" /></a-form-item>
          <a-button type="primary" block :loading="loading" @click="login">登录</a-button>
        </a-form>
        <div class="inline-alert info" style="margin-top:14px">首次部署后，由管理员初始化首个账号；系统不会内置业务数据。</div>
      </section>
    </div>
  </div>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import { message } from 'ant-design-vue'
const router = useRouter(); const username = ref(''); const password = ref(''); const loading = ref(false)
async function login(){
  if (!username.value || !password.value) return message.warning('请输入账号和密码')
  loading.value = true
  try { const r = await api.post('/api/auth/login', { username: username.value, password: password.value }); localStorage.setItem('tokensea_token', r.data.data.token); router.push('/dashboard') }
  finally { loading.value = false }
}
</script>
