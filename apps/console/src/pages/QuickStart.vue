<template>
  <div class="page console-page quick-start-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">快速开始</h1>
        <p class="page-desc">
          使用真实 TokenSea Gateway、Virtual Key 和企业服务模型完成接入验证、参数调试，并生成可直接复制的调用示例。
        </p>
      </div>
      <button class="btn" :disabled="healthLoading" @click="checkGateway">
        {{ healthLoading ? "检查中" : "重新检查服务" }}
      </button>
    </header>

    <section class="connection-steps">
      <article class="card step-card">
        <div class="step-head">
          <span class="step-index">1</span>
          <div>
            <span class="eyebrow">Gateway</span>
            <h2>检查服务</h2>
          </div>
          <span :class="['status', gatewayHealthy ? 'ok' : healthError ? 'danger' : 'muted']">
            {{ gatewayHealthy ? "可用" : healthLoading ? "检查中" : healthError ? "异常" : "未检查" }}
          </span>
        </div>
        <dl class="endpoint-list">
          <div>
            <dt>网关地址</dt>
            <dd><code>{{ gatewayBase }}</code></dd>
          </div>
          <div>
            <dt>健康检查</dt>
            <dd><code>GET /health</code></dd>
          </div>
          <div>
            <dt>就绪检查</dt>
            <dd><code>GET /health/readiness</code></dd>
          </div>
        </dl>
        <div v-if="healthError" class="inline-alert danger">{{ healthError }}</div>
        <div v-else-if="gatewayHealthy" class="inline-alert success">
          Gateway 已就绪，可以继续验证 Virtual Key。
        </div>
      </article>

      <article class="card step-card">
        <div class="step-head">
          <span class="step-index">2</span>
          <div>
            <span class="eyebrow">Authentication</span>
            <h2>验证 Virtual Key</h2>
          </div>
          <span :class="['status', keyVerified ? 'ok' : modelError ? 'danger' : 'muted']">
            {{ keyVerified ? "已验证" : modelsLoading ? "验证中" : modelError ? "失败" : "待验证" }}
          </span>
        </div>
        <div class="field">
          <label for="quick-start-key">Virtual Key</label>
          <input
            id="quick-start-key"
            v-model="apiKey"
            class="input"
            type="password"
            autocomplete="off"
            placeholder="输入 ts_ 开头的 TokenSea Virtual Key"
            @keyup.enter="loadModels"
          />
        </div>
        <button
          class="btn primary full-width"
          :disabled="modelsLoading || !apiKey.trim() || !gatewayHealthy"
          @click="loadModels"
        >
          {{ modelsLoading ? "正在验证" : "验证 Key 并查询模型" }}
        </button>
        <div v-if="modelError" class="inline-alert danger">{{ modelError }}</div>
        <div v-else-if="keyVerified" class="inline-alert success">
          Key 验证成功，可访问 {{ models.length }} 个企业服务模型。
        </div>
        <p class="security-note">Virtual Key 仅保存在当前页面内存中，不写入浏览器存储，也不会出现在代码示例里。</p>
      </article>

      <article class="card step-card">
        <div class="step-head">
          <span class="step-index">3</span>
          <div>
            <span class="eyebrow">Model</span>
            <h2>选择服务模型</h2>
          </div>
          <span :class="['status', model ? 'ok' : 'muted']">{{ model ? "已选择" : "待选择" }}</span>
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
            placeholder="先验证 Key，再选择模型"
          />
        </div>
        <div class="endpoint-hint">
          <strong>实际接口</strong>
          <code>GET {{ gatewayBase }}/v1/models</code>
        </div>
        <div v-if="modelsLoaded && !models.length && !modelError" class="state-panel empty-state compact-state">
          当前 Key 没有可访问的已发布企业服务模型。
        </div>
      </article>
    </section>

    <section class="card request-card">
      <div class="section-heading">
        <div>
          <span class="eyebrow">端到端验证</span>
          <h2>发送真实对话请求</h2>
          <p>验证 Key 权限、租户授权、服务模型、路由、供应商渠道和上游模型是否完整可用。</p>
        </div>
        <span class="endpoint-method">POST /v1/chat/completions</span>
      </div>

      <div class="request-grid">
        <div class="request-form">
          <div class="field">
            <label for="quick-start-prompt">测试消息</label>
            <textarea
              id="quick-start-prompt"
              v-model="prompt"
              class="textarea-large"
              placeholder="请输入测试消息"
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
            class="btn primary"
            :disabled="requestLoading || !keyVerified || !model || !prompt.trim()"
            @click="sendTestRequest"
          >
            {{ requestLoading ? "请求中" : "发送测试请求" }}
          </button>
          <div v-if="requestError" class="inline-alert danger">{{ requestError }}</div>
        </div>

        <div class="response-panel">
          <div class="response-head">
            <strong>网关响应</strong>
            <span v-if="requestResult" class="status ok">调用成功</span>
          </div>
          <div v-if="requestResult" class="response-content">
            <div class="response-metrics">
              <div><span>耗时</span><strong>{{ requestResult.elapsedMs }} ms</strong></div>
              <div><span>输入 Token</span><strong>{{ requestResult.promptTokens }}</strong></div>
              <div><span>输出 Token</span><strong>{{ requestResult.completionTokens }}</strong></div>
              <div><span>总 Token</span><strong>{{ requestResult.totalTokens }}</strong></div>
            </div>
            <dl class="response-meta">
              <div><dt>请求 ID</dt><dd>{{ requestResult.requestId || "—" }}</dd></div>
              <div><dt>返回模型</dt><dd>{{ requestResult.responseModel || model }}</dd></div>
            </dl>
            <div class="answer-box">{{ requestResult.content || "上游返回了空内容" }}</div>
            <details>
              <summary>查看原始响应</summary>
              <pre class="code-block compact-code">{{ requestResult.raw }}</pre>
            </details>
          </div>
          <div v-else class="state-panel empty-state">尚未发送真实请求</div>
        </div>
      </div>
    </section>

    <section class="card quickstart-code">
      <div class="section-heading code-heading">
        <div>
          <span class="eyebrow">接入代码</span>
          <h2>复制到业务系统</h2>
          <p>业务系统只需要替换 Base URL、Virtual Key 和企业服务模型名。</p>
        </div>
        <button class="btn" @click="copyCode">复制代码</button>
      </div>
      <nav class="asset-tabs">
        <button
          v-for="item in examples"
          :key="item.key"
          :class="['asset-tab', { active: activeExample === item.key }]"
          @click="activeExample = item.key"
        >
          {{ item.label }}
        </button>
      </nav>
      <pre class="code-block">{{ currentExample.code }}</pre>
      <div class="inline-alert">
        示例不会写入当前页面中的真实 Virtual Key。请通过环境变量或密钥管理服务注入凭证，禁止写入 Git。
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { message } from "ant-design-vue";
import { gatewayBase } from "../api/client";
import {
  checkGatewayHealth,
  createChatCompletion,
  gatewayErrorMessage,
  listGatewayModels,
} from "../api/gateway";

