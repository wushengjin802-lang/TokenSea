<template>
  <div class="page console-page calls-page-internal-scroll">
    <header class="page-header">
      <div>
        <h1 class="page-title">
          {{ mode === "usage" ? "用量分析" : "调用日志" }}
        </h1>
        <p class="page-desc">
          {{
            mode === "usage"
              ? "按真实请求归因 Token 与成本。"
              : "从业务请求下钻全部 Attempt 和不可变成本快照。"
          }}
        </p>
      </div>
      <button class="btn" :disabled="loading" @click="load">刷新</button>
    </header>
    <section class="card data-surface">
      <div class="toolbar">
        <div class="filters">
          <input
            v-model.trim="keyword"
            class="input"
            placeholder="请求 ID、租户、模型"
            @keyup.enter="apply"
          /><a-select
            v-model:value="status"
            :options="statusOptions"
            allow-clear
            placeholder="全部状态"
            class="filter-select"
          /><button class="btn" @click="apply">查询</button>
        </div>
      </div>
      <div v-if="error" class="state-panel error-state">
        <strong>调用记录加载失败</strong>
        <p>{{ error }}</p>
        <button class="btn" @click="load">重试</button>
      </div>
      <div v-else-if="loading" class="state-panel">
        <span class="loading-mark"></span><strong>正在读取真实调用记录</strong>
      </div>
      <template v-else
        ><div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th v-for="column in columns" :key="column.key">
                  <button class="sort-button" @click="sortBy(column.key)">
                    {{ column.label }} {{ icon(column.key) }}
                  </button>
                </th>
                <th>详情</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.requestId">
                <td v-for="column in columns" :key="column.key">
                  <span
                    v-if="column.key === 'status'"
                    :class="[
                      'status',
                      row.status === 'SUCCESS' ? 'ok' : 'danger',
                    ]"
                    >{{
                      row.status === "SUCCESS"
                        ? "成功"
                        : row.status === "FAILED"
                          ? "失败"
                          : row.status
                    }}</span
                  ><span v-else>{{ display(row[column.key]) }}</span>
                </td>
                <td>
                  <button class="btn small" @click="openTrace(row)">
                    Attempt 与成本
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!rows.length" class="state-panel empty-state">
          <strong>当前没有调用记录</strong>
          <p>产生真实网关请求后，归因与成本记录会显示在这里。</p>
        </div>
        <footer class="pagination">
          <span>共 {{ total }} 条</span>
          <template v-if="total > size">
          <button
            class="btn"
            :disabled="page === 1"
            @click="
              page--;
              load();
            "
          >
            上一页</button
          ><span>第 {{ page }} / {{ pageCount }} 页</span
          ><button
            class="btn"
            :disabled="page >= pageCount"
            @click="
              page++;
              load();
            "
          >
            下一页
          </button>
          </template>
        </footer></template
      >
    </section>
    <aside v-if="selected" class="trace-drawer" aria-label="调用详情">
      <div class="detail-heading">
        <div>
          <span class="eyebrow">请求追踪</span
          ><strong>{{ selected.requestId }}</strong>
        </div>
        <button class="icon-button" @click="selected = undefined">×</button>
      </div>
      <div v-if="detailError" class="inline-alert danger">
        {{ detailError }}
      </div>
      <div v-else-if="detailLoading" class="state-panel">
        <span class="loading-mark"></span>正在读取 Attempt 与成本快照
      </div>
      <template v-else-if="detail"
        ><section class="trace-summary">
          <div>
            <span>业务结果</span
            ><strong>{{ display(detail.status || selected.status) }}</strong>
          </div>
          <div>
            <span>服务模型</span
            ><strong>{{
              display(detail.modelAlias || selected.modelAlias)
            }}</strong>
          </div>
          <div>
            <span>总成本</span
            ><strong
              >{{
                display(
                  detail.costSnapshot?.costAmount ??
                    detail.costAmount ??
                    selected.costAmount,
                )
              }}
              {{
                detail.costSnapshot?.currency ||
                detail.currency ||
                selected.currency ||
                ""
              }}</strong
            >
          </div>
        </section>
        <section class="detail-section">
          <h3>Attempt 链</h3>
          <div class="attempt-timeline">
            <article
              v-for="(attempt, index) in detail.attempts || []"
              :key="attempt.id || index"
              class="attempt-card"
            >
              <span class="attempt-index">{{
                String(attempt.attemptNo || index + 1).padStart(2, "0")
              }}</span>
              <div>
                <strong>{{
                  attempt.providerInstanceName ||
                  attempt.providerInstanceId ||
                  "—"
                }}</strong>
                <p>{{ attempt.runtimeModelName || "—" }}</p>
              </div>
              <span
                :class="[
                  'status',
                  attempt.status === 'SUCCESS' ? 'ok' : 'danger',
                ]"
                >{{ attempt.status === "SUCCESS" ? "成功" : "失败" }}</span
              >
              <div>
                <small>耗时</small
                ><strong>{{ display(attempt.latencyMs) }} ms</strong>
              </div>
              <div>
                <small>错误码</small
                ><strong>{{ display(attempt.errorCode) }}</strong>
              </div>
            </article>
          </div>
          <div
            v-if="!(detail.attempts || []).length"
            class="state-panel empty-state compact-state"
          >
            该请求没有返回 Attempt 记录
          </div>
        </section>
        <section class="detail-section">
          <h3>成本快照</h3>
          <dl v-if="detail.costSnapshot" class="cost-snapshot-list">
            <div
              v-for="item in costFields"
              :key="item.key"
              class="cost-snapshot-item"
            >
              <dt>{{ item.label }}</dt>
              <dd :title="display(detail.costSnapshot[item.key])">
                {{ display(detail.costSnapshot[item.key]) }}
              </dd>
            </div>
          </dl>
          <div v-else class="state-panel empty-state compact-state">
            该请求没有返回成本快照
          </div>
        </section></template
      >
    </aside>
  </div>
