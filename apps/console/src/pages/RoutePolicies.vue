<template>
  <div class="page console-page list-page-internal-scroll">
    <header class="page-header">
      <div>
        <h1 class="page-title">路由策略</h1>
        <p class="page-desc">
          平台管理员保存策略后可直接校验并生效；系统会检查模型映射、能力验证和生产准入状态。
        </p>
      </div>
      <div class="header-actions">
        <button class="btn" :disabled="loading" @click="load">刷新</button
        ><button class="btn primary" @click="openCreate">新建策略</button>
      </div>
    </header>
    <section class="card data-surface">
      <div class="toolbar">
        <div class="filters">
          <input
            v-model.trim="keyword"
            class="input"
            placeholder="搜索策略或服务模型"
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
        <strong>路由策略加载失败</strong>
        <p>{{ error }}</p>
        <button class="btn" @click="load">重试</button>
      </div>
      <div v-else-if="loading" class="state-panel">
        <span class="loading-mark"></span><strong>正在读取真实路由策略</strong>
      </div>
      <template v-else
        ><div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>
                  <button class="sort-button" @click="sortBy('name')">
                    策略名称 {{ icon("name") }}
                  </button>
                </th>
                <th>服务模型</th>
                <th>策略</th>
                <th>故障切换</th>
                <th>候选数</th>
                <th>状态</th>
                <th>创建时间</th>
                <th>更新时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td>{{ row.name }}</td>
                <td>{{ modelLabel(row.modelAlias) }}</td>
                <td>{{ row.strategy === "weighted" ? "加权" : "优先级" }}</td>
                <td>
                  <span :class="['status', fallbackClass(row)]" :title="fallbackHint(row)">{{ fallbackLabel(row) }}</span>
                </td>
                <td>{{ candidateCount(row.config) }}</td>
                <td>
                  <span :class="['status', statusClass(row.status)]">{{
                    statusLabel(row.status)
                  }}</span>
                </td>
                <td>{{ formatDateTime(row.createdAt) }}</td>
                <td>{{ formatDateTime(row.updatedAt) }}</td>
                <td>
                  <div class="row-actions">
                    <button
                      v-if="row.status === 'DRAFT'"
                      class="btn small"
                      @click="openEdit(row)"
                    >
                      编辑</button
                    ><button
                      v-if="['DRAFT', 'PENDING_APPROVAL'].includes(row.status)"
                      class="btn small"
                      @click="action(row, 'activate', '校验并生效')"
                    >
                      校验并生效</button
                    ><button
                      v-if="row.status === 'ACTIVE'"
                      class="btn small"
                      @click="action(row, 'retire', '退役')"
                    >
                      退役
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!rows.length" class="state-panel empty-state">
          <strong>尚无路由策略</strong>
          <p>创建策略前请先准备已批准部署和通过的能力验证记录。</p>
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

    <a-modal
      v-model:open="visible"
      :title="editing ? '编辑路由策略' : '新建路由策略'"
      width="960px"
      :confirm-loading="saving"
      @ok="save"
    >
      <div v-if="formError" class="inline-alert danger">{{ formError }}</div>
      <a-form layout="vertical" class="route-form"
        ><a-form-item label="策略名称" required
          ><a-input v-model:value="form.name" /></a-form-item
        ><a-form-item label="企业服务模型" required
          ><a-select
            v-model:value="form.modelAlias"
            :options="modelOptions"
            show-search
            option-filter-prop="label"
            placeholder="请选择服务模型" /></a-form-item
        ><a-form-item label="策略类型" required
          ><a-select
            v-model:value="form.strategy"
            :options="strategyOptions" /></a-form-item
        ><a-form-item label="故障切换" required
          ><a-select
            v-model:value="form.fallbackEnabled"
            :options="booleanOptions" /></a-form-item
      ></a-form>
      <section class="candidate-editor">
        <div class="candidate-heading">
          <div>
            <span class="eyebrow">候选链路</span>
            <h3>按真实部署配置尝试顺序</h3>
          </div>
          <button class="btn" @click="addCandidate">添加候选</button>
        </div>
        <article
          v-for="(candidate, index) in form.candidates"
          :key="candidate.localId"
          class="candidate-row"
        >
          <span class="candidate-index">{{
            String(index + 1).padStart(2, "0")
          }}</span>
          <div>
            <label>供应商渠道</label
            ><a-select
              v-model:value="candidate.providerInstanceId"
              :options="channelOptions"
              show-search
              option-filter-prop="label"
              placeholder="选择渠道"
              @change="
                candidate.actualModel = undefined;
                candidate.referencePriceLabel = undefined;
              "
            />
          </div>
          <div>
            <label>实际模型</label
            ><a-select
              v-model:value="candidate.actualModel"
              :options="deploymentOptions(candidate.providerInstanceId)"
              show-search
              option-filter-prop="label"
              placeholder="选择已审核模型"
              @change="loadReferencePrice(candidate)"
            />
          </div>
          <div>
            <label>参考价格</label>
            <div class="reference-price" :class="{ loading: candidate.referencePriceLoading }" :title="candidate.referencePriceLabel">
              {{ candidate.referencePriceLabel || "选择实际模型后加载" }}
            </div>
          </div>
          <div>
            <label>{{ form.strategy === "weighted" ? "权重" : "优先级" }}</label
            ><a-input-number
              v-model:value="candidate.rank"
              :min="1"
              style="width: 100%"
            />
          </div>
          <button
            class="icon-button"
            aria-label="删除候选"
            @click="removeCandidate(index)"
          >
            ×
          </button>
        </article>
        <div
          v-if="!form.candidates.length"
          class="state-panel empty-state compact-state"
        >
          至少添加一个真实候选链路
        </div>
      </section>
    </a-modal>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import {
  create,
  errorMessage,
  get,
  postAction,
  queryPage,
  update,
} from "../api/client";
import { formatDateTime } from "../format";
import { stableSortRows } from "../listSort";
import { message } from "ant-design-vue";
type Candidate = {
  localId: string;
  providerInstanceId?: string;
  actualModel?: string;
  referencePriceLabel?: string;
  referencePriceLoading?: boolean;
  rank: number;
};
type Row = Record<string, any>;
type Option = {
  label: string;
  value: any;
  providerInstanceId?: string;
  deploymentId?: string;
  providerModelName?: string;
};
const rows = ref<Row[]>([]),
  total = ref(0),
  page = ref(1),
  size = ref(20),
  keyword = ref(""),
  status = ref<string>(),
  sort = ref("createdAt"),
  order = ref<"asc" | "desc">("desc"),
  loading = ref(false),
  error = ref(""),
  visible = ref(false),
  editing = ref<Row>(),
  saving = ref(false),
  formError = ref("");