type ModelOption = { label: string; value: string };
type RequestResult = {
  content: string;
  requestId: string;
  responseModel: string;
  elapsedMs: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  raw: string;
};

const healthLoading = ref(false);
const healthError = ref("");
const healthOk = ref(false);
const readinessOk = ref(false);
const apiKey = ref("");
const keyVerified = ref(false);
const models = ref<ModelOption[]>([]);
const modelsLoading = ref(false);
const modelsLoaded = ref(false);
const modelError = ref("");
const model = ref<string>();
const prompt = ref("请只回复：TokenSea 接口调用成功");
const temperature = ref(0.7);
const maxTokens = ref(1024);
const requestLoading = ref(false);
const requestError = ref("");
const requestResult = ref<RequestResult>();
const activeExample = ref("python");

const gatewayHealthy = computed(() => healthOk.value && readinessOk.value);
const apiBase = computed(() => `${gatewayBase}/v1`);
const selectedModel = computed(() => model.value || "YOUR_SERVICE_MODEL");

const examples = computed(() => [
  {
    key: "python",
    label: "Python",
    code: `import os\nfrom openai import OpenAI\n\nclient = OpenAI(\n    base_url="${apiBase.value}",\n    api_key=os.environ["TOKENSEA_API_KEY"],\n)\n\nresponse = client.chat.completions.create(\n    model="${selectedModel.value}",\n    messages=[{"role": "user", "content": "你好"}],\n)\n\nprint(response.choices[0].message.content)`,
  },
  {
    key: "javascript",
    label: "JavaScript",
    code: `import OpenAI from "openai";\n\nconst client = new OpenAI({\n  baseURL: "${apiBase.value}",\n  apiKey: process.env.TOKENSEA_API_KEY,\n});\n\nconst response = await client.chat.completions.create({\n  model: "${selectedModel.value}",\n  messages: [{ role: "user", content: "你好" }],\n});\n\nconsole.log(response.choices[0].message.content);`,
  },
  {
    key: "curl",
    label: "cURL",
    code: `curl "${apiBase.value}/chat/completions" \\\n  -H "Authorization: Bearer $TOKENSEA_API_KEY" \\\n  -H "Content-Type: application/json" \\\n  -d '{\n    "model": "${selectedModel.value}",\n    "messages": [{"role": "user", "content": "你好"}]\n  }'`,
  },
  {
    key: "stream",
    label: "流式调用",
    code: `import os\nfrom openai import OpenAI\n\nclient = OpenAI(\n    base_url="${apiBase.value}",\n    api_key=os.environ["TOKENSEA_API_KEY"],\n)\n\nstream = client.chat.completions.create(\n    model="${selectedModel.value}",\n    messages=[{"role": "user", "content": "你好"}],\n    stream=True,\n)\n\nfor chunk in stream:\n    print(chunk.choices[0].delta.content or "", end="")`,
  },
]);

