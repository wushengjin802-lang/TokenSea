
<template>
  <div class="page"><div class="page-header"><div><div class="eyebrow">Developer Portal</div><h1 class="page-title">{{ title }}</h1><p class="page-desc">业务系统通过统一 Base URL 与 TokenSea Virtual Key 接入，供应商原始 Key 不暴露给业务侧。</p></div></div>
    <div class="grid cols-2"><div class="card"><div class="card-title">接入参数</div><div class="detail-row"><span>Base URL</span><span class="mono">{{ gatewayBase }}</span></div><div class="detail-row"><span>认证方式</span><span class="mono">Authorization: Bearer &lt;TokenSea Virtual Key&gt;</span></div><div class="detail-row"><span>模型字段</span><span class="mono">model: &lt;平台模型别名&gt;</span></div></div><div class="card"><div class="card-title">调用前检查</div><ul class="doc-list"><li>已创建租户、项目、应用。</li><li>已配置供应商、模型、价格与路由。</li><li>已生成可用 Key，并配置模型范围与预算。</li></ul></div></div>
    <div class="card page-section"><div class="card-title">curl</div><pre class="code-block">curl {{ gatewayBase }}/v1/chat/completions \
  -H "Authorization: Bearer $TOKENSEA_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"&lt;model_alias&gt;","messages":[{"role":"user","content":"hello"}]}'</pre></div>
    <div class="card page-section"><div class="card-title">Python</div><pre class="code-block">from openai import OpenAI
client = OpenAI(base_url="{{ gatewayBase }}/v1", api_key=os.environ["TOKENSEA_API_KEY"])
resp = client.chat.completions.create(model="&lt;model_alias&gt;", messages=[{"role":"user","content":"hello"}])</pre></div>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { gatewayBase } from '../api/client'

const props = defineProps<{ mode?: string }>()
const title = computed(() => props.mode === 'sdk' ? 'SDK 示例' : props.mode === 'guide' ? '接入指南' : 'API 文档')
</script>
