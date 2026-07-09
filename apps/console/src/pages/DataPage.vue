
<template>
  <div class="page">
    <div class="breadcrumb">TokenSea / {{ title }}</div>
    <div class="page-header">
      <div><div class="eyebrow">{{ eyebrow }}</div><h1 class="page-title">{{ title }}</h1><p class="page-desc">{{ desc }}</p></div>
      <div class="header-actions"><button class="btn" @click="load">刷新</button><button v-if="showCreateButton" class="btn primary" @click="openCreate">{{ createText }}</button></div>
    </div>
    <div v-if="tabs.length" class="asset-tabs">
      <button v-for="tab in tabs" :key="tab.key" :class="['asset-tab', { active: activeTab === tab.key }]" @click="activeTab = tab.key">{{ tab.label }}</button>
    </div>
    <div :class="['split', { 'no-detail': hideDetailPanel }]">
      <section class="card">
        <div class="toolbar"><div class="filters"><input class="input" v-model="keyword" placeholder="搜索当前列表" /><select v-if="isDataTab" class="select" v-model="status"><option value="">全部状态</option><option v-for="opt in statusOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option></select></div><span class="muted">{{ filteredRows.length }} 条记录</span></div>
        <div class="table-wrap">
          <table class="data-table"><thead><tr><th v-for="f in activeFields" :key="f">{{ label(f) }}</th><th v-if="hasActions">操作</th></tr></thead>
            <tbody v-if="filteredRows.length"><tr v-for="r in filteredRows" :key="r.id || r.name || JSON.stringify(r)" @click="selected = r"><td v-for="f in activeFields" :key="f"><span v-if="f==='status' || f==='healthStatus'" :class="['status', statusClass(r[f])]">{{ displayOption(f, r[f]) }}</span><span v-else>{{ displayOption(f, r[f]) }}</span></td><td v-if="hasActions"><button v-for="action in builtinActions" :key="action" class="btn small" @click.stop="builtinAction(action, r)">{{ action }}</button><button v-if="canEditActive" class="btn small" @click.stop="edit(r)">编辑</button><button v-if="canEditActive" class="btn small danger" @click.stop="del(r)">删除</button></td></tr></tbody>
          </table>
          <div v-if="!filteredRows.length" class="table-empty"><strong>未查询到数据</strong><p>{{ emptyText }}</p><button v-if="showCreateButton" class="btn primary empty-action" @click="openCreate">{{ createText }}</button></div>
          <div class="table-footer-note">{{ footerNote }}</div>
        </div>
      </section>
      <aside v-if="!hideDetailPanel" class="detail-panel"><div class="detail-title">详情面板</div><template v-if="selected"><div v-for="f in activeFields" :key="f" class="detail-row"><span class="muted">{{ label(f) }}</span><span>{{ displayOption(f, selected[f]) }}</span></div></template><div v-else class="empty-state"><strong>未选择记录</strong><p>点击表格行查看字段、策略与审计上下文。</p></div></aside>
    </div>
    <a-modal v-model:open="visible" :title="editing?.id ? '编辑' : '新建'" width="680px" @ok="save">
      <a-form layout="vertical" class="compact-modal-form">
        <a-form-item v-for="f in formFields" :key="f" :class="{ 'span-2': isWide(f) }">
          <template #label>
            <span class="field-label">
              <span v-if="isRequired(f)" class="required-star">*</span>
              <span>{{ label(f) }}</span>
            </span>
          </template>
          <a-select
            v-if="options(f).length"
            v-model:value="form[f]"
            :placeholder="placeholder(f)"
            :options="options(f)"
            allow-clear
          />
          <a-input-number
            v-else-if="isNumber(f)"
            v-model:value="form[f]"
            :placeholder="placeholder(f)"
            class="field-number"
            :min="0"
          />
          <a-textarea
            v-else-if="isLong(f)"
            v-model:value="form[f]"
            :rows="3"
            :placeholder="placeholder(f)"
          />
          <a-input
            v-else
            v-model:value="form[f]"
            :placeholder="placeholder(f)"
          />
          <div v-if="description(f)" class="field-desc">{{ description(f) }}</div>
          <div v-if="example(f) && !options(f).length" class="field-example">{{ example(f) }}</div>
        </a-form-item>
      </a-form>
      <div class="form-help">保存后会写入控制面数据库。供应商原始密钥请在密钥托管入口录入并加密保存。</div>
    </a-modal>
    <a-modal v-model:open="relatedVisible" :title="relatedTitle" width="760px" :footer="null">
      <div class="table-wrap">
        <table class="data-table related-table">
          <thead><tr><th v-for="f in relatedFields" :key="f">{{ relatedLabel(f) }}</th></tr></thead>
          <tbody v-if="relatedRows.length"><tr v-for="r in relatedRows" :key="r.id || JSON.stringify(r)"><td v-for="f in relatedFields" :key="f">{{ display(r[f]) }}</td></tr></tbody>
        </table>
        <div v-if="!relatedRows.length" class="table-empty"><strong>暂无数据</strong><p>当前对象还没有关联记录。</p></div>
      </div>
    </a-modal>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { list, create, update, remove, postAction, patchAction, get } from '../api/client'