const form = reactive<{
  name: string;
  modelAlias?: string;
  strategy: string;
  fallbackEnabled: boolean;
  candidates: Candidate[];
}>({ name: "", strategy: "priority", fallbackEnabled: true, candidates: [] });
const modelOptions = ref<Option[]>([]),
  channelOptions = ref<Option[]>([]),
  deployments = ref<Option[]>([]);
  const statusOptions = [
    { label: "草稿", value: "DRAFT" },
    { label: "待审批", value: "PENDING_APPROVAL" },
    { label: "生效", value: "ACTIVE" },
    { label: "退役", value: "RETIRED" },
  ],
  strategyOptions = [
    { label: "优先级", value: "priority" },
    { label: "加权", value: "weighted" },
  ],
  booleanOptions = [
    { label: "启用", value: true },
    { label: "停用", value: false },
  ];
const pageCount = computed(() =>
  Math.max(1, Math.ceil(total.value / size.value)),
);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const result = await queryPage<Row>("/api/routes", {
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      status: status.value,
      sort: sort.value || undefined,
      order: order.value,
    });
    rows.value = stableSortRows(result.items, sort.value || "id", order.value);
    total.value = result.total;
  } catch (e) {
    error.value = errorMessage(e);
    rows.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}
function cacheModeLabel(value?: string) {
  return ({
    EXPLICIT: "明确价格",
    EXPLICIT_ZERO: "免费",
    INHERIT_INPUT: "沿用未命中价",
    NOT_APPLICABLE: "不适用",
    UNKNOWN: "未确认",
  } as Record<string, string>)[String(value || "UNKNOWN")] || "未确认";
}