</template>
<script setup lang="ts">
import { computed, ref, onMounted } from "vue";
import { errorMessage, get, queryPage } from "../api/client";
import { formatDateTime } from "../format";
import { stableSortRows } from "../listSort";
const props = withDefaults(defineProps<{ mode?: "logs" | "usage" }>(), {
  mode: "logs",
});
const rows = ref<any[]>([]),
  total = ref(0),
  page = ref(1),
  size = ref(20),
  keyword = ref(""),
  status = ref<string>(),
  sort = ref("createdAt"),
  order = ref<"asc" | "desc">("desc"),
  loading = ref(false),
  error = ref(""),
  selected = ref<any>(),
  detail = ref<any>(),
  detailLoading = ref(false),
  detailError = ref("");
const columns = computed(() =>
  props.mode === "usage"
    ? [
        { key: "requestId", label: "请求 ID" },
        { key: "tenantName", label: "租户" },
        { key: "projectName", label: "项目" },
        { key: "appName", label: "应用" },
        { key: "modelAlias", label: "模型" },
        { key: "totalTokens", label: "Token" },
        { key: "costAmount", label: "成本" },
        { key: "currency", label: "币种" },
        { key: "status", label: "状态" },
        { key: "createdAt", label: "时间" },
      ]
    : [
        { key: "requestId", label: "请求 ID" },
        { key: "tenantName", label: "租户" },
        { key: "apiKeyName", label: "API Key" },
        { key: "modelAlias", label: "模型" },
        { key: "status", label: "业务结果" },
        { key: "errorCode", label: "错误码" },
        { key: "latencyMs", label: "耗时（毫秒）" },
        { key: "createdAt", label: "时间" },
      ],
);
const statusOptions = [
    { label: "成功", value: "SUCCESS" },
    { label: "失败", value: "FAILED" },
  ],
  costFields = [
    { key: "priceVersionId", label: "价格版本" },
    { key: "priceLayer", label: "价格层级" },
    { key: "usageSource", label: "Usage 来源" },
    { key: "costStatus", label: "计费状态" },
    { key: "inputUncachedTokens", label: "输入 Token（缓存未命中）" },
    { key: "cacheReadTokens", label: "输入 Token（缓存命中）" },
    { key: "cacheWriteTokens", label: "输入 Token（缓存写入）" },
    { key: "inputTokensTotal", label: "输入 Token 合计" },
    { key: "outputTokens", label: "普通输出 Token" },
    { key: "reasoningTokens", label: "推理 Token" },
    { key: "completionTokens", label: "输出 Token 合计" },
    { key: "billingBasis", label: "计费对象" },
    { key: "billingQuantity", label: "计费基数" },
    { key: "inputUnitPrice", label: "输入价格（缓存未命中）" },
    { key: "cacheReadUnitPrice", label: "输入价格（缓存命中）" },
    { key: "cacheReadMode", label: "缓存命中价格模式" },
    { key: "cacheWriteUnitPrice", label: "输入价格（缓存写入）" },
    { key: "cacheWriteMode", label: "缓存写入价格模式" },
    { key: "outputUnitPrice", label: "输出价格" },
    { key: "cacheGrossSavings", label: "缓存读取毛节省" },
    { key: "cacheWritePremium", label: "缓存写入溢价" },
    { key: "cacheStorageCost", label: "缓存存储成本" },
    { key: "cacheNetSavings", label: "缓存净节省" },
    { key: "actualCostAmount", label: "实际成本金额" },
    { key: "currency", label: "币种" },
    { key: "priceComponents", label: "生效价格组件" },
    { key: "costComponents", label: "本次成本分项" },
    { key: "usageEvidence", label: "上游 Usage 证据" },
    { key: "sourceRef", label: "来源依据" },
    { key: "calculatorVersion", label: "计算器版本" },
    { key: "createdAt", label: "快照时间" },
  ];
