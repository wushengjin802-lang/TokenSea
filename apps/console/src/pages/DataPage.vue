<template>
  <div :class="['page', 'console-page', { 'data-page-internal-scroll': internalScroll, 'provider-channel-page': activePath === '/api/provider-instances', 'model-discovery-candidate-page': activePath === '/api/model-discovery-candidates' }]">
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ title }}</h1>
        <p :class="['page-desc', { 'page-desc-nowrap': descNoWrap }]">{{ desc }}</p>
        <slot name="header-after-description" />
      </div>
      <div class="header-actions">
        <button class="btn" :disabled="loading" @click="load">刷新</button
        ><button v-if="canCreate" class="btn primary" @click="openCreate">
          {{ createLabel || "新建" }}
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
        <div v-if="exportPath" class="table-meta">
          <button class="btn" @click="exportRows">
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
        <div :class="['table-wrap', { 'table-wrap-no-horizontal-scroll': noHorizontalScroll, 'provider-channel-table-wrap': activePath === '/api/provider-instances' }]">
          <table :class="['data-table', { 'data-table-no-horizontal-scroll': noHorizontalScroll, 'provider-channel-table': activePath === '/api/provider-instances' }]">
            <thead>
              <tr>
                <th
                  v-for="field in activeFields"
                  :key="field"
                  :class="columnClass(field)"
                  :style="columnStyle(field)"
                >
                  <button class="sort-button" @click="sortBy(field)">
                    {{ label(field)
                    }}<span aria-hidden="true">{{ sortIcon(field) }}</span>
                  </button>
                </th>
                <th v-if="hasActions" class="actions-column" :style="actionColumnStyle">操作</th>
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
                <td
                  v-for="field in activeFields"
                  :key="field"
                  :class="columnClass(field)"
                  :style="columnStyle(field)"
                >
                  <span
                    v-if="isStatus(field)"
                    :class="['status', statusClass(cellValue(row, field))]"
                    >{{ displayOption(field, cellValue(row, field)) }}</span
                  ><span
                    v-else
                    :class="{ 'cell-url': isUrlField(field) }"
                    :title="
                      isUrlField(field)
                        ? displayOption(field, cellValue(row, field))
                        : undefined
                    "
                    >{{ displayOption(field, cellValue(row, field)) }}</span
                  >
                </td>
                <td
                  v-if="hasActions"
                  :class="['actions-column', { 'provider-actions-column': activePath === '/api/provider-instances' }]"
                  :style="actionColumnStyle"
                >
                  <div
                    :class="['row-action-buttons', { 'provider-row-action-buttons': activePath === '/api/provider-instances' }]"
                  >
                  <button
                    v-for="action in availableActions(row)"
                    :key="action"
                    :class="['btn', 'small', { 'action-pending': isActionLoading(action, row) }]"
                    :disabled="isActionLoading(action, row)"
                    :aria-busy="isActionLoading(action, row)"
                    :title="isActionLoading(action, row) ? `${actionTooltip(action)}（正在执行）` : actionTooltip(action)"
                    @click.stop="runAction(action, row)"
                  >
                    <template v-if="isActionLoading(action, row)">
                      <span class="action-spinner" aria-hidden="true"></span><span>进行中</span>
                    </template>
                    <span v-else>{{ action }}</span>
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
                  </div>
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
        <footer class="pagination" aria-label="分页">
          <span>共 {{ total }} 条</span>
          <template v-if="total > pageSize">
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
          </template>
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
        <template v-for="field in detailDisplayFields" :key="field"
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
      <a-form
        layout="vertical"
        :class="['compact-modal-form', { 'form-compact': compactForm }]"
      >
        <a-form-item
          v-for="field in formFields"
          :key="field"
          :class="{ 'form-item-wide': fieldType(field) === 'json' }"
          :label="label(field)"
          :required="required(field)"
        >
          <a-select
            v-if="isSelectField(field)"
            v-model:value="form[field]"
            :options="options(field)"
            :mode="selectMode(field)"
            :loading="optionLoading[field]"
            :disabled="fieldDisabled(field)"
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
            :disabled="fieldDisabled(field)"
            style="width: 100%"
          />
          <a-input
            v-else-if="fieldType(field) === 'datetime'"
            v-model:value="form[field]"
            :disabled="fieldDisabled(field)"
            type="datetime-local"
          />
          <a-input
            v-else-if="fieldType(field) === 'date'"
            v-model:value="form[field]"
            :disabled="fieldDisabled(field)"
            type="date"
          />
          <a-textarea
            v-else-if="['textarea', 'json'].includes(fieldType(field))"
            v-model:value="form[field]"
            :disabled="fieldDisabled(field)"
            :rows="compactForm ? 3 : 4"
          />
          <a-input
            v-else
            v-model:value="form[field]"
            :disabled="fieldDisabled(field)"
            :placeholder="fieldPlaceholder(field)"
          />
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
      :width="priceParsePreview ? '920px' : isWideRelatedResult ? '1180px' : '760px'"
      >
      <section v-if="priceParsePreview" class="price-parse-report">
        <div class="price-parse-report-head">
          <div>
            <strong>价格源解析报告</strong>
            <span>{{ priceParseEvidence.pageTitle || '官方价格页面' }}</span>
          </div>
          <span class="parse-status" :class="parseStatusClass(priceParsePreview.parseStatus)">
            {{ parseStatusText(priceParsePreview.parseStatus) }}
          </span>
        </div>
        <div class="price-parse-metrics">
          <article><span>HTTP 状态</span><strong>{{ priceParsePreview.httpStatus ?? '—' }}</strong></article>
          <article><span>页面表格</span><strong>{{ priceParsePreview.tableCount ?? 0 }}</strong></article>
          <article><span>匹配价格表</span><strong>{{ priceParsePreview.matchedTableCount ?? 0 }}</strong></article>
          <article><span>生成价格记录</span><strong>{{ priceParsePreview.recordsNormalized ?? 0 }}</strong></article>
        </div>
        <div class="price-parse-meta">
          <div><span>内容类型</span><strong>{{ priceParsePreview.contentType || '—' }}</strong></div>
          <div><span>响应大小</span><strong>{{ formatBytes(priceParsePreview.responseBytes) }}</strong></div>
          <div><span>跳过表格</span><strong>{{ priceParsePreview.skippedTableCount ?? 0 }}</strong></div>
          <div><span>发现定价子页</span><strong>{{ priceParsePreview.discoveredPricePages?.length || 0 }}</strong></div>
          <div class="wide"><span>页面结构指纹</span><code>{{ priceParsePreview.structureFingerprint || '—' }}</code></div>
        </div>
        <div v-if="priceParsePreview.headlessRecommended" class="inline-alert warning">
          当前普通 HTTP 内容可能缺少动态渲染后的价格，建议使用 Headless 获取模式重新测试。
        </div>
        <div v-if="priceParseWarnings.length" class="price-parse-warning-list">
          <strong>解析提示</strong>
          <ul><li v-for="warning in priceParseWarnings" :key="warning">{{ warning }}</li></ul>
        </div>
        <details v-if="priceTableDiagnostics.length" class="price-parse-details" open>
          <summary>表格识别明细（{{ priceTableDiagnostics.length }}）</summary>
          <div class="table-wrap">
            <table class="data-table compact-diagnostic-table">
              <thead><tr><th>表格</th><th>识别结果</th><th>结构</th><th>说明</th></tr></thead>
              <tbody>
                <tr v-for="item in priceTableDiagnostics.slice(0, 20)" :key="item.tableIndex">
                  <td>table[{{ item.tableIndex }}]</td>
                  <td><span class="parse-status" :class="item.matched ? 'success' : 'neutral'">{{ item.matched ? '价格表' : '已跳过' }}</span></td>
                  <td>{{ diagnosticStructure(item) }}</td>
                  <td>{{ item.reason || item.heading || '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-if="priceTableDiagnostics.length > 20" class="diagnostic-more">
            仅展示前 20 条，共 {{ priceTableDiagnostics.length }} 条诊断记录。
          </p>
        </details>
        <details v-if="priceParsePreview.sample?.length" class="price-parse-details">
          <summary>标准化价格样例（{{ priceParsePreview.sample.length }}）</summary>
          <pre class="price-parse-json">{{ JSON.stringify(priceParsePreview.sample, null, 2) }}</pre>
        </details>
      </section>
      <div v-else :class="['table-wrap', { 'publish-check-table-wrap': isPublishCheck, 'effective-price-table-wrap': isEffectivePrice }]">
        <table :class="['data-table', { 'publish-check-table': isPublishCheck, 'effective-price-table': isEffectivePrice }]">
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
                {{ relatedDisplay(row, field) }}
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
            v-else-if="currentActionForm?.fieldTypes?.[field] === 'datetime'"
            v-model:value="actionPayload[field]"
            type="datetime-local" /><a-input
            v-else
            v-model:value="actionPayload[field]" /></a-form-item></a-form
    ></a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { formatDateTime } from "../format";
import { stableSortRows } from "../listSort";
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
  filterBy?: string;
  query?: Record<string, string | number | boolean | undefined>;
  pathsByValue?: Record<string, string>;
  serialize?: "json" | "array";
  autofill?: Record<string, string>;
};
type FieldCondition = {
  field: string;
  equals?: any;
  in?: any[];
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
type ActionPrecondition = {
  path: string;
  idField: string;
  field: string;
  equals: unknown;
  message: string;
};
const props = withDefaults(defineProps<{
  title: string;
  desc?: string;
  descNoWrap?: boolean;
  apiPath: string;
  queryParams?: Record<string, string | number | boolean | undefined>;
  fields: string[];
  labels?: Record<string, string>;
  requiredFields?: string[];
  fieldOptions?: Record<string, Option[]>;
  fieldPresets?: Record<string, Record<string, Record<string, any>>>;
  optionSources?: Record<string, Source>;
  fieldTypes?: Record<string, string>;
  fieldVisibility?: Record<string, FieldCondition>;
  conditionalRequiredFields?: Record<string, FieldCondition>;
  numberFields?: string[];
  readonly?: boolean;
  allowCreate?: boolean;
  createLabel?: string;
  allowEdit?: boolean;
  editableFields?: string[];
  readonlyFields?: string[];
  immutableFields?: string[];
  detailFields?: string[];
  compactForm?: boolean;
  internalScroll?: boolean;
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
  refreshOnErrorActions?: string[];
  actionPreconditions?: Record<string, ActionPrecondition>;
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
  defaultSort?: string;
  defaultOrder?: "asc" | "desc";
  sortFallbackFields?: string[];
  columnWidths?: Record<string, string>;
  actionColumnWidth?: string;
  noHorizontalScroll?: boolean;
  showBottomTotal?: boolean;
  hideTopTotal?: boolean;
  showCellTooltip?: boolean;
}>(), {
  allowCreate: true,
  allowEdit: true,
  internalScroll: true,
  noHorizontalScroll: true,
  showBottomTotal: true,
  hideTopTotal: true,
  showCellTooltip: true,
});
const uid = Math.random().toString(36).slice(2);
const rows = ref<any[]>([]),
  total = ref(0),
  loading = ref(false),
  error = ref(""),
  keyword = ref(""),
  status = ref<any>(""),
  page = ref(1),
  pageSize = ref(20),
  sort = ref(props.defaultSort || "id"),
  order = ref<"asc" | "desc">(props.defaultOrder || "asc"),
  selected = ref<any>(null);
const formVisible = ref(false),
  editing = ref<any>(null),
  saving = ref(false),
  formError = ref(""),
  form = reactive<any>({}),
  dynamicOptions = reactive<Record<string, Option[]>>({}),
  optionRows = reactive<Record<string, Record<string, any>>>({}),
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
  relatedTitle = ref(""),
  relatedAction = ref("");
const snapshotVisible = ref(false), snapshotLoading = ref(false), snapshotReason = ref(""), snapshotError = ref(""), snapshotRow = ref<any>(), snapshotData = ref<any>();
const secretVisible = ref(false),
  generatedSecret = ref("");
const actionFormVisible = ref(false),
  actionName = ref(""),
  actionRow = ref<any>(),
  actionPayload = reactive<Record<string, any>>({}),
  actionError = ref(""),
  actionSaving = ref(false),
  actionLoading = ref<Record<string, true>>({});
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
  detailDisplayFields = computed(() => props.detailFields || activeFields.value),
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
  compactForm = computed(() => props.compactForm === true),
  noHorizontalScroll = computed(() => props.noHorizontalScroll === true),
  hasActions = computed(
    () => canEdit.value || actions.value.length > 0 || !!props.statePath,
  ),
  actionColumnStyle = computed(() => {
    const width = props.actionColumnWidth;
    return width ? { width, minWidth: width, maxWidth: width } : undefined;
  }),
  formFields = computed(() =>
    (props.editableFields || activeFields.value).filter(
      (field) =>
        ![
          "id",
          ...(props.statusInForm ? [] : ["status"]),
          "approvalStatus",
          "routeStatus",
          "createdAt",
          "updatedAt",
          "keyPrefix",
          "healthStatus",
          "keyStatus",
        ].includes(field) && fieldConditionMatches(props.fieldVisibility?.[field]),
    ),
  );
const statePath = computed(() => props.statePath),
  stateLabel = computed(() => props.stateLabel),
  relatedFields = computed(() => props.relatedFields || []),
  hasRelatedRowAction = computed(() => ["/api/provider-instances", "/api/budget-rules"].includes(activePath.value)),
  priceParsePreview = computed(() =>
    activePath.value === "/api/provider-price-sources" &&
    relatedAction.value === "测试解析" &&
    relatedRows.value.length
      ? relatedRows.value[0]
      : null,
  ),
  priceParseEvidence = computed<Record<string, any>>(() =>
    parseAuditSnapshot(priceParsePreview.value?.sourceEvidence),
  ),
  priceTableDiagnostics = computed<any[]>(() => {
    const value = priceParseEvidence.value.tableDiagnostics;
    return Array.isArray(value) ? value : [];
  }),
  priceParseWarnings = computed<string[]>(() => {
    const value = priceParsePreview.value?.warnings;
    return Array.isArray(value) ? value.map((item: any) => String(item)) : [];
  }),
  isPublishCheck = computed(() =>
    activePath.value === "/api/platform-models" && relatedAction.value === "发布检查",
  ),
  isEffectivePrice = computed(() =>
    activePath.value === "/api/model-deployment-governance" && relatedAction.value === "有效成本价格",
  ),
  isWideRelatedResult = computed(() => isPublishCheck.value || isEffectivePrice.value),
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
  return stableSortRows(value, sort.value || "id", order.value, props.sortFallbackFields);
});
const serverPaged = ref(false);
const visibleRows = computed(() =>
  serverPaged.value
    ? stableSortRows(rows.value, sort.value || "id", order.value, props.sortFallbackFields)
    : locallyProcessed.value.slice(
        (page.value - 1) * pageSize.value,
        page.value * pageSize.value,
      ),
);
const statusOptions = computed(() => options("status"));
function label(field: string) {
  return activeLabels.value?.[field] || props.labels?.[field] || field;
}
const urlFields = new Set([
  "endpoint",
  "sourceEndpoint",
  "finalEndpoint",
  "sourceRef",
  "baseUrl",
  "modelsEndpoint",
  "billingEndpoint",
]);
function isUrlField(field: string) {
  return urlFields.has(field);
}
function columnClass(field: string) {
  return { "column-url": isUrlField(field), [`column-${field}`]: true };
}
function columnStyle(field: string) {
  const width = props.columnWidths?.[field];
  return width ? { width } : undefined;
}
function source(field: string) {
  return props.optionSources?.[field];
}
function parseAuditSnapshot(value: any): Record<string, any> {
  if (!value) return {};
  if (typeof value === "object" && !Array.isArray(value)) {
    if (
      ["json", "jsonb"].includes(String(value?.type || "").toLowerCase()) &&
      typeof value?.value === "string"
    ) {
      try {
        return JSON.parse(value.value);
      } catch {
        return {};
      }
    }
    return value;
  }
  if (typeof value !== "string") return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}
