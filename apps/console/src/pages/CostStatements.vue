<template>
  <div class="page console-page list-page-internal-scroll">
    <header class="page-header">
      <div>
        <h1 class="page-title">内部成本单</h1>
        <p class="page-desc">按请求发生月份汇率将原币成本折算为 CNY，生成、确认、调整和导出内部成本单。</p>
      </div>
      <button class="btn" :disabled="loading" @click="load">刷新</button>
    </header>
    <section class="card data-surface">
      <div class="toolbar cost-filter">
        <a-select
          v-model:value="filters.tenantId"
          :options="tenants"
          allow-clear
          show-search
          option-filter-prop="label"
          placeholder="全部租户"
        />
        <div>
          <label>开始时间</label
          ><input v-model="filters.from" class="input" type="datetime-local" />
        </div>
        <div>
          <label>结束时间</label
          ><input v-model="filters.to" class="input" type="datetime-local" />
        </div>
        <div class="fixed-currency"><span>汇总币种</span><strong>CNY 人民币</strong></div><button class="btn primary" @click="generate">生成成本单</button>
      </div>
      <div v-if="error" class="state-panel error-state">
        <strong>成本单加载失败</strong>
        <p>{{ error }}</p>
        <button class="btn" @click="load">重试</button>
      </div>
      <div v-else-if="loading" class="state-panel">
        <span class="loading-mark"></span><strong>正在读取真实成本单</strong>
      </div>
      <template v-else
        ><div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>成本单号</th>
                <th>租户</th>
                <th>周期</th>
                <th>请求数</th>
                <th>实际成本（CNY）</th>
                <th>调整金额</th>
                <th>异常或调整原因</th>
                <th>币种</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td>{{ row.statementNo }}</td>
                <td>{{ tenantName(row.tenantId) }}</td>
                <td>
                  {{ display(row.periodStart) }} 至 {{ display(row.periodEnd) }}
                </td>
                <td>{{ display(row.requestCount) }}</td>
                <td>{{ display(row.actualCost) }}</td>
                <td>{{ display(row.adjustmentAmount) }}</td>
                <td>{{ anomalyReason(row.anomalyDetail) }}</td>
                <td>{{ row.currency }}</td>
                <td>{{ statusLabel(row.status) }}</td>
                <td class="row-actions">
                  <button class="btn small" @click="detail(row)">明细</button
                  ><button
                    v-if="row.status === 'GENERATED'"
                    class="btn small"
                    @click="confirm(row)"
                  >
                    确认</button
                  ><button class="btn small" @click="openAdjust(row)">
                    调整</button
                  ><button class="btn small" @click="exportOne(row)">
                    导出
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!rows.length" class="state-panel empty-state">
          <strong>尚无成本单</strong>
          <p>请选择真实账期后生成。</p>
        </div>
        <footer class="pagination"><span>共 {{ rows.length }} 条</span></footer></template
      >
    </section>
    <a-modal
      v-model:open="adjustVisible"
      title="调整成本单"
      :confirm-loading="saving"
      @ok="adjust"
      ><div v-if="actionError" class="inline-alert danger">
        {{ actionError }}
      </div>
      <a-form layout="vertical"
        ><a-form-item label="调整金额" required
          ><a-input-number
            v-model:value="adjustment.amount"
            style="width: 100%" /></a-form-item
        ><a-form-item label="调整原因" required
          ><a-textarea
            v-model:value="adjustment.reason"
            :rows="4" /></a-form-item></a-form
    ></a-modal>
    <a-modal
      v-model:open="detailVisible"
      title="成本单明细"
      :footer="null"
      width="1280px"
      wrap-class-name="cost-statement-detail-modal"
      ><div v-if="actionError" class="inline-alert danger">
        {{ actionError }}
      </div>
      <div class="table-wrap cost-statement-detail-table-wrap">
        <table class="data-table cost-statement-detail-table">
          <thead>
            <tr>
              <th>项目</th>
              <th>应用</th>
              <th>API Key</th>
              <th>模型</th>
              <th>渠道</th>
              <th>请求数</th>
              <th>输入 Token</th>
              <th>输出 Token</th>
              <th>实际成本（CNY）</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="line in lines" :key="line.id">
              <td>{{ display(line.projectName) }}</td>
              <td>{{ display(line.appName) }}</td>
              <td>{{ display(line.apiKeyName) }}</td>
              <td>{{ display(line.modelAlias) }}</td>
              <td>{{ display(line.providerName) }}</td>
              <td>{{ display(line.requestCount) }}</td>
              <td>{{ display(line.promptTokens) }}</td>
              <td>{{ display(line.completionTokens) }}</td>
              <td>{{ display(line.actualCost) }}</td>
            </tr>
          </tbody>
        </table>
      </div></a-modal
    >
  </div>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import {
  download,
  errorMessage,
  get,
  postAction,
  queryPage,
} from "../api/client";
import { message } from "ant-design-vue";
import { formatDateTime } from "../format";
import { stableSortRows } from "../listSort";
const now = new Date(),
  start = new Date(now.getFullYear(), now.getMonth(), 1),
  local = (d: Date) =>
    new Date(d.getTime() - d.getTimezoneOffset() * 60000)
      .toISOString()
      .slice(0, 16);
