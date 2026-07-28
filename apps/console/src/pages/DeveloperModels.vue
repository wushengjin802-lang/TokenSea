<template>
  <div class="page console-page list-page-internal-scroll developer-models-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">服务模型列表</h1>
        <p class="page-desc">
          使用真实 Virtual Key 查询 Gateway <code>/v1/models</code>，结果按租户授权与 Key 权限实时过滤。
        </p>
      </div>
      <button class="btn" :disabled="healthLoading" @click="checkGateway">
        {{ healthLoading ? "检查中" : "检查网关" }}
      </button>
    </header>

    <section class="card gateway-summary">
      <div>
        <span class="summary-label">Gateway 地址</span>
        <code>{{ gatewayBase }}</code>
      </div>
      <div>
        <span class="summary-label">服务状态</span>
        <span :class="['status', gatewayReady ? 'ok' : healthError ? 'danger' : 'muted']">
          {{ gatewayReady ? "已就绪" : healthLoading ? "检查中" : healthError ? "异常" : "未检查" }}
        </span>
      </div>
      <div v-if="lastQueriedAt">
        <span class="summary-label">最近查询</span>
        <strong>{{ lastQueriedAt }}</strong>
      </div>
      <div v-if="queryElapsedMs">
        <span class="summary-label">接口耗时</span>
        <strong>{{ queryElapsedMs }} ms</strong>
      </div>
    </section>

    <div v-if="healthError" class="inline-alert danger">{{ healthError }}</div>

    <section class="card data-surface">
      <div class="toolbar">
        <div class="filters developer-key-filter">
          <input
            v-model="apiKey"
            class="input key-input"
            type="password"
            autocomplete="off"
            placeholder="输入 ts_ 开头的 TokenSea Virtual Key"
            @keyup.enter="load"
          />
          <input
            v-model.trim="keyword"
            class="input"
            placeholder="按模型标识筛选"
            @keyup.enter="load"
          />
          <button class="btn primary" :disabled="loading || !apiKey.trim()" @click="load">
            {{ loading ? "查询中" : "验证 Key 并查询模型" }}
          </button>
        </div>
        <span class="security-note">Virtual Key 仅保存在当前页面内存，不写入浏览器存储。</span>
      </div>

      <div v-if="error" class="state-panel error-state">
        <strong>模型查询失败</strong>
        <p>{{ error }}</p>
        <button class="btn" :disabled="loading" @click="load">重试</button>
      </div>
      <div v-else-if="loading" class="state-panel">
        <span class="loading-mark"></span>
        <strong>正在验证 Virtual Key 并查询可访问模型</strong>
      </div>
      <template v-else>
        <div v-if="queried && filtered.length" class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>
                  <button class="sort-button" @click="toggleSort">
                    模型标识 {{ ascending ? "↑" : "↓" }}
                  </button>
                </th>
                <th>对象类型</th>
                <th>归属</th>
                <th>创建时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in paged" :key="row.id">
                <td><code>{{ row.id }}</code></td>
                <td>{{ objectLabel(row.object) }}</td>
                <td>{{ ownerLabel(row.owned_by) }}</td>
                <td>{{ createdLabel(row.created) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="queried && !filtered.length" class="state-panel empty-state">
          <strong>当前 Key 没有可访问模型</strong>
          <p>请检查租户可用服务模型、Key 允许调用的服务模型、企业服务模型发布状态及路由状态。</p>
        </div>
        <div v-if="!queried" class="state-panel empty-state">
          <strong>请输入 Virtual Key</strong>
          <p>查询结果来自 Gateway，不使用控制面中的全量模型目录。</p>
        </div>
        <footer v-if="queried" class="pagination">
          <span>共 {{ filtered.length }} 个可访问模型</span>
          <template v-if="filtered.length > size">
            <button class="btn" :disabled="page === 1" @click="page--">上一页</button>
            <span>第 {{ page }} / {{ pageCount }} 页</span>
            <button class="btn" :disabled="page >= pageCount" @click="page++">下一页</button>
          </template>
        </footer>
      </template>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { gatewayBase } from "../api/client";
import {
  checkGatewayHealth,
  gatewayErrorMessage,
  listGatewayModels,
  type GatewayModel,
} from "../api/gateway";

const apiKey = ref("");
const keyword = ref("");
const models = ref<GatewayModel[]>([]);
const loading = ref(false);
const error = ref("");
const queried = ref(false);
const ascending = ref(true);
const page = ref(1);
const size = 20;
const healthLoading = ref(false);
const healthError = ref("");
const gatewayReady = ref(false);
const queryElapsedMs = ref(0);
const lastQueriedAt = ref("");

const filtered = computed(() =>
  models.value
    .filter((row) => row.id.toLowerCase().includes(keyword.value.toLowerCase()))
    .sort((left, right) => left.id.localeCompare(right.id) * (ascending.value ? 1 : -1)),
);
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / size)));
const paged = computed(() => filtered.value.slice((page.value - 1) * size, page.value * size));

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

async function load() {
  queried.value = true;
  page.value = 1;
  if (!apiKey.value.trim()) {
    error.value = "请输入真实 Virtual Key";
    models.value = [];
    return;
  }
  loading.value = true;
  error.value = "";
  queryElapsedMs.value = 0;
  const startedAt = performance.now();
  try {
    models.value = await listGatewayModels(apiKey.value);
    queryElapsedMs.value = Math.round(performance.now() - startedAt);
    lastQueriedAt.value = new Date().toLocaleString("zh-CN", { hour12: false });
  } catch (e) {
    error.value = gatewayErrorMessage(e, "服务模型列表查询失败");
    models.value = [];
  } finally {
    loading.value = false;
  }
}

function toggleSort() {
  ascending.value = !ascending.value;
}
function objectLabel(value?: string) {
  return String(value || "").toLowerCase() === "model" ? "企业服务模型" : value || "—";
}
function ownerLabel(value?: string) {
  const normalized = String(value || "").toLowerCase();
  if (["tokensea", "system", "openai"].includes(normalized)) return "TokenSea 平台";
  return value || "TokenSea 平台";
}
function createdLabel(value?: number) {
  if (!value) return "—";
  const timestamp = value > 10_000_000_000 ? value : value * 1000;
  return new Date(timestamp).toLocaleString("zh-CN", { hour12: false });
}

watch(apiKey, () => {
  queried.value = false;
  models.value = [];
  error.value = "";
  queryElapsedMs.value = 0;
  lastQueriedAt.value = "";
});
watch(keyword, () => {
  page.value = 1;
});

onMounted(checkGateway);
</script>

<style scoped>
.developer-models-page {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.developer-models-page .page-header {
  margin-bottom: 0;
}
.developer-models-page .page-desc {
  margin-top: 5px;
}
.gateway-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px 24px;
  padding: 12px 16px;
}
.gateway-summary > div {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}
.summary-label,
.security-note {
  color: #64748b;
  font-size: 12px;
}
.gateway-summary code {
  overflow-wrap: anywhere;
}
.status.muted {
  background: #f1f5f9;
  color: #64748b;
}
.status.danger {
  background: #fee2e2;
  color: #b91c1c;
}
.toolbar {
  align-items: center;
  gap: 14px;
}
.developer-key-filter {
  flex: 1;
}
.key-input {
  min-width: 330px;
}
@media (max-width: 920px) {
  .developer-key-filter {
    width: 100%;
  }
  .key-input {
    min-width: 0;
  }
}
</style>