const currentExample = computed(
  () => examples.value.find((item) => item.key === activeExample.value) || examples.value[0],
);

async function checkGateway() {
  healthLoading.value = true;
  healthError.value = "";
  healthOk.value = false;
  readinessOk.value = false;
  try {
    const result = await checkGatewayHealth();
    healthOk.value = result.healthOk;
    readinessOk.value = result.readinessOk;
    if (!readinessOk.value) healthError.value = "Gateway 已启动，但尚未达到可接收业务请求的就绪状态。";
  } catch (error) {
    healthError.value = gatewayErrorMessage(error, "无法连接 TokenSea Gateway。");
  } finally {
    healthLoading.value = false;
  }
}

async function loadModels() {
  if (!apiKey.value.trim()) return message.warning("请输入 Virtual Key");
  modelsLoading.value = true;
  modelsLoaded.value = true;
  modelError.value = "";
  keyVerified.value = false;
  model.value = undefined;
  models.value = [];
  requestResult.value = undefined;
  requestError.value = "";
  try {
    const response = await listGatewayModels(apiKey.value);
    const uniqueModels: string[] = Array.from(
      new Set<string>(
        response
          .map((row) => String(row?.id || "").trim())
          .filter((id) => Boolean(id)),
      ),
    ).sort((left, right) => left.localeCompare(right));
    models.value = uniqueModels.map((id) => ({ label: id, value: id }));
    keyVerified.value = true;
    if (models.value.length === 1) model.value = models.value[0].value;
  } catch (error) {
    modelError.value = gatewayErrorMessage(error, "Virtual Key 验证失败或模型列表查询失败。");
  } finally {
    modelsLoading.value = false;
  }
}