async function loadOptions() {
  try {
    const [models, channels, deployed] = await Promise.all([
      queryPage<Row>("/api/platform-models", { size: 500 }),
      queryPage<Row>("/api/provider-instances", { size: 500 }),
      queryPage<Row>("/api/channel-model-deployments", {
        size: 500,
        productionStatus: "APPROVED",
      }),
    ]);
    modelOptions.value = models.items.map((x) => ({
      label: x.displayName || x.platformModelName,
      value: x.platformModelName,
    }));
    channelOptions.value = channels.items.map((x) => ({
      label: x.instanceName,
      value: x.id,
    }));
    deployments.value = deployed.items.map((x) => ({
      label: x.displayName || x.providerModelName,
      value: x.providerModelName,
      providerInstanceId: x.providerInstanceId,
      deploymentId: x.id,
    }));
  } catch (e) {
    formError.value = `业务选项加载失败：${errorMessage(e)}`;
  }
}
function deploymentOptions(id?: string) {
  return deployments.value.filter((x) => !id || x.providerInstanceId === id);
}
async function loadReferencePrice(candidate: Candidate) {
  candidate.referencePriceLabel = undefined;
  if (!candidate.providerInstanceId || !candidate.actualModel) return;
  const deployment = deployments.value.find(
    (item) => item.providerInstanceId === candidate.providerInstanceId && item.value === candidate.actualModel,
  );
  if (!deployment?.deploymentId) return;
  candidate.referencePriceLoading = true;
  try {
    const price = await get<Row>(`/api/model-deployment-governance/${deployment.deploymentId}/reference-price`);
    candidate.referencePriceLabel = price.referencePriceStatus === "MATCHED_REFERENCE"
      ? `参考价 · ${price.currency} · 入 ${price.inputUnitPrice ?? "—"} / 出 ${price.outputUnitPrice ?? "—"}`
      : "暂无自动参考价（不影响路由）";
  } catch {
    candidate.referencePriceLabel = "参考价查询失败（不影响路由）";
  } finally {
    candidate.referencePriceLoading = false;
  }
}
function addCandidate() {
  form.candidates.push({
    localId: crypto.randomUUID(),
    rank: form.candidates.length + 1,
  });
}
function removeCandidate(index: number) {
  form.candidates.splice(index, 1);
}
async function openCreate() {
  editing.value = undefined;
  Object.assign(form, {
    name: "",
    modelAlias: undefined,
    strategy: "priority",
    fallbackEnabled: true,
    candidates: [],
  });
  formError.value = "";
  visible.value = true;
  await loadOptions();
  addCandidate();
}
async function openEdit(row: Row) {
  editing.value = row;
  let parsed: any = {};
  try {
    parsed =
      typeof row.config === "string"
        ? JSON.parse(row.config)
        : row.config || {};
  } catch {}
  Object.assign(form, {
    name: row.name,
    modelAlias: row.modelAlias,
    strategy: row.strategy || "priority",
    fallbackEnabled: Boolean(row.fallbackEnabled),
    candidates: (parsed.candidates || []).map((x: any, index: number) => ({
      localId: crypto.randomUUID(),
      providerInstanceId: x.providerInstanceId,
      actualModel: x.actualModel,
      rank: x.priority ?? x.weight ?? index + 1,
    })),
  });
  formError.value = "";
  visible.value = true;
  await loadOptions();
}
async function save() {
  if (
    !form.name ||
    !form.modelAlias ||
    !form.candidates.length ||
    form.candidates.some(
      (x) => !x.providerInstanceId || !x.actualModel,
    )
  ) {
    formError.value = "请完整选择服务模型、渠道和实际模型";
    return;
  }
  saving.value = true;
  formError.value = "";
  try {
    const payload = {
      name: form.name,
      modelAlias: form.modelAlias,
      strategy: form.strategy,
      fallbackEnabled: form.fallbackEnabled,
      config: JSON.stringify({
        candidates: form.candidates.map((x) => ({
          providerInstanceId: x.providerInstanceId,
          actualModel: x.actualModel,
          [form.strategy === "weighted" ? "weight" : "priority"]: x.rank,
        })),
      }),
    };
    editing.value
      ? await update("/api/routes", editing.value.id, payload)
      : await create("/api/routes", payload);
    visible.value = false;
    message.success("路由策略已保存");
    await load();
  } catch (e) {
    formError.value = errorMessage(e);
  } finally {
    saving.value = false;
  }
}
async function action(row: Row, suffix: string, label: string) {
  try {
    await postAction(`/api/routes/${row.id}/${suffix}`);
    message.success(`${label}成功`);
    await load();
  } catch (e) {
    message.error(errorMessage(e, `路由策略 / ${label}`));
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
function modelLabel(value: any) {
  return (
    modelOptions.value.find((x) => x.value === value)?.label || value || "—"
  );
}
function candidateCount(raw: any) {
  try {
    return (
      (typeof raw === "string" ? JSON.parse(raw) : raw)?.candidates?.length || 0
    );
  } catch {
    return 0;
  }
}
function fallbackLabel(row: Row) {
  return row.status === "RETIRED" ? "停用" : row.fallbackEnabled ? "启用" : "停用";
}
function fallbackClass(row: Row) {
  if (row.status === "RETIRED") return "danger";
  return row.fallbackEnabled ? "ok" : "info";
}
function fallbackHint(row: Row) {
  return row.status === "RETIRED" ? "路由已退役，故障切换不生效。" : undefined;
}
function statusLabel(value: any) {
  return statusOptions.find((x) => x.value === value)?.label || value || "—";
}
function statusClass(value: any) {
  return value === "ACTIVE" ? "ok" : value === "RETIRED" ? "danger" : "warn";
}
onMounted(async () => {
  await loadOptions();
  await load();
});
</script>

<style scoped>
.row-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-width: 150px;
}

.candidate-row > div {
  min-width: 0;
}

.candidate-row :deep(.ant-select) {
  width: 100%;
  min-width: 0;
}

.candidate-row :deep(.ant-select-selection-item) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reference-price {
  height: 32px;
  padding: 6px 10px;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  color: #36526f;
  font-size: 13px;
  line-height: 18px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reference-price.loading { color: #1677ff; }
</style>
