<template>
  <div class="page console-page">
    <header class="page-header"><div><div class="eyebrow">租户工作台</div><h1 class="page-title">我的业务空间</h1><p class="page-desc">数据范围由当前账号的实时租户成员关系决定。</p></div><button class="btn" :disabled="loading" @click="load">刷新</button></header>
    <div v-if="error" class="state-panel error-state" role="alert"><strong>工作台加载失败</strong><p>{{ error }}</p><button class="btn" @click="load">重试</button></div>
    <div v-else-if="loading" class="state-panel" aria-busy="true"><span class="loading-mark"></span><strong>正在核验租户权限并读取数据</strong></div>
    <template v-else>
      <section class="workspace-strip"><div><span>当前账号</span><strong>{{ session.username || context.userId }}</strong></div><div><span>有效租户</span><strong>{{ context.tenantIds?.length || 0 }}</strong></div></section>
      <nav class="asset-tabs" aria-label="租户数据"><button v-for="tab in tabs" :key="tab.key" :class="['asset-tab',{active:active===tab.key}]" @click="active=tab.key">{{ tab.label }} <span>{{ datasets[tab.key]?.length ?? 0 }}</span></button></nav>
      <section class="card data-surface">
        <div class="table-wrap"><table class="data-table"><thead><tr><th v-for="field in current.fields" :key="field">{{ current.labels[field] }}</th></tr></thead><tbody><tr v-for="row in datasets[active]" :key="row.id"><td v-for="field in current.fields" :key="field">{{ display(row[field]) }}</td></tr></tbody></table></div>
        <div v-if="!datasets[active]?.length" class="state-panel empty-state"><strong>当前范围没有数据</strong><p>接口未返回该类型的业务记录。</p></div>
      </section>
    </template>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { errorMessage, get, identity, list } from '../api/client'
import { assertTenantScope } from '../security/tenantScope'
const session=identity(),loading=ref(false),error=ref(''),context=ref<any>({}),active=ref('tenants'),datasets=reactive<Record<string,any[]>>({})
const tabs=[
  {key:'tenants',label:'租户',fields:['name','type','status','ownerName','monthlyBudget'],labels:{name:'租户名称',type:'类型',status:'状态',ownerName:'负责人',monthlyBudget:'月预算'}},
  {key:'projects',label:'项目',fields:['name','tenantId','ownerName','monthlyBudget','status'],labels:{name:'项目名称',tenantId:'租户',ownerName:'负责人',monthlyBudget:'月预算',status:'状态'}},
  {key:'apps',label:'应用',fields:['name','projectId','environment','ownerName','status'],labels:{name:'应用名称',projectId:'项目',environment:'环境',ownerName:'负责人',status:'状态'}},
  {key:'models',label:'可用模型',fields:['platformModelName','displayName','status'],labels:{platformModelName:'模型名',displayName:'展示名称',status:'状态'}},
  {key:'keys',label:'API Key',fields:['name','keyPrefix','projectId','appId','status'],labels:{name:'Key 名称',keyPrefix:'前缀',projectId:'项目',appId:'应用',status:'状态'}},
  {key:'usage',label:'用量',fields:['requestId','modelAlias','totalTokens','costAmount','status','createdAt'],labels:{requestId:'请求 ID',modelAlias:'模型',totalTokens:'Token',costAmount:'成本',status:'状态',createdAt:'时间'}},
  {key:'billing',label:'账单',fields:['periodStart','periodEnd','totalTokens','totalCost','status'],labels:{periodStart:'开始',periodEnd:'结束',totalTokens:'Token',totalCost:'成本',status:'状态'}},
] as const
const current=computed<any>(()=>tabs.find(tab=>tab.key===active.value)!)
function display(value:any){if(value===null||value===undefined||value==='')return'—';if(typeof value==='object')return JSON.stringify(value);return String(value)}
async function load(){loading.value=true;error.value='';try{context.value=await get('/api/tenant/context');assertTenantScope(context.value,session);const values=await Promise.all(tabs.map(tab=>list(`/api/tenant/${tab.key}`)));tabs.forEach((tab,index)=>datasets[tab.key]=values[index])}catch(e){Object.keys(datasets).forEach(key=>datasets[key]=[]);error.value=errorMessage(e)}finally{loading.value=false}}
onMounted(load)
</script>