const filters = reactive({
    tenantId: undefined as string | undefined,
    from: local(start),
    to: local(now),
    currency: "CNY",
  }),
  tenants = ref<any[]>([]),
  rows = ref<any[]>([]),
  lines = ref<any[]>([]),
  loading = ref(false),
  error = ref(""),
  saving = ref(false),
  adjustVisible = ref(false),
  detailVisible = ref(false),
  selected = ref<any>(),
  actionError = ref(""),
  adjustment = reactive<{ amount?: number; reason: string }>({ reason: "" });
async function load() {
  loading.value = true;
  error.value = "";
  try {
    rows.value = stableSortRows(
      (
        await queryPage("/api/cost-statements/entities", {
          size: 500,
          status: undefined,
          sort: "id",
          order: "asc",
        })
      ).items,
      "id",
      "asc",
    );
  } catch (e) {
    error.value = errorMessage(e);
    rows.value = [];
  } finally {
    loading.value = false;
  }
}
async function generate() {
  saving.value = true;
  try {
    await postAction("/api/cost-statements/generate", {
      tenantId: filters.tenantId,
      from: new Date(filters.from).toISOString(),
      to: new Date(filters.to).toISOString(),
      currency: filters.currency,
    });
    message.success("成本单生成成功");
    await load();
  } catch (e) {
    message.error(errorMessage(e));
  } finally {
    saving.value = false;
  }
}
async function confirm(row: any) {
  try {
    await postAction(`/api/cost-statements/${row.id}/confirm`);
    message.success("成本单确认成功");
    await load();
  } catch (e) {
    message.error(errorMessage(e));
  }
}
function openAdjust(row: any) {
  selected.value = row;
  adjustment.amount = undefined;
  adjustment.reason = "";
  actionError.value = "";
  adjustVisible.value = true;
}
async function adjust() {
  if (adjustment.amount === undefined || !adjustment.reason.trim()) {
    actionError.value = "请填写调整金额和调整原因";
    return;
  }
  saving.value = true;
  try {
    await postAction(
      `/api/cost-statements/${selected.value.id}/adjust`,
      adjustment,
    );
    adjustVisible.value = false;
    message.success("成本单调整成功");
    await load();
  } catch (e) {
    actionError.value = errorMessage(e);
  } finally {
    saving.value = false;
  }
}
async function detail(row: any) {
  actionError.value = "";
  detailVisible.value = true;
  try {
    const data: any = await get(`/api/cost-statements/${row.id}`);
    lines.value = stableSortRows(data.lines || [], "id", "asc");
  } catch (e) {
    lines.value = [];
    actionError.value = errorMessage(e);
  }
}
async function exportOne(row: any) {
  try {
    await download(
      `/api/cost-statements/${row.id}/export`,
      `${row.statementNo || row.id}.csv`,
    );
    message.success("导出成功");
    await load();
  } catch (e) {
    message.error(errorMessage(e));
  }
}
function tenantName(id: any) {
  return tenants.value.find((x) => x.value === id)?.label || id || "全部租户";
}
function display(v: any) {
  if (v === null || v === undefined || v === "") return "—";
  return formatDateTime(v) || String(v);
}
function anomalyReason(value: any) {
  if (!value) return "—";
  try {
    const detail = typeof value === "string" ? JSON.parse(value) : value;
    return detail.adjustmentReason || detail.reason || JSON.stringify(detail);
  } catch {
    return String(value);
  }
}
function statusLabel(v: string) {
  return (
    {
      GENERATED: "已生成",
      CONFIRMED: "已确认",
      ADJUSTED: "已调整",
      ANOMALOUS: "存在异常",
      EXPORTED: "已导出",
    }[v] ||
    v ||
    "—"
  );
}
onMounted(async () => {
  try {
    tenants.value = (
      await queryPage<any>("/api/tenants", { size: 500 })
    ).items.map((x) => ({ label: x.name, value: x.id }));
  } catch {
    tenants.value = [];
  }
  await load();
});
</script>
<style scoped>
.fixed-currency{display:flex;min-width:122px;height:54px;flex-direction:column;justify-content:center;padding:0 12px;border:1px solid #dbe3ee;border-radius:7px;background:#f7faff}.fixed-currency span{color:#7b8799;font-size:10px}.fixed-currency strong{margin-top:2px;color:#244a8f;font-size:12px}
.cost-statement-detail-table-wrap{overflow-x:hidden}
.cost-statement-detail-table{width:100%;min-width:0;table-layout:fixed}
.cost-statement-detail-table th,.cost-statement-detail-table td{padding:8px 7px;overflow-wrap:anywhere;word-break:break-word;white-space:normal;vertical-align:top}
.cost-statement-detail-table th:nth-child(1),.cost-statement-detail-table th:nth-child(2){width:13%}
.cost-statement-detail-table th:nth-child(3){width:15%}
.cost-statement-detail-table th:nth-child(4){width:12%}
.cost-statement-detail-table th:nth-child(5){width:11%}
.cost-statement-detail-table th:nth-child(n+6){width:9%}
:deep(.cost-statement-detail-modal .ant-modal){max-width:calc(100vw - 48px)}
</style>