const pageCount = computed(() =>
  Math.max(1, Math.ceil(total.value / size.value)),
);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const result = await queryPage<any>("/api/usage-analysis/details", {
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      status: status.value,
      sort: sort.value,
      order: order.value,
    });
    if (result.serverPaged) {
      rows.value = stableSortRows(result.items, sort.value, order.value);
      total.value = result.total;
    } else {
      let items = result.items;
      if (keyword.value) {
        const q = keyword.value.toLowerCase();
        items = items.filter((row) =>
          JSON.stringify(row).toLowerCase().includes(q),
        );
      }
      if (status.value)
        items = items.filter((row) => row.status === status.value);
      items = stableSortRows(items, sort.value, order.value);
      total.value = items.length;
      rows.value = items.slice(
        (page.value - 1) * size.value,
        page.value * size.value,
      );
    }
  } catch (e) {
    error.value = errorMessage(e);
    rows.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}
async function openTrace(row: any) {
  selected.value = row;
  detail.value = undefined;
  detailError.value = "";
  detailLoading.value = true;
  try {
    const value: any = await get(
      `/api/calls/${encodeURIComponent(row.requestId)}`,
    );
    detail.value = {
      ...value,
      ...(value.usage || {}),
      costSnapshot: Array.isArray(value.costSnapshot)
        ? value.costSnapshot[0]
        : value.costSnapshot,
    };
  } catch (e) {
    detailError.value = errorMessage(e);
  } finally {
    detailLoading.value = false;
  }
}
function apply() {
  page.value = 1;
  load();
}
function sortBy(field: string) {
  sort.value === field
    ? (order.value = order.value === "asc" ? "desc" : "asc")
    : ((sort.value = field), (order.value = "asc"));
  load();
}
function icon(field: string) {
  return sort.value === field ? (order.value === "asc" ? "↑" : "↓") : "";
}
function display(value: any) {
  if (value === null || value === undefined || value === "") return "—";
  if (Array.isArray(value))
    return value.map((item) => formatDateTime(item) || String(item)).join("、");
  return formatDateTime(value) || String(value);
}
onMounted(load);
</script>
<style scoped>
@media (min-width: 981px) {
  .calls-page-internal-scroll {
    display: flex;
    height: calc(100vh - 136px);
    height: calc(100dvh - 136px);
    min-height: 0;
    flex-direction: column;
    overflow: hidden;
  }

  .calls-page-internal-scroll > .page-header {
    flex: 0 0 auto;
  }

  .calls-page-internal-scroll > .data-surface {
    display: flex;
    min-height: 0;
    flex: 1 1 auto;
    flex-direction: column;
    overflow: hidden;
  }

  .calls-page-internal-scroll > .data-surface > .toolbar,
  .calls-page-internal-scroll > .data-surface > .pagination {
    flex: 0 0 auto;
  }

  .calls-page-internal-scroll > .data-surface > .table-wrap {
    min-height: 0;
    flex: 1 1 auto;
    overflow-x: hidden;
    overflow-y: auto;
  }

  .calls-page-internal-scroll > .data-surface > .table-wrap .data-table th {
    position: sticky;
    top: 0;
    z-index: 2;
  }

  .calls-page-internal-scroll > .data-surface > .state-panel {
    min-height: 0;
    flex: 1 1 auto;
  }
}
</style>
