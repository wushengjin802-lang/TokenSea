<template>
  <div class="page console-page">
    <header class="page-header">
      <div>
        <div class="eyebrow">控制面数据</div>
        <h1 class="page-title">{{ title }}</h1>
        <p class="page-desc">{{ desc }}</p>
      </div>
      <div class="header-actions">
        <button class="btn" :disabled="loading" @click="load">刷新</button
        ><button v-if="canCreate" class="btn primary" @click="openCreate">
          新建
        </button>
      </div>
    </header>

    <nav v-if="tabs.length" class="asset-tabs" aria-label="数据视图">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :class="['asset-tab', { active: activeTab === tab.key }]"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
      </button>
    </nav>

    <section class="card data-surface" aria-live="polite">
      <div class="toolbar">
        <div class="filters">
          <label class="sr-only" :for="`${uid}-keyword`">搜索</label>
          <input
            :id="`${uid}-keyword`"
            v-model.trim="keyword"
            class="input"
            placeholder="输入关键字筛选"
            @keyup.enter="applyFilters"
          />
          <select
            v-if="activeFields.includes('status')"
            v-model="status"
            class="select"
            aria-label="状态筛选"
            @change="applyFilters"
          >
            <option value="">全部状态</option>
            <option
              v-for="item in statusOptions"
              :key="String(item.value)"
              :value="item.value"
            >
              {{ item.label }}
            </option>
          </select>
          <button class="btn" @click="applyFilters">查询</button
          ><button
            v-if="keyword || status"
            class="btn ghost"
            @click="resetFilters"
          >
            清除
          </button>
        </div>
        <div class="table-meta">
          <span>{{ total }} 条</span
          ><button v-if="exportPath" class="btn" @click="exportRows">
            导出
          </button>
        </div>
      </div>

      <div v-if="error" class="state-panel error-state" role="alert">
        <strong>数据加载失败</strong>
        <p>{{ error }}</p>
        <button class="btn" @click="load">重试</button>
      </div>
      <div v-else-if="loading" class="state-panel" aria-busy="true">
        <span class="loading-mark"></span><strong>正在读取控制面数据</strong>
      </div>
      <template v-else>
        <div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th v-for="field in activeFields" :key="field">
                  <button class="sort-button" @click="sortBy(field)">
                    {{ label(field)
                    }}<span aria-hidden="true">{{ sortIcon(field) }}</span>
                  </button>
                </th>
                <th v-if="hasActions">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in visibleRows"
                :key="row.id"
                tabindex="0"
                @click="openDetail(row)"
                @keydown.enter="openDetail(row)"
              >
                <td v-for="field in activeFields" :key="field">
                  <span
                    v-if="isStatus(field)"
                    :class="['status', statusClass(cellValue(row, field))]"
                    >{{ displayOption(field, cellValue(row, field)) }}</span
                  ><span v-else>{{
                    displayOption(field, cellValue(row, field))
                  }}</span>
                </td>
                <td v-if="hasActions" class="row-actions">
                  <button
                    v-for="action in actions"
                    :key="action"
                    class="btn small"
                    @click.stop="runAction(action, row)"
                  >
                    {{ action }}
                  </button>
                  <button
                    v-if="statePath"
                    class="btn small"
                    @click.stop="openState(row)"
                  >
                    {{ stateLabel || "变更状态" }}
                  </button>
                  <button
                    v-if="canEdit"
                    class="btn small"
                    @click.stop="openEdit(row)"
                  >
                    编辑
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!visibleRows.length" class="state-panel empty-state">
          <strong>{{ emptyTitle || "没有符合条件的数据" }}</strong>
          <p>
            {{
              emptyDescription || "当前接口未返回记录，或记录不符合筛选条件。"
            }}
          </p>
        </div>
        <footer v-if="total > pageSize" class="pagination" aria-label="分页">
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
          <select
            v-model.number="pageSize"
            class="select compact"
            aria-label="每页条数"
            @change="
              page = 1;
              load();
            "
          >
            <option :value="20">20 条</option>
            <option :value="50">50 条</option>
            <option :value="100">100 条</option>
          </select>
        </footer>
      </template>
    </section>

    <aside v-if="selected" class="detail-drawer" aria-label="记录详情">
      <div class="detail-heading">
        <div>
          <span class="eyebrow">控制面记录</span><strong>记录详情</strong>
        </div>
        <button
          class="icon-button"
          aria-label="关闭详情"
          @click="selected = null"
        >
          ×
        </button>
      </div>
      <dl>
        <template v-for="field in activeFields" :key="field"
          ><dt>{{ label(field) }}</dt>
          <dd>
            {{ displayOption(field, cellValue(selected, field)) }}
          </dd></template
        >
      </dl>
      <section
        v-for="section in detailSections || []"
        :key="section.title"
        class="detail-section"
      >
        <h3>{{ section.title }}</h3>
        <div
          v-if="detailLoading[section.title]"
          class="state-panel compact-state"
        >
          <span class="loading-mark"></span>正在读取详情
        </div>
        <div
          v-else-if="detailErrors[section.title]"
          class="inline-alert danger"
        >
          {{ detailErrors[section.title] }}
        </div>
        <template v-else
          ><div
            v-if="Array.isArray(detailData[section.title])"
            class="table-wrap"
          >
            <table class="data-table">
              <thead>
                <tr>
                  <th v-for="field in section.fields" :key="field">
                    {{ section.labels[field] || field }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(row, index) in detailData[section.title]"
                  :key="row.id || index"
                >
                  <td v-for="field in section.fields" :key="field">
                    {{ display(row[field]) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <dl v-else-if="detailData[section.title]">
            <template v-for="field in section.fields" :key="field"
              ><dt>{{ section.labels[field] || field }}</dt>
              <dd>{{ display(detailData[section.title][field]) }}</dd></template
            >
          </dl>
          <div v-else class="state-panel compact-state empty-state">
            当前请求没有{{ section.title }}
          </div></template
        >
      </section>
    </aside>

    <a-modal
      v-model:open="formVisible"
      :title="editing ? `编辑${title}` : `新建${title}`"
      width="720px"
      :confirm-loading="saving"
      @ok="save"
    >
      <div v-if="formError" class="inline-alert danger" role="alert">
        {{ formError }}
      </div>
      <div
        v-if="Object.keys(optionErrors).length"
        class="inline-alert danger"
        role="alert"
      >
        部分业务选项加载失败，请刷新后重试；不会使用本地占位数据。
      </div>
      <a-form layout="vertical" class="compact-modal-form">
        <a-form-item
          v-for="field in formFields"
          :key="field"
          :label="label(field)"
          :required="required(field)"
        >
          <a-select
            v-if="isSelectField(field)"
            v-model:value="form[field]"
            :options="options(field)"
            :mode="selectMode(field)"
            :loading="optionLoading[field]"
            show-search
            option-filter-prop="label"
            allow-clear
            :placeholder="`请选择${label(field)}`"
            @change="onFieldChange(field)"
            @dropdown-visible-change="onDropdown(field, $event)"
          />
          <a-input-number
            v-else-if="numberFields?.includes(field)"
            v-model:value="form[field]"
            :min="0"
            style="width: 100%"
          />
          <a-input
            v-else-if="fieldType(field) === 'datetime'"
            v-model:value="form[field]"
            type="datetime-local"
          />
          <a-input
            v-else-if="fieldType(field) === 'date'"
            v-model:value="form[field]"
            type="date"
          />
          <a-textarea
            v-else-if="['textarea', 'json'].includes(fieldType(field))"
            v-model:value="form[field]"
            :rows="4"
          />
          <a-input v-else v-model:value="form[field]" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="stateVisible"
      :title="stateLabel || '变更状态'"
      :confirm-loading="saving"
      @ok="saveState"
    >
      <a-select
        v-if="statePath === 'status'"
        v-model:value="stateValue"
        :options="options('status')"
        style="width: 100%"
        placeholder="请选择状态"
      />
      <p v-else>确认执行“{{ stateLabel }}”操作？</p>
    </a-modal>

    <a-modal
      v-model:open="relatedVisible"
      :title="relatedTitle"
      :footer="null"
      width="760px"
      ><div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th v-for="field in relatedFields" :key="field">
                {{ relatedLabels?.[field] || label(field) }}
              </th>
              <th v-if="hasRelatedRowAction">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in relatedRows" :key="row.id">
              <td v-for="field in relatedFields" :key="field">
                {{ display(row[field]) }}
              </td>
              <td v-if="hasRelatedRowAction" class="row-actions">
                <button
                  v-if="activePath === '/api/provider-instances'"
                  class="btn small"
                  @click="openSnapshot(row)"
                >查看正文与字段来源</button>
                <button
                  v-if="activePath === '/api/budget-rules'"
                  class="btn small"
                  @click="rollbackBudget(row)"
                >回滚到此版本</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="!relatedRows.length" class="state-panel empty-state">
        没有关联记录
      </div></a-modal
    >
    <a-modal
      v-model:open="secretVisible"
      title="新生成的 API Key"
      :footer="null"
      ><div class="inline-alert danger">
        该密钥只在本次操作中返回，请立即安全保存。
      </div>
      <pre class="secret-value">{{ generatedSecret }}</pre>
      <button class="btn primary" @click="copySecret">复制密钥</button></a-modal
    >
    <a-modal
      v-model:open="snapshotVisible"
      title="受控查看快照正文与字段来源"
      :confirm-loading="snapshotLoading"
      @ok="loadSnapshot"
    >
      <div v-if="snapshotError" class="inline-alert danger">{{ snapshotError }}</div>
      <a-form layout="vertical"><a-form-item label="查看理由" required><a-textarea v-model:value="snapshotReason" :rows="3" placeholder="请填写本次查看的业务理由" /></a-form-item></a-form>
      <template v-if="snapshotData"><h3>快照正文</h3><pre class="secret-value">{{ display(snapshotData.rawPayload) }}</pre><h3>字段来源</h3><pre class="secret-value">{{ display(snapshotData.fieldSources) }}</pre></template>
    </a-modal>
    <a-modal
      v-model:open="actionFormVisible"
      :title="actionName"
      :confirm-loading="actionSaving"
      @ok="submitActionForm"
      ><div v-if="actionError" class="inline-alert danger">
        {{ actionError }}
      </div>
      <a-form layout="vertical"
        ><a-form-item
          v-for="field in currentActionForm?.fields || []"
          :key="field"
          :label="currentActionForm?.labels[field] || field"
          :required="currentActionForm?.requiredFields?.includes(field)"
          ><a-select
            v-if="currentActionForm?.fieldOptions?.[field]"
            v-model:value="actionPayload[field]"
            :options="currentActionForm.fieldOptions[field]"
            show-search
            option-filter-prop="label"
            placeholder="请选择" /><a-input-password
            v-else-if="currentActionForm?.fieldTypes?.[field] === 'password'"
            v-model:value="actionPayload[field]"
            autocomplete="new-password" /><a-input
            v-else
            v-model:value="actionPayload[field]" /></a-form-item></a-form
    ></a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import {
  create,
  download,
  errorMessage,
  get,
  list,
  patchAction,
  postAction,
  queryPage,
  update,
} from "../api/client";
import { message } from "ant-design-vue";
type Option = { label: string; value: any };
type Source = {
  path: string;
  label: string;
  value: string;
  multiple?: boolean;
  dependsOn?: string;
  pathsByValue?: Record<string, string>;
  serialize?: "json" | "array";
};
type Tab = { key: string; label: string; kind: "builtin" | "data" };
type DetailSection = {
  title: string;
  path: string;
  fields: string[];
  labels: Record<string, string>;
};
type ActionForm = {
  fields: string[];
  labels: Record<string, string>;
  requiredFields?: string[];
  fieldOptions?: Record<string, Option[]>;
  fieldTypes?: Record<string, string>;
  rowFields?: Record<string, string>;
};
const props = defineProps<{
  title: string;
  desc?: string;
  apiPath: string;
  fields: string[];
  labels?: Record<string, string>;
  requiredFields?: string[];
  fieldOptions?: Record<string, Option[]>;
  optionSources?: Record<string, Source>;
  fieldTypes?: Record<string, string>;
  numberFields?: string[];
  readonly?: boolean;
  allowCreate?: boolean;
  allowEdit?: boolean;
  editableFields?: string[];
  updateKey?: string;
  allowDelete?: boolean;
  updateMethod?: "put" | "patch";
  tabs?: Tab[];
  defaultTab?: string;
  builtinApiPath?: string;
  builtinFields?: string[];
  builtinLabels?: Record<string, string>;
  builtinRequiredFields?: string[];
  builtinUpdateMethod?: "put" | "patch";
  builtinActions?: string[];
  builtinActionMap?: Record<string, string>;
  actionForms?: Record<string, ActionForm>;
  relatedFields?: string[];
  relatedLabels?: Record<string, string>;
  statePath?: string;
  stateLabel?: string;
  stateMethod?: "post" | "patch";
  statusInForm?: boolean;
  defaultFormValues?: Record<string, any>;
  activationStatus?: any;
  activationPath?: string;
  exportPath?: string;
  emptyTitle?: string;
  emptyDescription?: string;
  detailSections?: DetailSection[];
}>();
const uid = Math.random().toString(36).slice(2);
const rows = ref<any[]>([]),
  total = ref(0),
  loading = ref(false),
  error = ref(""),
  keyword = ref(""),
  status = ref<any>(""),
  page = ref(1),
  pageSize = ref(20),
  sort = ref(""),
  order = ref<"asc" | "desc">("asc"),
  selected = ref<any>(null);
const formVisible = ref(false),
  editing = ref<any>(null),
  saving = ref(false),
  formError = ref(""),
  form = reactive<any>({}),
  dynamicOptions = reactive<Record<string, Option[]>>({}),
  optionLoading = reactive<Record<string, boolean>>({}),
  optionErrors = reactive<Record<string, string>>({});
const detailData = reactive<Record<string, any>>({}),
  detailLoading = reactive<Record<string, boolean>>({}),
  detailErrors = reactive<Record<string, string>>({});
const stateVisible = ref(false),
  stateRow = ref<any>(null),
  stateValue = ref<any>("");
const relatedVisible = ref(false),
  relatedRows = ref<any[]>([]),
  relatedTitle = ref("");
const snapshotVisible = ref(false), snapshotLoading = ref(false), snapshotReason = ref(""), snapshotError = ref(""), snapshotRow = ref<any>(), snapshotData = ref<any>();
const secretVisible = ref(false),
  generatedSecret = ref("");
const actionFormVisible = ref(false),
  actionName = ref(""),
  actionRow = ref<any>(),
  actionPayload = reactive<Record<string, any>>({}),
  actionError = ref(""),
  actionSaving = ref(false);
const currentActionForm = computed(() => props.actionForms?.[actionName.value]);
const tabs = computed(() => props.tabs || []),
  activeTab = ref(props.defaultTab || props.tabs?.[0]?.key || "data"),
  tab = computed(() => tabs.value.find((item) => item.key === activeTab.value)),
  builtin = computed(() => tab.value?.kind === "builtin");
const activePath = computed(() =>
    builtin.value ? props.builtinApiPath || props.apiPath : props.apiPath,
  ),
  activeFields = computed(() =>
    builtin.value ? props.builtinFields || props.fields : props.fields,
  ),
  activeLabels = computed(() =>
    builtin.value ? props.builtinLabels || props.labels : props.labels,
  ),
  requiredFields = computed(() =>
    builtin.value
      ? props.builtinRequiredFields || []
      : props.requiredFields || [],
  );
const canCreate = computed(
    () => !props.readonly && props.allowCreate !== false,
  ),
  canEdit = computed(() => !props.readonly && props.allowEdit !== false),
  actions = computed(() => props.builtinActions || []),
  hasActions = computed(
    () => canEdit.value || actions.value.length > 0 || !!props.statePath,
  ),
  formFields = computed(() =>
    (props.editableFields || activeFields.value).filter(
      (field) =>
        ![
          "id",
          ...(props.statusInForm ? [] : ["status"]),
          "approvalStatus",
          "createdAt",
          "updatedAt",
          "keyPrefix",
          "healthStatus",
          "keyStatus",
        ].includes(field),
    ),
  );
const statePath = computed(() => props.statePath),
  stateLabel = computed(() => props.stateLabel),
  relatedFields = computed(() => props.relatedFields || []),
  hasRelatedRowAction = computed(() => ["/api/provider-instances", "/api/budget-rules"].includes(activePath.value)),
  pageCount = computed(() =>
    Math.max(1, Math.ceil(total.value / pageSize.value)),
  );
const locallyProcessed = computed(() => {
  let value = [...rows.value];
  if (keyword.value) {
    const q = keyword.value.toLowerCase();
    value = value.filter((row) =>
      JSON.stringify(row).toLowerCase().includes(q),
    );
  }
  if (status.value) value = value.filter((row) => row.status === status.value);
  if (sort.value)
    value.sort(
      (a, b) =>
        String(a[sort.value] ?? "").localeCompare(
          String(b[sort.value] ?? ""),
          "zh-CN",
        ) * (order.value === "asc" ? 1 : -1),
    );
  return value;
});
const serverPaged = ref(false);
const visibleRows = computed(() =>
  serverPaged.value
    ? rows.value
    : locallyProcessed.value.slice(
        (page.value - 1) * pageSize.value,
        page.value * pageSize.value,
      ),
);
const statusOptions = computed(() => options("status"));
function label(field: string) {
  return activeLabels.value?.[field] || props.labels?.[field] || field;
}
function source(field: string) {
  return props.optionSources?.[field];
}
function cellValue(row: any, field: string) {
  const snake = field.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  return row?.[field] ?? row?.[snake];
}
function options(field: string) {
  return [
    ...(props.fieldOptions?.[field] || []),
    ...(dynamicOptions[field] || []),
  ].filter(
    (item, index, array) =>
      array.findIndex((other) => other.value === item.value) === index,
  );
}
function display(value: any) {
  if (value === null || value === undefined || value === "") return "—";
  if (Array.isArray(value)) return value.join("、");
  if (typeof value === "boolean") return value ? "是" : "否";
  if (typeof value === "object") return JSON.stringify(value);
  try {
    if (typeof value === "string" && value.startsWith("["))
      return JSON.parse(value).join("、");
  } catch {}
  return String(value);
}
const statusText: Record<string, string> = {
  ACTIVE: "启用",
  SUCCESS: "成功",
  APPROVED: "已通过",
  PENDING: "待处理",
  DRAFT: "草稿",
  SUSPENDED: "暂停",
  DISABLED: "停用",
  FAILED: "失败",
  REJECTED: "已拒绝",
  RETIRED: "退役",
};
function displayOption(field: string, value: any) {
  const option = options(field).find((item) => item.value === value);
  if (option) return option.label;
  if (isStatus(field) && statusText[String(value).toUpperCase()])
    return statusText[String(value).toUpperCase()];
  return display(value);
}
function required(field: string) {
  return requiredFields.value.includes(field);
}
function fieldType(field: string) {
  return (
    props.fieldTypes?.[field] ||
    (["remark", "message", "reason", "suggestion"].includes(field)
      ? "textarea"
      : "text")
  );
}
function isSelectField(field: string) {
  return (
    ["select", "multiselect"].includes(fieldType(field)) ||
    !!source(field) ||
    !!props.fieldOptions?.[field]
  );
}
function selectMode(field: string) {
  return fieldType(field) === "multiselect" || source(field)?.multiple
    ? "multiple"
    : undefined;
}
function isStatus(field: string) {
  return [
    "status",
    "healthStatus",
    "keyStatus",
    "approvalStatus",
    "reviewStatus",
    "capabilityStatus",
    "priceStatus",
    "lifecycleStatus",
    "result",
    "decision",
    "severity",
  ].includes(field);
}
function statusClass(value: any) {
  const v = String(value || "").toUpperCase();
  if (
    [
      "ACTIVE",
      "SUCCESS",
      "APPROVED",
      "启用",
      "生效",
      "健康",
      "已托管",
      "已发布",
      "已启用",
    ].includes(v)
  )
    return "ok";
  if (["FAILED", "REJECTED", "DISABLED", "异常", "停用", "已停用"].includes(v))
    return "danger";
  return "warn";
}
function sortIcon(field: string) {
  return sort.value === field ? (order.value === "asc" ? " ↑" : " ↓") : "";
}
function sortBy(field: string) {
  sort.value === field
    ? (order.value = order.value === "asc" ? "desc" : "asc")
    : ((sort.value = field), (order.value = "asc"));
  page.value = 1;
  load();
}
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const result = await queryPage(activePath.value, {
      page: page.value,
      size: pageSize.value,
      keyword: keyword.value || undefined,
      status: status.value || undefined,
      sort: sort.value || undefined,
      order: order.value,
    });
    rows.value = result.items;
    serverPaged.value = result.serverPaged;
    total.value = result.serverPaged
      ? result.total
      : locallyProcessed.value.length;
    if (selected.value)
      selected.value =
        rows.value.find((row) => row.id === selected.value.id) || null;
  } catch (e) {
    error.value = errorMessage(e);
    rows.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}
function applyFilters() {
  page.value = 1;
  load();
}
function resetFilters() {
  keyword.value = "";
  status.value = "";
  applyFilters();
}
function resolvedSourcePath(field: string) {
  const config = source(field);
  if (!config) return "";
  const base =
    (config.dependsOn && config.pathsByValue?.[form[config.dependsOn]]) ||
    config.path;
  return base.replace(/\{([^}]+)\}/g, (_, key) =>
    encodeURIComponent(form[key] ?? ""),
  );
}
function onDropdown(field: string, open: boolean) {
  if (open) loadFieldOptions(field);
}
function onFieldChange(field: string) {
  for (const [dependent, config] of Object.entries(props.optionSources || {}))
    if (config.dependsOn === field) {
      form[dependent] = config.multiple ? [] : undefined;
      loadFieldOptions(dependent);
    }
}
async function loadFieldOptions(field: string) {
  const config = source(field);
  if (!config) return;
  if (config.dependsOn && !form[config.dependsOn]) {
    dynamicOptions[field] = [];
    return;
  }
  const path = resolvedSourcePath(field);
  if (path.includes("//") || path.endsWith("/")) return;
  optionLoading[field] = true;
  delete optionErrors[field];
  try {
    const result = await queryPage<any>(path, { page: 1, size: 500 });
    dynamicOptions[field] = result.items
      .map((row) => ({
        label: String(
          cellValue(row, config.label) ?? cellValue(row, config.value),
        ),
        value: cellValue(row, config.value),
      }))
      .filter((item) => item.value !== undefined && item.value !== null);
  } catch (e) {
    dynamicOptions[field] = [];
    optionErrors[field] = errorMessage(e);
  } finally {
    optionLoading[field] = false;
  }
}
async function loadOptions() {
  await Promise.all(
    Object.keys(props.optionSources || {}).map(loadFieldOptions),
  );
}
async function openCreate() {
  editing.value = null;
  formError.value = "";
  formFields.value.forEach(
    (field) =>
      (form[field] =
        props.defaultFormValues?.[field] ??
        (selectMode(field) === "multiple" ? [] : undefined)),
  );
  formVisible.value = true;
  await loadOptions();
}
async function openEdit(row: any) {
  editing.value = row;
  formError.value = "";
  formFields.value.forEach((field) => {
    const value = row[field];
    if (selectMode(field) === "multiple" && typeof value === "string") {
      try {
        form[field] = JSON.parse(value);
      } catch {
        form[field] = [];
      }
    } else form[field] = value;
  });
  formVisible.value = true;
  await loadOptions();
}
async function save() {
  const missing = requiredFields.value.filter(
    (field) =>
      form[field] === undefined ||
      form[field] === null ||
      form[field] === "" ||
      (Array.isArray(form[field]) && !form[field].length),
  );
  if (missing.length) {
    formError.value = `请填写：${missing.map(label).join("、")}`;
    return;
  }
  if (activePath.value === "/api/price-versions") {
    const target = ({
      PUBLIC_REFERENCE: "publicModelReferenceId",
      CHANNEL_ACTUAL: "deploymentId",
      INTERNAL_ACCOUNTING: "platformModelId",
    } as Record<string, string>)[String(form.priceLayer)];
    if (!target || !form[target]) {
      formError.value = "请选择与价格层级对应的模型对象";
      return;
    }
    for (const field of [
      "publicModelReferenceId",
      "deploymentId",
      "platformModelId",
    ])
      if (field !== target) form[field] = undefined;
  }
  if (activePath.value === "/api/data-sources") {
    if (form.syncMode === "SCHEDULED" && !form.scheduleExpression) {
      formError.value = "定时同步必须选择同步周期";
      return;
    }
    if (form.sourceType === "PROVIDER_API" && !form.providerInstanceId) {
      formError.value = "供应商接口数据源必须选择供应商渠道";
      return;
    }
    if (form.sourceType === "PUBLIC_REFERENCE" && !form.endpoint) {
      formError.value = "公共参考来源必须填写已批准的 HTTPS 地址";
      return;
    }
    if (form.sourceType === "FILE_IMPORT" && !form.config) {
      formError.value = "受控文件导入必须填写导入配置";
      return;
    }
  }
  if (
    Object.keys(optionErrors).some((field) => formFields.value.includes(field))
  ) {
    formError.value = "业务选项尚未成功加载，请刷新后重试";
    return;
  }
  saving.value = true;
  formError.value = "";
  try {
    const payload = { ...form };
    const requestedStatus = props.statusInForm ? payload.status : undefined;
    if (!editing.value && requestedStatus === "SUSPENDED") {
      formError.value = "新建租户不能直接设为暂停状态";
      return;
    }
    if (props.statusInForm) delete payload.status;
    for (const field of formFields.value.filter(
      (item) => fieldType(item) === "json" && typeof payload[item] === "string",
    )) {
      try {
        payload[field] = JSON.parse(payload[field]);
      } catch {
        formError.value = `${label(field)}必须是有效 JSON`;
        return;
      }
    }
    Object.keys(props.optionSources || {}).forEach((field) => {
      if (
        selectMode(field) === "multiple" &&
        source(field)?.serialize !== "array"
      )
        payload[field] = JSON.stringify(payload[field] || []);
    });
    const saved = editing.value
      ? await update(
        activePath.value,
        String(editing.value[props.updateKey || "id"]),
        payload,
        builtin.value
          ? props.builtinUpdateMethod || "put"
          : props.updateMethod || "put",
      )
      : await create(activePath.value, payload);
    if (
      props.statusInForm &&
      requestedStatus &&
      requestedStatus !== saved?.status
    ) {
      const id = String(saved?.[props.updateKey || "id"] || "");
      if (!id) throw new Error("保存后未返回租户标识");
      const result =
        requestedStatus === props.activationStatus && props.activationPath
          ? await postAction(`${activePath.value}/${id}/${props.activationPath}`)
          : await patchAction(`${activePath.value}/${id}/${statePath.value}`, {
              status: requestedStatus,
            });
      if ((result as any)?.plainTextKey) {
        generatedSecret.value = (result as any).plainTextKey;
        secretVisible.value = true;
      }
    }
    formVisible.value = false;
    message.success("保存成功");
    await load();
  } catch (e) {
    formError.value = errorMessage(e);
  } finally {
    saving.value = false;
  }
}
async function openDetail(row: any) {
  selected.value = row;
  for (const section of props.detailSections || []) {
    detailLoading[section.title] = true;
    detailErrors[section.title] = "";
    detailData[section.title] = null;
    const path = section.path
      .replace(":id", encodeURIComponent(row.id || ""))
      .replace(":requestId", encodeURIComponent(row.requestId || row.id || ""));
    try {
      detailData[section.title] = await get(path);
    } catch (e) {
      detailErrors[section.title] = errorMessage(e);
    } finally {
      detailLoading[section.title] = false;
    }
  }
}
async function runAction(action: string, row: any) {
  if (props.actionForms?.[action]) {
    actionName.value = action;
    actionRow.value = row;
    actionError.value = "";
    Object.keys(actionPayload).forEach((key) => delete actionPayload[key]);
    actionFormVisible.value = true;
    return;
  }
  try {
    await executeAction(action, row, {});
  } catch (e) {
    message.error(errorMessage(e));
  }
}
async function executeAction(
  action: string,
  row: any,
  formPayload: Record<string, any>,
) {
  const raw = props.builtinActionMap?.[action];
  if (!raw) return;
  const match = raw.match(/^(GET|POST|PATCH|DOWNLOAD)\s+/);
  const method = match?.[1] || "POST";
  const suffix = raw
    .replace(/^(GET|POST|PATCH|DOWNLOAD)\s+/, "")
    .replace(":id", row.id);
  const [route, query = ""] = suffix.split("?");
  const path = route.startsWith("/") ? route : `${activePath.value}/${route}`;
  const config = props.actionForms?.[action];
  const rowPayload = Object.fromEntries(
    Object.entries(config?.rowFields || {}).map(([payloadField, rowField]) => [
      payloadField,
      row[rowField],
    ]),
  );
  const payload = {
    ...Object.fromEntries(new URLSearchParams(query)),
    ...rowPayload,
    ...formPayload,
  };
  if (method === "GET") {
    relatedRows.value = await get(path);
    relatedTitle.value = `${action} · ${row.providerName || row.platformModelName || row.id}`;
    relatedVisible.value = true;
  } else if (method === "DOWNLOAD") {
    await download(path, `${props.title}-${row.id}`);
    message.success("导出成功");
  } else {
    const result: any =
      method === "PATCH"
        ? await patchAction(path, payload)
        : await postAction(path, payload);
    if (result?.plainTextKey) {
      generatedSecret.value = result.plainTextKey;
      secretVisible.value = true;
    }
    message.success(`${action}成功`);
    await load();
  }
}
async function submitActionForm() {
  const config = currentActionForm.value;
  if (!config || !actionRow.value) return;
  const missing = (config.requiredFields || []).filter(
    (field) => !actionPayload[field],
  );
  if (missing.length) {
    actionError.value = `请填写：${missing.map((field) => config.labels[field] || field).join("、")}`;
    return;
  }
  actionSaving.value = true;
  actionError.value = "";
  try {
    await executeAction(actionName.value, actionRow.value, actionPayload);
    actionFormVisible.value = false;
  } catch (e) {
    actionError.value = errorMessage(e);
  } finally {
    actionSaving.value = false;
  }
}
function openSnapshot(row: any) { snapshotRow.value = row; snapshotReason.value = ""; snapshotError.value = ""; snapshotData.value = null; snapshotVisible.value = true; }
async function loadSnapshot() { if (!snapshotReason.value.trim()) { snapshotError.value = "请填写查看理由"; return; } snapshotLoading.value = true; snapshotError.value = ""; try { snapshotData.value = await get(`/api/model-snapshots/${snapshotRow.value.id}/raw?reason=${encodeURIComponent(snapshotReason.value.trim())}`); } catch (e) { snapshotError.value = errorMessage(e); } finally { snapshotLoading.value = false; } }
async function rollbackBudget(row: any) { try { await postAction(`/api/budget-rules/versions/${row.id}/rollback`); message.success("预算版本回滚成功"); relatedVisible.value = false; await load(); } catch (e) { message.error(errorMessage(e)); } }
function openState(row: any) {
  stateRow.value = row;
  stateValue.value = row.status;
  stateVisible.value = true;
}
async function saveState() {
  if (!stateRow.value) return;
  saving.value = true;
  try {
    const path = `${activePath.value}/${stateRow.value.id}/${statePath.value}`;
    const payload =
      statePath.value === "status" ? { status: stateValue.value } : {};
    props.stateMethod === "post"
      ? await postAction(path, payload)
      : await patchAction(path, payload);
    stateVisible.value = false;
    message.success(`${stateLabel.value || "状态变更"}成功`);
    await load();
  } catch (e) {
    message.error(errorMessage(e));
  } finally {
    saving.value = false;
  }
}
async function copySecret() {
  await navigator.clipboard.writeText(generatedSecret.value);
  message.success("已复制");
}
async function exportRows() {
  if (!props.exportPath) return;
  try {
    await download(props.exportPath, `${props.title}.csv`, {
      keyword: keyword.value,
      status: status.value,
      sort: sort.value,
      order: order.value,
    });
  } catch (e) {
    message.error(errorMessage(e));
  }
}
onMounted(async () => {
  await loadOptions();
  await load();
});
watch(activeTab, () => {
  page.value = 1;
  load();
});
watch(
  () => props.apiPath,
  () => {
    page.value = 1;
    loadOptions();
    load();
  },
);
</script>