function auditObjectName(row: any) {
  const after = parseAuditSnapshot(row?.afterValue ?? row?.after_value);
  const before = parseAuditSnapshot(row?.beforeValue ?? row?.before_value);
  const snapshot = { ...before, ...after };
  const directKeys = [
    "name",
    "displayName",
    "instanceName",
    "platformModelName",
    "providerModelName",
    "candidateModelName",
    "secretName",
    "username",
    "title",
    "modelAlias",
    "sourceName",
    "contractName",
  ];
  for (const key of directKeys) {
    const value = snapshot[key] ?? snapshot[key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`)];
    if (value !== null && value !== undefined && String(value).trim()) return String(value).trim();
  }
  const providerModel = snapshot.providerModelName ?? snapshot.provider_model_name;
  const targetModel = snapshot.targetProviderModelName ?? snapshot.target_provider_model_name;
  if (providerModel && targetModel) return `${providerModel} → ${targetModel}`;
  const fromCurrency = snapshot.fromCurrency ?? snapshot.from_currency;
  const toCurrency = snapshot.toCurrency ?? snapshot.to_currency;
  const rateMonth = snapshot.rateMonth ?? snapshot.rate_month;
  if (fromCurrency && toCurrency) return `${rateMonth ? `${rateMonth} · ` : ""}${fromCurrency} → ${toCurrency}`;
  const capability = snapshot.capabilityCode ?? snapshot.capability_code;
  if (capability) {
    return ({ CHAT: "对话能力验证", STREAM: "流式能力验证", EMBEDDING: "向量能力验证" } as Record<string, string>)[String(capability)] || `${capability} 能力验证`;
  }
  const objectType = String(row?.objectType ?? row?.object_type ?? "");
  return (
    {
      ProviderPriceSyncRun: "价格同步任务",
      CapabilityValidation: "能力验证记录",
      ProviderPriceDiff: "价格差异记录",
      ChannelModelDeployment: "模型部署记录",
      ModelDiscoveryCandidate: "模型候选记录",
      FxRateSyncRun: "汇率同步任务",
    }[objectType] || "详情中查看"
  );
}
function auditClientName(row: any) {
  const userAgent = String(row?.userAgent ?? row?.user_agent ?? "").trim();
  if (!userAgent) return String(row?.actorId ?? row?.actor_id) === "SYSTEM" ? "后台任务" : "未知客户端";
  const os = userAgent.includes("Windows")
    ? "Windows"
    : userAgent.includes("Macintosh")
      ? "macOS"
      : userAgent.includes("Linux")
        ? "Linux"
        : "";
  const client = userAgent.includes("Edg/")
    ? "Edge 浏览器"
    : userAgent.includes("Chrome/")
      ? "Chrome 浏览器"
      : userAgent.includes("Firefox/")
        ? "Firefox 浏览器"
        : userAgent.includes("Safari/") && !userAgent.includes("Chrome/")
          ? "Safari 浏览器"
          : userAgent.includes("PostmanRuntime")
            ? "Postman"
            : /python|httpx|requests|openai/i.test(userAgent)
              ? "Python 客户端"
              : /curl/i.test(userAgent)
                ? "cURL"
                : /java/i.test(userAgent)
                  ? "Java 客户端"
                  : "API 客户端";
  return os ? `${client} · ${os}` : client;
}
function cellValue(row: any, field: string) {
  if (activePath.value === "/api/audit") {
    if (field === "objectName") return auditObjectName(row);
    if (field === "clientName") return auditClientName(row);
  }
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
  if (
    typeof value === "object" &&
    !Array.isArray(value) &&
    ["json", "jsonb"].includes(String(value?.type || "").toLowerCase()) &&
    typeof value?.value === "string"
  ) {
    try {
      return JSON.stringify(JSON.parse(value.value));
    } catch {
      return value.value;
    }
  }
  if (Array.isArray(value))
    return value.map((item) => formatDateTime(item) || String(item)).join("、");
  if (typeof value === "boolean") return value ? "是" : "否";
  if (typeof value === "object") return JSON.stringify(value);
  const dateTime = formatDateTime(value);
  if (dateTime) return dateTime;
  try {
    if (typeof value === "string" && value.startsWith("["))
      return JSON.parse(value)
        .map((item: unknown) => formatDateTime(item) || String(item))
        .join("、");
  } catch {}
  return String(value);
}
const statusText: Record<string, string> = {
  ACTIVE: "启用",
  OPEN: "待处理",
  ACKNOWLEDGED: "已确认",
  RESOLVED: "已解决",
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
  const multiple = selectMode(field) === "multiple";
  const values = multiple && typeof value === "string"
    ? (() => {
        try {
          const parsed = JSON.parse(value);
          return Array.isArray(parsed) ? parsed : undefined;
        } catch {
          return undefined;
        }
      })()
    : Array.isArray(value)
      ? value
      : undefined;
  if (values) {
    return values
      .map((item) => options(field).find((option) => option.value === item)?.label || display(item))
      .join("、") || "—";
  }
  const option = options(field).find((item) => item.value === value);
  if (option) return option.label;
  if (isStatus(field) && statusText[String(value).toUpperCase()])
    return statusText[String(value).toUpperCase()];
  return display(value);
}
function parseStatusText(value: any) {
  return (
    {
      PRICE_PARSED: "解析成功",
      PRICE_TABLE_MATCHED_NO_RECORDS: "价格表已匹配但无记录",
      PRICE_TABLE_NOT_MATCHED: "未匹配价格表",
      NO_PRICE_TABLE: "未发现价格表",
      NOT_PARSED_UNCHANGED: "内容未变化，未重新解析",
      NOT_PARSED: "尚未解析",
    }[String(value || "").toUpperCase()] || display(value)
  );
}
function parseStatusClass(value: any) {
  const status = String(value || "").toUpperCase();
  if (status === "PRICE_PARSED") return "success";
  if (["PRICE_TABLE_MATCHED_NO_RECORDS", "PRICE_TABLE_NOT_MATCHED"].includes(status)) return "warning";
  if (status === "NO_PRICE_TABLE") return "danger";
  return "neutral";
}
function formatBytes(value: any) {
  const bytes = Number(value || 0);
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}
function diagnosticStructure(item: Record<string, any>) {
  if (!item?.matched) return "—";
  if (item.orientation === "COLUMN") {
    return `横向：模型行 ${item.modelRow ?? "—"} / 输入行 ${item.inputRow ?? "—"} / 输出行 ${item.outputRow ?? "—"}`;
  }
  return `纵向：模型列 ${item.modelColumn ?? "—"} / 输入列 ${item.inputColumn ?? "—"} / 输出列 ${item.outputColumn ?? "—"}`;
}
function relatedDisplay(row: Record<string, any>, field: string) {
  if (field === "passed") return row[field] === true ? "通过" : "未通过";
  if (field !== "cacheReadUnitPrice" && field !== "cacheWriteUnitPrice")
    return display(row[field]);
  if (row[field] !== null && row[field] !== undefined && row[field] !== "")
    return display(row[field]);
  const mode = String(
    field === "cacheReadUnitPrice" ? row.cacheReadMode : row.cacheWriteMode,
  ).toUpperCase();
  return (
    {
      EXPLICIT_ZERO: "0",
      INHERIT_INPUT: "沿用输入价格",
      NOT_APPLICABLE: "不适用",
      UNKNOWN: "待确认",
    }[mode] || "—"
  );
}
function fieldConditionMatches(condition?: FieldCondition) {
  if (!condition) return true;
  const value = form[condition.field];
  if (condition.in) return condition.in.includes(value);
  if (Object.prototype.hasOwnProperty.call(condition, "equals"))
    return value === condition.equals;
  return Boolean(value);
}
function required(field: string) {
  return (
    requiredFields.value.includes(field) ||
    fieldConditionMatches(props.conditionalRequiredFields?.[field]) &&
      !!props.conditionalRequiredFields?.[field]
  );
}
function fieldType(field: string) {
  return (
    props.fieldTypes?.[field] ||
    (["remark", "message", "reason", "suggestion"].includes(field)
      ? "textarea"
      : "text")
  );
}
function fieldPlaceholder(field: string) {
  if (fieldType(field) === "aliases") return "多个别名用逗号分隔";
  if (fieldType(field) === "host-list") return "多个域名用逗号分隔，例如 api.example.com";
  return undefined;
}
function fieldDisabled(field: string) {
  return Boolean(
    props.readonlyFields?.includes(field) ||
      (editing.value && props.immutableFields?.includes(field)),
  );
}
function jsonFormValue(value: any) {
  value = databaseJsonValue(value);
  if (value === undefined || value === null || value === "") return "{}";
  if (typeof value === "string") {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  return JSON.stringify(value, null, 2);
}
function stringListFormValue(value: any) {
  value = databaseJsonValue(value);
  if (Array.isArray(value)) return value.join(", ");
  if (typeof value !== "string") return "";
  try {
    const values = JSON.parse(value);
    return Array.isArray(values) ? values.join(", ") : value;
  } catch {
    return value;
  }
}
function databaseJsonValue(value: any) {
  if (
    value &&
    typeof value === "object" &&
    ["json", "jsonb"].includes(String(value.type || "").toLowerCase()) &&
    typeof value.value === "string"
  ) return value.value;
  return value;
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
    "routeStatus",
    "capabilityStatus",
    "priceStatus",
    "lifecycleStatus",
    "result",
    "decision",
    "severity",
  ].includes(field);
}
function availableActions(row: any) {
  if (activePath.value === "/api/alerts") {
    const alertStatus = String(cellValue(row, "status") || "");
    return actions.value.filter((action) =>
      alertStatus !== "RESOLVED" && !(alertStatus === "ACKNOWLEDGED" && action === "确认"),
    );
  }
  if (activePath.value === "/api/provider-price-sources") {
    const isActive = ["ACTIVE", "启用"].includes(String(cellValue(row, "status")));
    return actions.value.filter((action) => {
      if (action === "启用") return !isActive;
      if (action === "暂停") return isActive;
      return true;
    });
  }
  if (activePath.value === "/api/provider-billing-sources") {
    const isActive = String(cellValue(row, "status") || "").toUpperCase() === "ACTIVE";
    return actions.value.filter((action) => {
      if (action === "启用") return !isActive;
      if (action === "暂停") return isActive;
      return true;
    });
  }
  if (activePath.value === "/api/provider-price-diffs") {
    const diffStatus = String(cellValue(row, "status") || "");
    return actions.value.filter((action) => {
      if (action === "查看差异") return true;
      if (["批准发布", "驳回"].includes(action)) return diffStatus === "PENDING";
      if (action === "撤销发布") {
        return ["APPROVED", "AUTO_PUBLISHED"].includes(diffStatus)
          && !!cellValue(row, "publishedCatalogId");
      }
      return false;
    });
  }
  if (activePath.value === "/api/platform-models") {
    return actions.value.filter((action) => {
      if (action === "提交审批")
        return row.approvalRequired === true && row.status !== "已发布";
      if (action === "发布") return row.status !== "已发布";
      return true;
    });
  }
  if (activePath.value === "/api/budget-rules") {
    return actions.value.filter((action) => {
      const approvalStatus = String(row.approvalStatus || "");
      if (action === "提交审批") return approvalStatus === "DRAFT";
      if (action === "审批后生效") return approvalStatus === "APPROVED" && row.status !== "ACTIVE";
      if (action === "退役") return row.status === "ACTIVE";
      return true;
    });
  }
  if (activePath.value === "/api/governance/approvals") {
    return actions.value.filter((action) => row.status === "PENDING");
  }
  if (activePath.value === "/api/keys") {
    return actions.value.filter((action) => {
      if (action === "生成密钥")
        return row.status === "PENDING" || row.keyPrefix === "pending";
      if (action === "禁用") return row.status === "ACTIVE";
      return true;
    });
  }
  return actions.value;
}
function statusClass(value: any) {
  const v = String(value || "").toUpperCase();
  if (["CRITICAL", "HIGH", "严重", "高"].includes(v)) return "danger";
  if (["WARNING", "MEDIUM", "LOW", "警告", "中", "低"].includes(v)) return "warn";
  if (["INFO", "信息"].includes(v)) return "info";
  if (
    [
      "ACTIVE",
      "SUCCESS",
      "HEALTHY",
      "MATCHED_OFFICIAL",
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
      ...props.queryParams,
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
function clearDependentFields(field: string, visited = new Set<string>()) {
  if (visited.has(field)) return;
  visited.add(field);
  for (const [dependent, config] of Object.entries(props.optionSources || {})) {
    if (config.dependsOn !== field) continue;
    form[dependent] = config.multiple ? [] : undefined;
    dynamicOptions[dependent] = [];
    clearDependentFields(dependent, visited);
  }
}
function applySourceAutofill(field: string) {
  const config = source(field);
  if (!config?.autofill) return;
  const selected = form[field];
  const row = selected === undefined || selected === null || selected === ""
    ? undefined
    : optionRows[field]?.[String(selected)];
  for (const [targetField, sourceField] of Object.entries(config.autofill)) {
    form[targetField] = row ? cellValue(row, sourceField) : undefined;
  }
}
function applyFieldPreset(field: string) {
  const preset = props.fieldPresets?.[field]?.[String(form[field] ?? "")];
  if (!preset) return;
  for (const [targetField, value] of Object.entries(preset)) {
    form[targetField] = fieldType(targetField) === "json" ? jsonFormValue(value) : value;
  }
}
function onFieldChange(field: string) {
  applySourceAutofill(field);
  applyFieldPreset(field);
  const directDependents = Object.entries(props.optionSources || {})
    .filter(([, config]) => config.dependsOn === field)
    .map(([dependent]) => dependent);
  clearDependentFields(field);
  for (const candidate of props.editableFields || activeFields.value) {
    if (!fieldConditionMatches(props.fieldVisibility?.[candidate])) {
      form[candidate] = source(candidate)?.multiple ? [] : undefined;
      dynamicOptions[candidate] = [];
    }
  }
  directDependents.forEach(loadFieldOptions);
}
async function loadFieldOptions(field: string) {
  const config = source(field);
  if (!config) return;
  const dependency = config.dependsOn ? form[config.dependsOn] : undefined;
  const dependencyValues = Array.isArray(dependency)
    ? dependency.map(String).filter(Boolean)
    : dependency === undefined || dependency === null || dependency === ""
      ? []
      : [String(dependency)];
  if (config.dependsOn && !dependencyValues.length) {
    dynamicOptions[field] = [];
    return;
  }
  const path = resolvedSourcePath(field);
  if (path.includes("//") || path.endsWith("/")) return;
  optionLoading[field] = true;
  delete optionErrors[field];
  try {
    const result = await queryPage<any>(path, { page: 1, size: 500, ...config.query });
    const filterBy = config.filterBy;
    const items = filterBy
      ? result.items.filter((row) =>
          dependencyValues.includes(String(cellValue(row, filterBy))),
        )
      : result.items;
    optionRows[field] = Object.fromEntries(
      items
        .map((row) => [String(cellValue(row, config.value)), row] as const)
        .filter(([value]) => value !== "undefined" && value !== "null"),
    );
    dynamicOptions[field] = items
      .map((row) => ({
        label: String(
          cellValue(row, config.label) ?? cellValue(row, config.value),
        ),
        value: cellValue(row, config.value),
      }))
      .filter((item) => item.value !== undefined && item.value !== null);
  } catch (e) {
    dynamicOptions[field] = [];
    optionRows[field] = {};
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
  formFields.value.forEach((field) => {
    const value = props.defaultFormValues?.[field] ??
      (selectMode(field) === "multiple" ? [] : undefined);
    form[field] = fieldType(field) === "json" ? jsonFormValue(value) : value;
  });
  formVisible.value = true;
  await loadOptions();
}
async function openEdit(row: any) {
  editing.value = row;
  formError.value = "";
  formFields.value.forEach((field) => {
    const value = cellValue(row, field);
    if (fieldType(field) === "json") {
      form[field] = jsonFormValue(value);
    } else if (fieldType(field) === "host-list") {
      form[field] = stringListFormValue(value);
    } else if (fieldType(field) === "aliases") {
      if (Array.isArray(value)) form[field] = value.join(", ");
      else if (typeof value === "string") {
        try {
          const aliases = JSON.parse(value);
          form[field] = Array.isArray(aliases) ? aliases.join(", ") : value;
        } catch {
          form[field] = value;
        }
      } else form[field] = "";
    } else if (selectMode(field) === "multiple" && typeof value === "string") {
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
    for (const field of formFields.value.filter(
      (item) => fieldType(item) === "aliases",
    )) {
      payload[field] = String(payload[field] || "")
        .split(/[,，]/)
        .map((item) => item.trim())
        .filter(Boolean);
    }
    for (const field of formFields.value.filter(
      (item) => fieldType(item) === "host-list",
    )) {
      payload[field] = String(payload[field] || "")
        .split(/[,，]/)
        .map((item) => item.trim().toLowerCase())
        .filter(Boolean);
    }
    for (const field of formFields.value.filter(
      (item) => fieldType(item) === "datetime" && payload[item],
    )) {
      const timestamp = new Date(payload[field]);
      if (!Number.isNaN(timestamp.getTime())) payload[field] = timestamp.toISOString();
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
  const loadingKey = actionLoadingKey(action, row);
  const showProgress = shouldShowActionProgress(action);
  if (showProgress && actionLoading.value[loadingKey]) return;
  if (showProgress) actionLoading.value = { ...actionLoading.value, [loadingKey]: true };
  try {
    const precondition = props.actionPreconditions?.[action];
    if (precondition) {
      const relatedId = row[precondition.idField];
      if (!relatedId) {
        message.error(precondition.message);
        return;
      }
      const target: any = await get(
        precondition.path.replace(":id", encodeURIComponent(String(relatedId))),
      );
      if (target?.[precondition.field] !== precondition.equals) {
        const current = target?.[precondition.field] ?? "未知";
        message.error(`${precondition.message} 当前路由状态：${current}`);
        return;
      }
    }
    if (props.actionForms?.[action]) {
      actionName.value = action;
      actionRow.value = row;
      actionError.value = "";
      Object.keys(actionPayload).forEach((key) => delete actionPayload[key]);
      actionFormVisible.value = true;
      return;
    }
    await executeAction(action, row, {});
  } catch (e) {
    if (props.refreshOnErrorActions?.includes(action)) await load();
    message.error(errorMessage(e));
  } finally {
    if (showProgress) {
      const next = { ...actionLoading.value };
      delete next[loadingKey];
      actionLoading.value = next;
    }
  }
}
function actionLoadingKey(action: string, row: any) {
  return `${row.id}:${action}`;
}
function isActionLoading(action: string, row: any) {
  return Boolean(actionLoading.value[actionLoadingKey(action, row)]);
}
function actionTooltip(action: string) {
  if (activePath.value === "/api/provider-price-sources") {
    return {
      测试获取: "请求价格源并检查访问是否正常",
      测试解析: "解析样本并预览可识别的价格数据",
      同步: "立即拉取并更新该价格源的数据",
      启用: "启用该价格源及其定时同步",
      停用: "暂停该价格源的后续同步",
    }[action] || action;
  }
  if (activePath.value === "/api/provider-billing-sources") {
    return {
      测试账单: "调用供应商账单接口并预览标准化结果",
      立即同步: "同步账单证据并生成或更新供应商对账",
      启用: "启用账单源定时同步",
      暂停: "暂停账单源定时同步",
    }[action] || action;
  }
  return action;
}
function shouldShowActionProgress(action: string) {
  return (activePath.value === "/api/provider-instances" && ["连接测试", "发现模型"].includes(action))
    || (activePath.value === "/api/provider-price-sources" && ["测试获取", "测试解析"].includes(action))
    || (activePath.value === "/api/provider-billing-sources" && ["测试账单", "立即同步"].includes(action));
}
async function executeAction(
  action: string,
  row: any,
  formPayload: Record<string, any>,
) {
  const raw = props.builtinActionMap?.[action];
  if (!raw) return;
  const match = raw.match(/^(GET|POST|POST_SHOW|PATCH|DOWNLOAD)\s+/);
  const method = match?.[1] || "POST";
  const suffix = raw
    .replace(/^(GET|POST|POST_SHOW|PATCH|DOWNLOAD)\s+/, "")
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
  if (method === "GET" || method === "POST_SHOW") {
    const result = method === "GET" ? await get(path) : await postAction(path, payload);
    relatedRows.value = Array.isArray(result)
      ? result
      : Array.isArray(result?.checks)
        ? result.checks
        : [result];
    relatedAction.value = action;
    relatedTitle.value = activePath.value === "/api/budget-rules"
      ? `${action} · 预算规则`
      : `${action} · ${row.providerName || row.platformModelName || row.providerModelName || row.name || row.id}`;
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
    const feedback = activePath.value === "/api/budget-rules" && action === "提交审批"
      ? "已提交审批，请前往“高级治理 → 治理审批”处理"
      : result?.message || `${action}成功`;
    if (result?.feedbackType === "warning" || result?.verificationPassed === false) {
      message.warning(feedback);
    } else {
      message.success(feedback);
    }
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
    const payload = { ...actionPayload };
    for (const [field, type] of Object.entries(config.fieldTypes || {})) {
      if (type !== "datetime" || !payload[field]) continue;
      const time = new Date(payload[field]);
      if (!Number.isNaN(time.getTime())) payload[field] = time.toISOString();
    }
    await executeAction(actionName.value, actionRow.value, payload);
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
    const isActivation =
      statePath.value === "status" &&
      stateValue.value === props.activationStatus &&
      !!props.activationPath;
    if (isActivation) {
      await postAction(`${activePath.value}/${stateRow.value.id}/${props.activationPath}`);
    } else {
      const path = `${activePath.value}/${stateRow.value.id}/${statePath.value}`;
      const payload = statePath.value === "status" ? { status: stateValue.value } : {};
      props.stateMethod === "post"
        ? await postAction(path, payload)
        : await patchAction(path, payload);
    }
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

<style scoped>
.form-item-wide {
  grid-column: 1 / -1;
}

.form-compact :deep(.ant-form-item) {
  margin-bottom: 10px;
}

.form-compact :deep(.ant-form-item-label) {
  padding-bottom: 3px;
}

.page-desc-nowrap {
  width: 100%;
  max-width: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.column-url {
  width: 280px;
  min-width: 280px;
  max-width: 280px;
}

.cell-url {
  display: -webkit-box;
  max-width: 100%;
  overflow: hidden;
  overflow-wrap: anywhere;
  word-break: break-word;
  line-height: 1.45;
  white-space: normal;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.actions-column {
  width: 220px;
  min-width: 220px;
}

.row-action-buttons {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.row-action-buttons .btn.small {
  flex: 0 0 auto;
  white-space: nowrap;
}

.action-pending {
  cursor: wait;
}

.action-spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: action-spin 0.7s linear infinite;
}

@keyframes action-spin {
  to { transform: rotate(360deg); }
}

.provider-actions-column {
  width: 256px;
  min-width: 256px;
}

.provider-row-action-buttons {
  display: grid;
  grid-template-columns: repeat(3, max-content);
}

.provider-channel-table-wrap {
  overflow-x: hidden !important;
}

.publish-check-table-wrap {
  overflow-x: hidden;
}

.publish-check-table {
  width: 100%;
  min-width: 0;
  table-layout: fixed;
}

.publish-check-table th:nth-child(1) { width: 13%; }
.publish-check-table th:nth-child(2) { width: 8%; }
.publish-check-table th:nth-child(3) { width: 19%; }
.publish-check-table th:nth-child(4) { width: 28%; }
.publish-check-table th:nth-child(5) { width: 32%; }

.publish-check-table th,
.publish-check-table td {
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
  vertical-align: top;
}

.effective-price-table-wrap {
  overflow-x: hidden;
}

.effective-price-table {
  width: 100%;
  min-width: 0;
  table-layout: fixed;
}

.effective-price-table th,
.effective-price-table td {
  padding: 8px 6px;
  overflow-wrap: anywhere;
  word-break: break-word;
  white-space: normal;
}

.effective-price-table th {
  font-size: 12px;
}

.effective-price-table td {
  font-size: 13px;
}

.provider-channel-table {
  width: 100%;
  min-width: 0;
  table-layout: fixed;
}

.provider-channel-table th,
.provider-channel-table td {
  padding: 8px 7px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.provider-channel-table th {
  font-size: 12px;
}

.provider-channel-table td {
  font-size: 13px;
}

.provider-channel-table .sort-button {
  max-width: 100%;
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.provider-channel-table td > span:not(.status) {
  display: -webkit-box;
  overflow: hidden;
  line-height: 1.35;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.provider-channel-table .actions-column,
.provider-channel-table .provider-actions-column {
  width: 176px;
  min-width: 0;
}

.provider-channel-table .provider-row-action-buttons {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px;
}

.provider-channel-table .row-action-buttons .btn.small {
  width: 100%;
  height: 28px;
  min-height: 28px;
  padding: 0;
  overflow: hidden;
  border-radius: 5px;
  font-size: 12px;
  text-overflow: ellipsis;
}

.model-discovery-candidate-page .table-wrap {
  overflow-x: hidden !important;
}

.model-discovery-candidate-page .data-table {
  min-width: 0;
  table-layout: fixed;
}

.model-discovery-candidate-page .actions-column {
  width: 84px;
  min-width: 84px;
}

.model-discovery-candidate-page .status {
  display: inline-block;
  max-width: 100%;
  overflow-wrap: anywhere;
  white-space: normal;
  word-break: break-word;
  line-height: 1.3;
  text-align: center;
}

.price-parse-report {
  display: grid;
  min-width: 0;
  gap: 14px;
}

.price-parse-report-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e8edf5;
}

.price-parse-report-head > div {
  display: grid;
  gap: 3px;
}

.price-parse-report-head strong {
  color: #233753;
  font-size: 16px;
}

.price-parse-report-head span:not(.parse-status) {
  color: #7c899c;
  font-size: 12px;
}

.parse-status {
  display: inline-flex;
  min-height: 24px;
  align-items: center;
  padding: 2px 9px;
  border: 1px solid #d8e0eb;
  border-radius: 999px;
  background: #f5f7fa;
  color: #66758a;
  font-size: 12px;
  line-height: 1.2;
  white-space: nowrap;
}

.parse-status.success {
  border-color: #b8e4ca;
  background: #eefaf3;
  color: #18794e;
}

.parse-status.warning {
  border-color: #f2d49a;
  background: #fff8e8;
  color: #9a6700;
}

.parse-status.danger {
  border-color: #f0b9b9;
  background: #fff1f1;
  color: #b42318;
}

.parse-status.neutral {
  border-color: #d8e0eb;
  background: #f5f7fa;
  color: #66758a;
}

.price-parse-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.price-parse-metrics article {
  display: grid;
  min-width: 0;
  gap: 4px;
  padding: 12px 14px;
  box-sizing: border-box;
  border: 1px solid #e1e8f2;
  border-radius: 9px;
  background: linear-gradient(180deg, #fff 0%, #f8fbff 100%);
}

.price-parse-metrics span,
.price-parse-meta span {
  color: #7b8799;
  font-size: 11px;
}

.price-parse-metrics strong {
  color: #203653;
  font-size: 22px;
  line-height: 1.1;
}

.price-parse-meta {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px 12px;
  padding: 11px 13px;
  border: 1px solid #e7ecf3;
  border-radius: 8px;
  background: #fafbfd;
  box-sizing: border-box;
}

.price-parse-meta > div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.price-parse-meta .wide {
  grid-column: 1 / -1;
}

.price-parse-meta strong,
.price-parse-meta code {
  overflow: hidden;
  color: #34465f;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.price-parse-warning-list {
  padding: 10px 12px;
  border: 1px solid #f1d6a5;
  border-radius: 8px;
  background: #fffaf0;
  color: #765a20;
  font-size: 12px;
}

.price-parse-warning-list ul {
  margin: 6px 0 0;
  padding-left: 18px;
}

.price-parse-details {
  border: 1px solid #e1e7ef;
  border-radius: 8px;
  background: #fff;
}

.price-parse-details summary {
  padding: 10px 12px;
  color: #34465f;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.compact-diagnostic-table th,
.compact-diagnostic-table td {
  padding: 7px 9px;
  font-size: 12px;
}

.compact-diagnostic-table {
  width: 100%;
  min-width: 0;
  table-layout: fixed;
}

.compact-diagnostic-table th,
.compact-diagnostic-table td {
  overflow-wrap: anywhere;
  word-break: break-word;
  white-space: normal;
}

.diagnostic-more {
  margin: 8px 12px 10px;
  color: #7b8799;
  font-size: 11px;
}

.price-parse-json {
  max-height: 280px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  border-top: 1px solid #edf1f6;
  background: #f7f9fc;
  color: #34465f;
  font-size: 11px;
  line-height: 1.55;
}

@media (max-width: 1280px) {
  .provider-channel-table .column-rateLimitRpm,
  .provider-channel-table .column-rateLimitTpm,
  .provider-channel-table .column-keyStatus {
    display: none;
  }
}

@media (max-width: 1100px) {
  .provider-channel-table .column-environment {
    display: none;
  }

  .provider-channel-table .column-apiBase {
    width: 140px !important;
  }

  .provider-channel-table .actions-column,
  .provider-channel-table .provider-actions-column {
    width: 162px;
  }
}

@media (max-width: 760px) {
  .provider-channel-table .column-providerTemplateId {
    display: none;
  }

  .price-parse-metrics,
  .price-parse-meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .price-parse-report-head {
    align-items: stretch;
    flex-direction: column;
  }

  .price-parse-report-head .parse-status {
    align-self: flex-start;
  }
}

@media (min-width: 981px) {
  .data-page-internal-scroll {
    display: flex;
    height: calc(100vh - 136px);
    height: calc(100dvh - 136px);
    min-height: 0;
    flex-direction: column;
    overflow: hidden;
  }

  .data-page-internal-scroll > .page-header,
  .data-page-internal-scroll > .asset-tabs {
    flex: 0 0 auto;
  }

  .data-page-internal-scroll > .data-surface {
    display: flex;
    min-height: 0;
    flex: 1 1 auto;
    flex-direction: column;
    overflow: hidden;
  }

  .data-page-internal-scroll > .data-surface > .toolbar,
  .data-page-internal-scroll > .data-surface > .pagination {
    flex: 0 0 auto;
  }

  .data-page-internal-scroll > .data-surface > .table-wrap {
    min-height: 0;
    flex: 1 1 auto;
    overflow: auto;
  }

  .data-page-internal-scroll > .data-surface > .table-wrap .data-table th {
    position: sticky;
    top: 0;
    z-index: 2;
  }

  .data-page-internal-scroll > .data-surface > .state-panel {
    min-height: 0;
    flex: 1 1 auto;
  }
}
</style>