async function sendTestRequest() {
  if (!apiKey.value.trim() || !model.value || !prompt.value.trim()) {
    return message.warning("请先验证 Key，并完整选择模型和填写测试消息");
  }
  requestLoading.value = true;
  requestError.value = "";
  requestResult.value = undefined;
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
    requestResult.value = {
      content: response.data?.choices?.[0]?.message?.content || "",
      requestId: response.headers?.["x-request-id"] || response.data?.id || "",
      responseModel: response.data?.model || "",
      elapsedMs: Math.round(performance.now() - startedAt),
      promptTokens: Number(usage.prompt_tokens || 0),
      completionTokens: Number(usage.completion_tokens || 0),
      totalTokens: Number(usage.total_tokens || 0),
      raw: JSON.stringify(response.data, null, 2),
    };
  } catch (error) {
    requestError.value = gatewayErrorMessage(error, "真实对话请求失败，请检查模型权限、路由和供应商状态。");
  } finally {
    requestLoading.value = false;
  }
}

async function copyCode() {
  await navigator.clipboard.writeText(currentExample.value.code);
  message.success("代码已复制");
}

watch(apiKey, () => {
  keyVerified.value = false;
  modelsLoaded.value = false;
  models.value = [];
  model.value = undefined;
  modelError.value = "";
  requestResult.value = undefined;
  requestError.value = "";
});
watch(model, () => {
  requestResult.value = undefined;
  requestError.value = "";
});

onMounted(checkGateway);
</script>

<style scoped>
.quick-start-page {
  display: grid;
  gap: 18px;
}
.connection-steps {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}
.step-card {
  display: flex;
  flex-direction: column;
  gap: 15px;
  min-height: 270px;
}
.step-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.step-head h2,
.section-heading h2 {
  margin: 2px 0 0;
  font-size: 18px;
}
.step-head .status {
  margin-left: auto;
}
.step-index {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: linear-gradient(135deg, #2563eb, #06b6d4);
  color: #fff;
  font-weight: 700;
}
.status.muted {
  background: #f1f5f9;
  color: #64748b;
}
.status.danger {
  background: #fee2e2;
  color: #b91c1c;
}
.endpoint-list,
.response-meta {
  display: grid;
  gap: 9px;
  margin: 0;
}
.endpoint-list div,
.response-meta div {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 10px;
}
.endpoint-list dt,
.response-meta dt {
  color: #64748b;
}
.endpoint-list dd,
.response-meta dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}
.full-width {
  width: 100%;
}
.security-note {
  margin: 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}
.endpoint-hint {
  display: grid;
  gap: 7px;
  padding: 12px;
  border-radius: 10px;
  background: #f8fafc;
}
.endpoint-hint code {
  overflow-wrap: anywhere;
}
.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}
.section-heading p {
  margin: 7px 0 0;
  color: #64748b;
}
.endpoint-method {
  flex: none;
  padding: 7px 10px;
  border-radius: 8px;
  background: #eff6ff;
  color: #1d4ed8;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.request-grid {
  display: grid;
  grid-template-columns: minmax(300px, 0.8fr) minmax(0, 1.2fr);
  gap: 18px;
}
.request-form,
.response-panel {
  min-width: 0;
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
.response-panel {
  padding: 16px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #f8fafc;
}
.response-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.response-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}
.response-metrics div {
  display: grid;
  gap: 4px;
  padding: 10px;
  border-radius: 9px;
  background: #fff;
}
.response-metrics span {
  color: #64748b;
  font-size: 12px;
}
.answer-box {
  margin: 14px 0;
  padding: 14px;
  border-radius: 10px;
  background: #fff;
  white-space: pre-wrap;
  line-height: 1.7;
}
.compact-code {
  max-height: 260px;
  margin-top: 10px;
  overflow: auto;
}
.code-heading {
  margin-bottom: 10px;
}
.inline-alert.success {
  border-color: #bbf7d0;
  background: #f0fdf4;
  color: #166534;
}
@media (max-width: 1100px) {
  .connection-steps {
    grid-template-columns: 1fr;
  }
  .step-card {
    min-height: auto;
  }
}
@media (max-width: 800px) {
  .request-grid {
    grid-template-columns: 1fr;
  }
  .response-metrics,
  .request-options {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .section-heading {
    flex-direction: column;
  }
}
@media (max-width: 520px) {
  .request-options {
    grid-template-columns: 1fr;
  }
}
</style>
