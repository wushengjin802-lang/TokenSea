<template>
  <div class="page console-page playground-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">Playground</h1>
        <p class="page-desc">
          使用真实 Virtual Key 查询可访问服务模型，并通过 TokenSea Gateway 发送 OpenAI-compatible 对话请求。
        </p>
      </div>
      <button class="btn" :disabled="healthLoading" @click="checkGateway">
        {{ healthLoading ? "检查中" : "检查网关" }}
      </button>
    </header>

    <div v-if="healthError" class="inline-alert danger">{{ healthError }}</div>

    <div class="playground-grid">
      <section class="card request-panel">
        <div class="field">
          <label>Gateway 地址</label>
          <input class="input" :value="gatewayBase" disabled />
        </div>
        <div class="gateway-state-row">
          <span>服务状态</span>
          <span :class="['status', gatewayReady ? 'ok' : healthError ? 'danger' : 'muted']">
            {{ gatewayReady ? "已就绪" : healthLoading ? "检查中" : healthError ? "异常" : "未检查" }}
          </span>
        </div>

        <div class="field">
          <label for="play-key">Virtual Key</label>
          <div class="key-row">
            <input
              id="play-key"
              v-model="apiKey"
              class="input"
              type="password"
              autocomplete="off"
              placeholder="输入 ts_ 开头的 TokenSea Virtual Key"
              @keyup.enter="loadModels"
            />
            <button
              class="btn"
              :disabled="modelsLoading || !apiKey.trim()"
              @click="loadModels"
            >
              {{ modelsLoading ? "验证中" : "验证并加载模型" }}
            </button>
          </div>
          <span class="field-help">模型列表来自该 Key 的 <code>GET /v1/models</code> 实时权限结果。</span>
        </div>

        <div v-if="modelError" class="inline-alert danger">{{ modelError }}</div>
        <div v-else-if="keyVerified" class="inline-alert success">
          Virtual Key 验证成功，可访问 {{ models.length }} 个企业服务模型。
        </div>

        <div class="field">
          <label>企业服务模型</label>
          <a-select
            v-model:value="model"
            :options="models"
            :loading="modelsLoading"
            :disabled="!keyVerified"
            show-search
            option-filter-prop="label"
            placeholder="先验证 Virtual Key，再选择模型"
          />
        </div>

        <div class="field">
          <label for="play-prompt">测试消息</label>
          <textarea
            id="play-prompt"
            v-model="prompt"
            class="textarea-large"
            placeholder="输入消息内容"
          ></textarea>
        </div>

        <div class="request-options">
          <label>
            <span>Temperature</span>
            <input v-model.number="temperature" class="input" type="number" min="0" max="2" step="0.1" />
          </label>
          <label>
            <span>最大输出 Token</span>
            <input v-model.number="maxTokens" class="input" type="number" min="1" max="8192" step="1" />
          </label>
        </div>

        <button
          class="btn primary full-width"
          :disabled="loading || !keyVerified || !model || !prompt.trim()"
          @click="send"
        >
          {{ loading ? "请求中" : "发送真实请求" }}
        </button>
        <p class="security-note">Virtual Key 仅存在当前页面内存，不写入浏览器存储。</p>
      </section>

      <section class="card response-card">
        <div class="response-head">
          <div>
            <span class="response-title">Gateway 响应</span>
            <small>POST /v1/chat/completions</small>
          </div>
          <span v-if="result" class="status ok">调用成功</span>
        </div>

        <div v-if="requestError" class="inline-alert danger">{{ requestError }}</div>
        <div v-if="loading" class="state-panel">
          <span class="loading-mark"></span>
          <strong>正在调用企业服务模型</strong>
        </div>
        <template v-else-if="result">
          <div class="metric-grid">
            <div><span>请求 ID</span><strong>{{ result.requestId || "—" }}</strong></div>
            <div><span>返回模型</span><strong>{{ result.model || "—" }}</strong></div>
            <div><span>耗时</span><strong>{{ result.elapsedMs }} ms</strong></div>
            <div><span>输入 Token</span><strong>{{ result.promptTokens }}</strong></div>
            <div><span>输出 Token</span><strong>{{ result.completionTokens }}</strong></div>
            <div><span>总 Token</span><strong>{{ result.totalTokens }}</strong></div>
          </div>
          <div class="answer-panel">
            <span>模型回复</span>
            <p>{{ result.content || "模型返回了空内容，请查看原始响应。" }}</p>
          </div>
          <details>
            <summary>查看原始响应</summary>
            <pre class="code-block">{{ result.raw }}</pre>
          </details>
        </template>
        <div v-else class="state-panel empty-state">
          <strong>尚未发送真实请求</strong>
          <p>先验证 Virtual Key、选择该 Key 可访问的企业服务模型，再发送测试消息。</p>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { message } from "ant-design-vue";
import { gatewayBase } from "../api/client";
import {
  checkGatewayHealth,
  createChatCompletion,
  gatewayErrorMessage,
  listGatewayModels,
} from "../api/gateway";

type ModelOption = { label: string; value: string };
type PlaygroundResult = {
  content: string;
  requestId: string;
  model: string;
  elapsedMs: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  raw: string;
};