import { message, Modal } from 'ant-design-vue'
type FieldOption = { label: string, value: string }
type PageTab = { key: string, label: string, kind: 'builtin' | 'data' }
const props = defineProps<{title:string, eyebrow?:string, desc?:string, apiPath:string, fields:string[], labels?:Record<string,string>, examples?:Record<string,string>, descriptions?:Record<string,string>, requiredFields?:string[], fieldOptions?:Record<string,FieldOption[]>, wideFields?:string[], numberFields?:string[], readonly?: boolean, hideDetailPanel?: boolean, tabs?:PageTab[], defaultTab?:string, builtinApiPath?:string, builtinFields?:string[], builtinRows?:Record<string,any>[], builtinLabels?:Record<string,string>, builtinActions?:string[], builtinActionMap?:Record<string,string>, relatedFields?:string[], relatedLabels?:Record<string,string>, createLabel?:string, builtinCreateLabel?:string, builtinEmptyText?:string, dataEmptyText?:string, builtinFooterNote?:string, dataFooterNote?:string}>()
const rows = ref<any[]>([]); const visible=ref(false); const relatedVisible=ref(false); const relatedTitle=ref(''); const relatedRows=ref<any[]>([]); const editing=ref<any|null>(null); const selected=ref<any|null>(null); const form=reactive<any>({}); const keyword=ref(''); const status=ref(''); const isReadonly = computed(() => !!props.readonly)
const tabs = computed(() => props.tabs || [])
const activeTab = ref(props.defaultTab || props.tabs?.[0]?.key || 'data')
const activeTabMeta = computed(() => tabs.value.find(t => t.key === activeTab.value))
const isDataTab = computed(() => !activeTabMeta.value || activeTabMeta.value.kind === 'data')
const builtinRows = ref<any[]>([])
const activeApiPath = computed(() => isDataTab.value ? props.apiPath : props.builtinApiPath)
const activeRows = computed(() => isDataTab.value ? rows.value : (props.builtinApiPath ? builtinRows.value : (props.builtinRows || [])))
const activeFields = computed(() => isDataTab.value ? props.fields : (props.builtinFields || props.fields))
const formFields = computed(() => activeFields.value.filter(f => !['id','createdAt','updatedAt'].includes(f)))
const canEditActive = computed(() => !isReadonly.value && !!activeApiPath.value)
const hasActions = computed(() => canEditActive.value || !!props.builtinActions?.length)
const showCreateButton = computed(() => !isReadonly.value && (isDataTab.value || !!props.builtinCreateLabel))
const createText = computed(() => isDataTab.value ? (props.createLabel || '新建') : (props.builtinCreateLabel || '新增自定义模板'))
const emptyText = computed(() => isDataTab.value ? (props.dataEmptyText || '当前列表没有真实记录。请通过“新建”录入实际配置。') : (props.builtinEmptyText || '当前没有内置模板数据。'))
const footerNote = computed(() => isDataTab.value ? (props.dataFooterNote || '列表数据来自控制面接口。') : (props.builtinFooterNote || '内置模板为 TokenSea 初始化建议数据，真实生产应由后端模板接口提供。'))
const builtinActions = computed(() => props.builtinActions || [])
const hideDetailPanel = computed(() => !!props.hideDetailPanel)
const relatedFields = computed(() => props.relatedFields || ['providerModelName','defaultDisplayName','capabilityTags','supportedEndpoints','status'])
const filteredRows = computed(() => activeRows.value.filter(r => (!isDataTab.value || !status.value || matchesStatus(r.status, status.value)) && (!keyword.value || JSON.stringify(r).toLowerCase().includes(keyword.value.toLowerCase()))))
const statusOptions = computed(() => props.fieldOptions?.status || [{ label: '启用', value: 'ACTIVE' }, { label: '停用', value: 'DISABLED' }, { label: '待配置', value: 'PENDING' }])
function label(f:string){ return (isDataTab.value ? props.labels?.[f] : props.builtinLabels?.[f]) || props.labels?.[f] || f }
function relatedLabel(f:string){ return props.relatedLabels?.[f] || props.labels?.[f] || f }
function isRequired(f:string){ return (isDataTab.value && props.requiredFields?.includes(f)) || (!isDataTab.value && ['providerName','providerType','protocol','platformModelName','displayName'].includes(f)) || false }
function example(f:string){ return props.examples?.[f] || '' }
function description(f:string){ return props.descriptions?.[f] || '' }
function placeholder(f:string){ if(options(f).length) return `请选择${label(f)}`; return example(f) || `请输入${label(f)}` }
function display(v:any){ if(v===null || v===undefined || v==='') return '-'; if(typeof v === 'object') return JSON.stringify(v); return String(v) }
function options(f:string){ return isDataTab.value ? (props.fieldOptions?.[f] || []) : [] }
function displayOption(f:string, v:any){ const opt = options(f).find(item => item.value === v); if(opt) return opt.label; if(f === 'status') return legacyStatusLabel(v); return display(v) }
function legacyStatusLabel(v:any){ const s=String(v||'').toUpperCase(); if(s === 'ACTIVE') return '启用'; if(s === 'DISABLED') return '停用'; if(s === 'PENDING') return '待配置'; return display(v) }
function matchesStatus(value:any, selected:string){ if(value === selected) return true; return legacyStatusLabel(value) === selected }
function statusClass(v:any){ const s=String(v||'').toUpperCase(); if(['ACTIVE','RUNNING','SUCCESS','APPROVED','启用','正常','健康','可启用','可配置','可发布','已发布'].includes(s)) return 'ok'; if(['PENDING','DRAFT','GRAY','待配置','待审核','暂停','观察','维护中','草稿','测试','灰度'].includes(s)) return 'warn'; if(['DISABLED','FAILED','REJECTED','停用','禁用','异常','已废弃','下架'].includes(s)) return 'danger'; return 'neutral' }
function isLong(f:string){ return ['config','remark','modelScope','capabilityTags','supportedEndpoints','ipWhitelist'].includes(f) }
function isWide(f:string){ return props.wideFields?.includes(f) || isLong(f) }
function isNumber(f:string){ return props.numberFields?.includes(f) || false }
async function load(){
  if(isDataTab.value) rows.value = await list(props.apiPath)
  else if(props.builtinApiPath) builtinRows.value = await list(props.builtinApiPath)
  selected.value = filteredRows.value[0] || null
}
function openCreate(){ if(!activeApiPath.value){ message.info(`${createText.value} 的后端接口尚未接入。`); return } editing.value=null; formFields.value.forEach(f=>form[f]=''); visible.value=true }
function edit(r:any){ editing.value=r; formFields.value.forEach(f=>form[f]=r[f]); visible.value=true }
async function builtinAction(action:string, r:any){
  const suffix = props.builtinActionMap?.[action]
  if(suffix === 'edit'){ edit(r); return }
  if(!props.builtinApiPath || !suffix){ message.info(`${action}：${r.name || r.providerName || r.modelName || r.platformModelName || '模板'} 已预留。`); return }
  const method = suffix.startsWith('PATCH ') ? 'PATCH' : suffix.startsWith('GET ') ? 'GET' : 'POST'
  const path = suffix.replace(/^(PATCH|GET)\s+/, '').replace(':id', r.id)
  if(method === 'GET') {
    relatedRows.value = await get(`${props.builtinApiPath}/${path}`)
    relatedTitle.value = `${r.providerName || r.platformModelName || '对象'} - ${action}`
    relatedVisible.value = true
  } else if(method === 'PATCH') await patchAction(`${props.builtinApiPath}/${path}`)
  else await postAction(`${props.builtinApiPath}/${path}`)
  if(method !== 'GET') message.success(`${action}完成`)
  await load()
}
async function save(){
  const missing = (props.requiredFields || []).filter(f => !String(form[f] ?? '').trim())
  if (missing.length) {
    message.warning(`请填写必填字段：${missing.map(label).join('、')}`)
    return
  }
  const path = activeApiPath.value || props.apiPath
  if(editing.value?.id) await update(path, editing.value.id, {...form}); else await create(path, {...form}); visible.value=false; message.success('已保存'); load()
}
async function del(r:any){ Modal.confirm({ title:'确认删除', content:'删除后将写入审计记录，请确认该对象不再被业务使用。', okText:'删除', okType:'danger', cancelText:'取消', onOk: async()=>{ await remove(activeApiPath.value || props.apiPath, r.id); message.success('已删除'); load() } }) }
onMounted(load); watch(()=>props.apiPath, load); watch(activeTab, load)
</script>
