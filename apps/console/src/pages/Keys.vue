<template>
  <DataPage
    title="API Key"
    desc="Key 必须在本页面单独创建。默认创建应用级 Key；租户级和项目级 Key 属于高级用途，需显式选择。允许调用的服务模型只能从租户授权池中选择，明文只返回一次。"
    :desc-no-wrap="true"
    :internal-scroll="true"
    api-path="/api/keys"
    default-sort="createdAt"
    default-order="desc"
    :fields="fields"
    :labels="labels"
    :required-fields="['tenantId', 'scopeLevel', 'name', 'modelScope']"
    :field-visibility="fieldVisibility"
    :conditional-required-fields="conditionalRequiredFields"
    :editable-fields="editableFields"
    :detail-fields="detailFields"
    :number-fields="['budgetAmount', 'rpmLimit', 'tpmLimit', 'qpsLimit']"
    :option-sources="sources"
    :field-options="options"
    :default-form-values="defaultFormValues"
    :builtin-actions="['生成密钥', '禁用']"
    :builtin-action-map="actions"
    action-column-width="136px"
    :allow-edit="false"
  />
</template>
<script setup lang="ts">
import DataPage from "./DataPage.vue";

const fields = [
  "tenantName",
  "scopeLevel",
  "projectName",
  "appName",
  "name",
  "keyPrefix",
  "status",
  "modelScope",
  "budgetAmount",
  "rpmLimit",
  "tpmLimit",
  "qpsLimit",
  "ipWhitelist",
  "expiresAt",
];
const editableFields = [
  "tenantId",
  "scopeLevel",
  "projectId",
  "appId",
  "name",
  "modelScope",
  "budgetAmount",
  "rpmLimit",
  "tpmLimit",
  "qpsLimit",
  "ipWhitelist",
  "expiresAt",
];
const detailFields = [
  "tenantName",
  "scopeLevel",
  "projectName",
  "appName",
  "name",
  "keyPrefix",
  "status",
  "modelScope",
  "budgetAmount",
  "rpmLimit",
  "tpmLimit",
  "qpsLimit",
  "expiresAt",
];
const labels: Record<string, string> = {
  tenantId: "租户",
  tenantName: "租户",
  scopeLevel: "Key 归属层级",
  projectId: "项目",
  projectName: "项目",
  appId: "应用",
  appName: "应用",
  name: "Key 名称",
  keyPrefix: "Key 前缀",
  status: "状态",
  modelScope: "允许调用的服务模型",
  budgetAmount: "预算",
  rpmLimit: "每分钟请求",
  tpmLimit: "每分钟 Token",
  qpsLimit: "每秒请求",
  ipWhitelist: "IP 白名单",
  expiresAt: "有效期",
};
const sources = {
  tenantId: { path: "/api/tenants", label: "name", value: "id" },
  projectId: {
    path: "/api/projects?tenantId={tenantId}",
    label: "name",
    value: "id",
    dependsOn: "tenantId",
  },
  appId: {
    path: "/api/apps?projectId={projectId}",
    label: "name",
    value: "id",
    dependsOn: "projectId",
  },
  modelScope: {
    path: "/api/tenants/{tenantId}/service-models",
    label: "displayName",
    value: "platformModelName",
    multiple: true,
    dependsOn: "tenantId",
  },
};
const fieldVisibility = {
  projectId: { field: "scopeLevel", in: ["PROJECT", "APPLICATION"] },
  appId: { field: "scopeLevel", equals: "APPLICATION" },
};
const conditionalRequiredFields = {
  projectId: { field: "scopeLevel", in: ["PROJECT", "APPLICATION"] },
  appId: { field: "scopeLevel", equals: "APPLICATION" },
};
const options = {
  scopeLevel: [
    { label: "应用级", value: "APPLICATION" },
    { label: "项目级", value: "PROJECT" },
    { label: "租户级", value: "TENANT" },
  ],
  status: [
    { label: "待生成", value: "PENDING" },
    { label: "启用", value: "ACTIVE" },
    { label: "停用", value: "DISABLED" },
  ],
};
const defaultFormValues = {
  scopeLevel: "APPLICATION",
};
const actions = {
  生成密钥: ":id/generate",
  禁用: ":id/disable",
};
</script>