const apiKey = ref("");
const model = ref<string>();
const prompt = ref("请只回复：TokenSea Playground 调用成功");
const temperature = ref(0.7);
const maxTokens = ref(1024);
const result = ref<PlaygroundResult>();
const loading = ref(false);
const modelsLoading = ref(false);
const keyVerified = ref(false);
const modelError = ref("");
const requestError = ref("");
const models = ref<ModelOption[]>([]);
const healthLoading = ref(false);
const healthError = ref("");
const gatewayReady = ref(false);

async function checkGateway() {
  healthLoading.value = true;
  healthError.value = "";
  gatewayReady.value = false;
  try {
    const status = await checkGatewayHealth();
    gatewayReady.value = status.healthOk && status.readinessOk;
    if (!gatewayReady.value) healthError.value = "Gateway 已启动，但尚未达到可接收业务请求的就绪状态。";
  } catch (e) {
    healthError.value = gatewayErrorMessage(e, "Gateway 状态检查失败");
  } finally {
    healthLoading.value = false;
  }
}

async function loadModels() {
  if (!apiKey.value.trim()) return message.warning("请输入 Virtual Key");
  modelsLoading.value = true;
  modelError.value = "";
  keyVerified.value = false;
  model.value = undefined;
  models.value = [];
  result.value = undefined;
  requestError.value = "";
  try {
    const rows = await listGatewayModels(apiKey.value);
    models.value = rows.map((row) => ({ label: row.id, value: row.id }));
    keyVerified.value = true;
    if (models.value.length === 1) model.value = models.value[0].value;
  } catch (e) {
    modelError.value = gatewayErrorMessage(e, "Virtual Key 验证或模型查询失败");
  } finally {
    modelsLoading.value = false;
  }
}

async function send() {
  if (!apiKey.value.trim() || !model.value || !prompt.value.trim()) {
    return message.warning("请先验证 Virtual Key，并完整选择模型和填写消息");
  }
  loading.value = true;
  requestError.value = "";
  result.value = undefined;
  const startedAt = performance.now();
  try {
    const response = await createChatCompletion(apiKey.value, {
      model: model.value,
      messages: [{ role: "user", content: prompt.value.trim() }],
      temperature: Number(temperature.value),
      max_tokens: Number(maxTokens.value),
      stream: false,
    });
    const usage = response.data?.usage || {};
    result.value = {
      content: response.data?.choices?.[0]?.message?.content || "",
      requestId: response.headers?.["x-request-id"] || response.data?.id || "",
      model: response.data?.model || model.value,
      elapsedMs: Math.round(performance.now() - startedAt),
      promptTokens: Number(usage.prompt_tokens || 0),
      completionTokens: Number(usage.completion_tokens || 0),
      totalTokens: Number(usage.total_tokens || 0),
      raw: JSON.stringify(response.data, null, 2),
    };
  } catch (e) {
    requestError.value = gatewayErrorMessage(e, "真实对话请求失败");
  } finally {
    loading.value = false;
  }
}

watch(apiKey, () => {
  keyVerified.value = false;
  models.value = [];
  model.value = undefined;
  modelError.value = "";
  result.value = undefined;
  requestError.value = "";
});
watch(model, () => {
  result.value = undefined;
  requestError.value = "";
});

onMounted(checkGateway);
</script>

<style scoped>
.playground-page {
  display: grid;
  gap: 16px;
}
.playground-grid {
  align-items: stretch;
}
.request-panel,
.response-card {
  min-width: 0;
}
.gateway-state-row,
.response-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.gateway-state-row {
  padding: 10px 12px;
  margin-bottom: 15px;
  border-radius: 10px;
  background: #f8fafc;
  color: #64748b;
}
.status.muted {
  background: #f1f5f9;
  color: #64748b;
}
.status.danger {
  background: #fee2e2;
  color: #b91c1c;
}
.key-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
}
.field-help,
.security-note {
  display: block;
  margin-top: 7px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}
.request-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.request-options label {
  display: grid;
  gap: 7px;
  color: #334155;
  font-size: 13px;
}
.full-width {
  width: 100%;
}
.response-card {
  display: flex;
  flex-direction: column;
}
.response-head {
  margin-bottom: 16px;
}
.response-head > div {
  display: grid;
  gap: 4px;
}
.response-title {
  font-size: 16px;
  font-weight: 700;
}
.response-head small {
  color: #64748b;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}
.metric-grid > div {
  display: grid;
  gap: 5px;
  padding: 11px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #f8fafc;
}
.metric-grid span,
.answer-panel > span {
  color: #64748b;
  font-size: 12px;
}
.metric-grid strong {
  min-width: 0;
  overflow-wrap: anywhere;
}
.answer-panel {
  padding: 15px;
  margin-bottom: 14px;
  border-radius: 12px;
  background: #f8fafc;
}
.answer-panel p {
  margin: 8px 0 0;
  white-space: pre-wrap;
  line-height: 1.75;
}
details summary {
  cursor: pointer;
  color: #2563eb;
  font-weight: 600;
}
details .code-block {
  margin-top: 12px;
}
@media (max-width: 900px) {
  .key-row,
  .request-options,
  .metric-grid {
    grid-template-columns: 1fr;
  }
}
</style>
