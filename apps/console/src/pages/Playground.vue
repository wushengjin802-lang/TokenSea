
<template>
  <div class="page"><div class="page-header"><div><div class="eyebrow">Playground</div><h1 class="page-title">在线调试</h1><p class="page-desc">使用真实 TokenSea Virtual Key 调用网关，不内置模型 Key 或业务数据。</p></div></div>
    <div class="playground-grid"><section class="card"><div class="field-row"><label>Base URL</label><input class="input" v-model="baseUrl" /></div><div class="field-row"><label>Virtual Key</label><input class="input" v-model="apiKey" type="password" placeholder="输入真实 Key" /></div><div class="field-row"><label>模型别名</label><input class="input" v-model="model" placeholder="如已配置的模型别名" /></div><label class="muted">消息内容</label><textarea class="textarea-large" v-model="prompt" placeholder="请输入要发送给模型的内容"></textarea><div style="margin-top:12px"><button class="btn primary" :disabled="loading" @click="send">发送请求</button></div></section><section class="card"><div class="card-title">响应结果</div><pre class="code-block" style="min-height:360px">{{ result || '等待真实调用结果' }}</pre></section></div>
  </div>
</template>
<script setup lang="ts">
import { ref } from 'vue'; import axios from 'axios'; import { message } from 'ant-design-vue'; import { gatewayBase } from '../api/client'
const baseUrl=ref(gatewayBase); const apiKey=ref(''); const model=ref(''); const prompt=ref(''); const result=ref(''); const loading=ref(false)
async function send(){ if(!apiKey.value || !model.value || !prompt.value) return message.warning('请填写 Key、模型别名和消息内容'); loading.value=true; result.value=''; try{ const r=await axios.post(`${baseUrl.value}/v1/chat/completions`, {model:model.value, messages:[{role:'user', content:prompt.value}]}, {headers:{Authorization:`Bearer ${apiKey.value}`}}); result.value=JSON.stringify(r.data,null,2) } catch(e:any){ result.value=JSON.stringify(e?.response?.data || {message:e.message},null,2) } finally{ loading.value=false } }
</script>
