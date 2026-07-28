<template>
  <DataPage :key="tab" v-bind="pageProps">
    <template #header-after-description>
      <nav class="alerts-tabs" aria-label="告警视图">
        <button
          v-for="item in tabs"
          :key="item.key"
          :class="['alerts-tab', { active: tab === item.key }]"
          @click="tab = item.key"
        >
          {{ item.label }}
        </button>
      </nav>
    </template>
  </DataPage>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { resources } from "../config/resources";
import DataPage from "./DataPage.vue";

const tab = ref<"current" | "history">("current");
const tabs = [
  { key: "current", label: "当前告警" },
  { key: "history", label: "历史告警" },
] as const;
const pageProps = computed(() => ({
  ...resources.alerts,
  title: "告警",
  desc: tab.value === "current"
    ? "展示待处理和已确认的告警；处理完成后会自动移入历史告警。"
    : "展示已解决告警，用于追溯历史处理记录。",
  apiPath: "/api/alerts",
  queryParams: { scope: tab.value },
}));
</script>

<style scoped>
.alerts-tabs { display: flex; width: fit-content; gap: 20px; margin-top: 14px; border-bottom: 1px solid #dce4ef; }
.alerts-tab { position: relative; padding: 0 2px 9px; border: 0; background: transparent; color: #75839a; cursor: pointer; font-size: 13px; line-height: 1; transition: color .18s ease; }
.alerts-tab:hover { color: #2d5fbe; }
.alerts-tab.active { color: #1f5fd1; font-weight: 700; }
.alerts-tab.active::after { position: absolute; right: 0; bottom: -1px; left: 0; height: 2px; background: #2f6bff; border-radius: 2px 2px 0 0; content: ""; }
</style>
