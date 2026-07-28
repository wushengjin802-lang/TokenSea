<template>
  <section class="page fx-page">
    <header class="page-header fx-header">
      <div>
        <h1 class="page-title">汇率管理</h1>
        <p class="page-desc">价格与用量明细保留原币种；预算、成本单和统计汇总统一折算为 CNY。</p>
      </div>
      <div class="header-actions">
        <button class="btn" :disabled="loading || syncing" @click="loadAll"><ReloadOutlined />刷新</button>
        <button class="btn" @click="manualVisible=true"><EditOutlined />人工维护</button>
        <button class="btn primary" :disabled="syncing" @click="syncNow"><CloudSyncOutlined />{{ syncing?'同步中…':'立即同步' }}</button>
      </div>
    </header>

    <div v-if="error" class="inline-alert danger">{{ error }}</div>
    <div v-if="message" class="inline-alert ok">{{ message }}</div>

    <section class="fx-summary-grid">
      <article class="fx-summary-card primary-card">
        <span class="summary-icon"><DollarCircleOutlined /></span>
        <div><small>汇总基准币种</small><strong>{{ fxStatus.baseCurrency || 'CNY' }}</strong><em>所有汇总与预算统一使用人民币</em></div>
      </article>
      <article class="fx-summary-card">
        <span class="summary-icon blue"><CalendarOutlined /></span>
        <div><small>当前汇率月份</small><strong>{{ fxStatus.currentMonth || '—' }}</strong><em>{{ fxStatus.schedule || '每月自动更新' }}</em></div>
      </article>
      <article class="fx-summary-card">
        <span class="summary-icon green"><SafetyCertificateOutlined /></span>
        <div><small>当前有效汇率</small><strong>{{ fxStatus.activeRates?.length || 0 }} 个</strong><em>{{ missingText }}</em></div>
      </article>
      <article class="fx-summary-card auto-card">
        <div><small>每月自动更新</small><strong>{{ fxStatus.autoUpdateEnabled?'已启用':'已停用' }}</strong><em>人工汇率优先，自动同步不会覆盖</em></div>
        <a-switch :checked="Boolean(fxStatus.autoUpdateEnabled)" :loading="autoSaving" @change="setAutoUpdate" />
      </article>
    </section>

    <section class="card fx-policy-card">
      <div class="policy-step"><span>01</span><div><strong>明细保留原币</strong><p>官方价格、价格版本、用量明细、调用链成本快照继续展示 CNY、USD、EUR 原始金额。</p></div></div>
      <i></i>
      <div class="policy-step"><span>02</span><div><strong>按月汇率折算</strong><p>使用请求发生月份的有效汇率；CNY 原始金额直接按 1:1 汇总。</p></div></div>
      <i></i>
      <div class="policy-step"><span>03</span><div><strong>汇总统一 CNY</strong><p>用量看板、成本单、预算预占和成本排名统一使用折算后的人民币金额。</p></div></div>
    </section>

    <section class="card data-surface fx-table-card">
      <div class="toolbar fx-toolbar">
        <div class="filters">
          <input v-model="filters.rateMonth" type="month" class="input compact-input" />
          <select v-model="filters.fromCurrency" class="input compact-select">
            <option value="">全部原币种</option>
            <option v-for="currency in managedCurrencies" :key="currency" :value="currency">{{ currencyLabel(currency) }}</option>
          </select>
          <select v-model="filters.status" class="input compact-select">
            <option value="">全部状态</option>
            <option value="ACTIVE">当前生效</option>
            <option value="SUPERSEDED">历史版本</option>
          </select>
          <button class="btn" @click="loadRates">查询</button>
          <button class="btn" @click="resetFilters">重置</button>
        </div>
        <div class="source-meta">
          <span>自动来源</span>
          <strong>ECB 官方参考汇率</strong>
          <em :title="fxStatus.sourceUrl">{{ fxStatus.sourceUrl || '—' }}</em>
        </div>
      </div>

      <div v-if="loading" class="state-panel"><span class="loading-mark"></span><strong>正在读取汇率版本</strong></div>
      <div v-else class="table-wrap fx-table-wrap">
        <table class="data-table fx-table">
          <thead><tr><th>汇率月份</th><th>折算方向</th><th>汇率</th><th>来源</th><th>来源日期</th><th>版本</th><th>状态</th><th>说明</th><th>更新时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id" :class="{manual:row.sourceType==='MANUAL',inactive:row.status!=='ACTIVE'}">
              <td>{{ row.rateMonth }}</td>
              <td><strong>{{ row.fromCurrency }}</strong><span class="direction">→</span><strong>{{ row.toCurrency }}</strong></td>
              <td><span class="rate-value">1 {{ row.fromCurrency }} = {{ formatRate(row.rate) }} {{ row.toCurrency }}</span></td>
              <td><span :class="['source-badge',row.sourceType==='MANUAL'?'manual-source':'auto-source']">{{ sourceLabel(row.sourceType) }}</span></td>
              <td>{{ row.sourceDate || '—' }}</td>
              <td>V{{ row.version }}</td>
              <td><span :class="['status',row.status==='ACTIVE'?'ok':'muted']">{{ row.status==='ACTIVE'?'当前生效':'历史版本' }}</span></td>
              <td class="note-cell" :title="row.note">{{ row.note || '—' }}</td>
              <td>{{ displayTime(row.updatedAt) }}</td>
              <td class="action-cell">
                <button v-if="row.status==='ACTIVE'" class="text-action" @click="editRate(row)">人工修改</button>
                <button v-if="row.status==='ACTIVE'&&row.sourceType==='MANUAL'" class="text-action danger-text" @click="restoreAuto(row)">恢复自动</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="!rows.length" class="state-panel empty-state"><strong>暂无汇率记录</strong><p>点击“立即同步”获取当月 ECB 参考汇率，或人工维护汇率。</p></div>
      </div>
      <footer v-if="!loading" class="pagination"><span>共 {{ rows.length }} 条</span></footer>
    </section>

    <a-modal v-model:open="manualVisible" title="人工维护月度汇率" :confirm-loading="manualSaving" ok-text="保存并生效" cancel-text="取消" @ok="saveManual">
      <div class="manual-form">
        <div class="manual-tip"><SafetyCertificateOutlined /><span>人工版本会立即成为该月份的当前有效汇率，并优先于自动同步；原自动版本保留为历史记录。</span></div>
        <label><span>汇率月份</span><input v-model="manual.rateMonth" type="month" class="input" /></label>
        <div class="currency-row">
          <label><span>原币种</span><select v-model="manual.fromCurrency" class="input"><option v-for="currency in managedCurrencies" :key="currency" :value="currency">{{ currencyLabel(currency) }}</option></select></label>
          <span class="currency-arrow">→</span>
          <label><span>目标币种</span><input value="CNY" class="input" disabled /></label>
        </div>
        <label><span>汇率</span><div class="rate-input"><i>1 {{ manual.fromCurrency }} =</i><input v-model="manual.rate" type="number" min="0" step="0.000001" class="input" /><i>CNY</i></div></label>
        <label><span>修改原因</span><textarea v-model.trim="manual.note" class="input textarea" maxlength="1000" placeholder="必填，例如：采用财务部 2026-08 月度管理汇率"></textarea></label>
      </div>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CalendarOutlined, CloudSyncOutlined, DollarCircleOutlined, EditOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons-vue'
import { api, errorMessage, normalizePayload } from '../api/client'
import { formatDateTime } from '../format'

type FxRow=Record<string,any>
const rows=ref<FxRow[]>([])
const fxStatus=reactive<any>({activeRates:[],missingCurrencies:[]})
const loading=ref(false),syncing=ref(false),autoSaving=ref(false),manualSaving=ref(false)
const error=ref(''),message=ref(''),manualVisible=ref(false)
const filters=reactive({rateMonth:'',fromCurrency:'',status:''})
const manual=reactive({rateMonth:currentMonth(),fromCurrency:'USD',rate:'',note:''})
const missingText=computed(()=>fxStatus.missingCurrencies?.length?`缺少：${fxStatus.missingCurrencies.join('、')}`:'当前月份币种完整')
const managedCurrencies=computed<string[]>(()=>fxStatus.managedCurrencies?.length?fxStatus.managedCurrencies:['USD'])

function currentMonth(){const d=new Date();return`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}`}
function monthDate(value:string){return value?`${value}-01`:undefined}
function sourceLabel(value:string){return value==='MANUAL'?'人工维护':value==='AUTOMATIC_ECB'?'ECB 自动同步':value||'—'}
function currencyLabel(value:string){return value==='USD'?'USD 美元':value==='EUR'?'EUR 欧元':value}
function formatRate(value:any){return new Intl.NumberFormat('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:8}).format(Number(value||0))}
function displayTime(value:any){return formatDateTime(value)||'—'}
function notify(text:string){message.value=text;window.setTimeout(()=>{if(message.value===text)message.value=''},5000)}
async function loadStatus(){const response=await api.get('/api/fx-rates/status');Object.assign(fxStatus,normalizePayload(response.data?.data||{}));if(!managedCurrencies.value.includes(manual.fromCurrency))manual.fromCurrency=managedCurrencies.value[0]||'USD'}
async function loadRates(){loading.value=true;error.value='';try{const response=await api.get('/api/fx-rates',{params:{rateMonth:monthDate(filters.rateMonth),fromCurrency:filters.fromCurrency||undefined,status:filters.status||undefined}});rows.value=normalizePayload(response.data?.data||[])}catch(e){error.value=errorMessage(e,'汇率列表')}finally{loading.value=false}}
async function loadAll(){try{await Promise.all([loadStatus(),loadRates()])}catch(e){error.value=errorMessage(e,'汇率管理')}}
function resetFilters(){Object.assign(filters,{rateMonth:'',fromCurrency:'',status:''});loadRates()}
async function syncNow(){syncing.value=true;error.value='';try{const response=await api.post('/api/fx-rates/sync');const result:any=normalizePayload(response.data?.data||{});if(result.status==='FAILED')throw new Error(result.message||'汇率同步失败');notify(`同步完成：写入 ${result.recordsWritten||0} 条，跳过 ${result.recordsSkipped||0} 条`);await loadAll()}catch(e){error.value=errorMessage(e,'汇率同步')}finally{syncing.value=false}}
async function setAutoUpdate(checked:any){autoSaving.value=true;error.value='';try{await api.put('/api/fx-rates/auto-update',{enabled:Boolean(checked)});fxStatus.autoUpdateEnabled=Boolean(checked);notify(Boolean(checked)?'已启用每月自动更新':'已停用每月自动更新')}catch(e){error.value=errorMessage(e,'自动更新设置')}finally{autoSaving.value=false}}
function editRate(row:FxRow){manual.rateMonth=String(row.rateMonth).slice(0,7);manual.fromCurrency=row.fromCurrency;manual.rate=String(row.rate);manual.note='';manualVisible.value=true}
async function saveManual(){if(!manual.rate||Number(manual.rate)<=0||!manual.note){error.value='人工汇率必须填写正数汇率和修改原因';return}manualSaving.value=true;error.value='';try{await api.post('/api/fx-rates/manual',{rateMonth:monthDate(manual.rateMonth),fromCurrency:manual.fromCurrency,toCurrency:'CNY',rate:manual.rate,note:manual.note});manualVisible.value=false;manual.note='';notify('人工汇率已保存并立即生效');await loadAll()}catch(e){error.value=errorMessage(e,'人工汇率')}finally{manualSaving.value=false}}
async function restoreAuto(row:FxRow){if(!window.confirm(`确认将 ${row.rateMonth} ${row.fromCurrency}→CNY 恢复为 ECB 自动汇率？`))return;syncing.value=true;error.value='';try{const response=await api.post(`/api/fx-rates/${row.id}/restore-auto`);const result:any=normalizePayload(response.data?.data||{});if(result.status==='FAILED')throw new Error(result.message||'恢复自动汇率失败');notify('已恢复为 ECB 自动汇率');await loadAll()}catch(e){error.value=errorMessage(e,'恢复自动汇率')}finally{syncing.value=false}}
onMounted(loadAll)
</script>

<style scoped>
.fx-page{padding-bottom:24px}.fx-header{display:flex;align-items:flex-end;justify-content:space-between;gap:18px}.header-actions{display:flex;gap:8px}.header-actions .btn{display:inline-flex;align-items:center;gap:6px}.inline-alert.ok{border-color:#b8e7ce;background:#effbf4;color:#146c43}.fx-summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:16px 0}.fx-summary-card{display:flex;min-height:112px;align-items:center;gap:14px;padding:18px;border:1px solid #e1e8f2;border-radius:14px;background:linear-gradient(145deg,#fff,#f9fbfe);box-shadow:0 7px 20px rgba(38,66,112,.06)}.fx-summary-card>div{display:flex;min-width:0;flex-direction:column}.fx-summary-card small{color:#75839a;font-size:11px;font-weight:700}.fx-summary-card strong{margin:4px 0;color:#17243d;font-size:22px}.fx-summary-card em{overflow:hidden;color:#7c899c;font-size:10px;font-style:normal;text-overflow:ellipsis;white-space:nowrap}.summary-icon{display:grid;width:44px;height:44px;flex:0 0 44px;place-items:center;border-radius:13px;background:#e9f8ef;color:#0d9d5b;font-size:20px}.summary-icon.blue{background:#eaf2ff;color:#2563eb}.summary-icon.green{background:#e9f8ef;color:#0f9f5d}.primary-card{background:linear-gradient(145deg,#eff5ff,#fff);border-color:#cfe0ff}.auto-card{justify-content:space-between}.fx-policy-card{display:grid;grid-template-columns:1fr 32px 1fr 32px 1fr;align-items:center;margin-bottom:14px;padding:14px 18px;background:linear-gradient(100deg,#f6f9ff,#f7fcfa)}.policy-step{display:flex;align-items:flex-start;gap:10px}.policy-step>span{display:grid;width:28px;height:28px;flex:0 0 28px;place-items:center;border-radius:9px;background:#e8f0ff;color:#2563eb;font-size:10px;font-weight:800}.policy-step strong{color:#263752;font-size:12px}.policy-step p{margin:3px 0 0;color:#748197;font-size:10px;line-height:1.5}.fx-policy-card>i{height:1px;background:linear-gradient(90deg,#cddaf0,#e8eef7)}.fx-table-card{overflow:hidden}.fx-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px}.fx-toolbar .filters{display:flex;gap:8px}.compact-input{width:150px}.compact-select{width:130px}.source-meta{display:grid;max-width:430px;grid-template-columns:auto auto;gap:2px 8px;text-align:right}.source-meta span{color:#8490a2;font-size:9px}.source-meta strong{color:#3a4b67;font-size:10px}.source-meta em{grid-column:1/-1;overflow:hidden;color:#98a3b3;font-size:9px;font-style:normal;text-overflow:ellipsis;white-space:nowrap}.fx-table-wrap{max-height:calc(100vh - 470px);min-height:300px;overflow:auto}.fx-table{min-width:1180px}.fx-table td{vertical-align:middle}.fx-table tr.manual{background:#fffdf6}.fx-table tr.inactive{opacity:.72}.direction{margin:0 6px;color:#8b98aa}.rate-value{color:#17243d;font-weight:700}.source-badge{display:inline-flex;padding:3px 7px;border-radius:999px;font-size:10px;font-weight:700}.auto-source{background:#eaf2ff;color:#2563eb}.manual-source{background:#fff1d9;color:#9b5b00}.status.muted{background:#eef1f5;color:#6d7888}.note-cell{max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.action-cell{white-space:nowrap}.text-action{margin-right:8px;border:0;background:transparent;color:#2563eb;font-size:11px;cursor:pointer}.danger-text{color:#b54848}.manual-form{display:grid;gap:14px}.manual-form label{display:grid;gap:6px}.manual-form label>span{color:#526079;font-size:12px;font-weight:700}.manual-tip{display:flex;align-items:flex-start;gap:8px;padding:10px;border:1px solid #d7e4fb;border-radius:9px;background:#f4f8ff;color:#536782;font-size:11px;line-height:1.5}.currency-row{display:grid;grid-template-columns:minmax(0,1fr) 30px minmax(0,1fr);align-items:end;gap:8px}.currency-row label{min-width:0}.currency-row .input,.rate-input .input{width:100%;min-width:0}.currency-arrow{padding-bottom:9px;color:#8a96a7;text-align:center}.rate-input{display:grid;grid-template-columns:max-content minmax(0,1fr) max-content;align-items:center;gap:8px}.rate-input i{color:#657289;font-size:11px;font-style:normal}.textarea{min-height:90px;padding:9px;resize:vertical}@media(max-width:1200px){.fx-summary-grid{grid-template-columns:repeat(2,1fr)}.fx-policy-card{grid-template-columns:1fr}.fx-policy-card>i{display:none}}@media(max-width:720px){.fx-header{align-items:stretch;flex-direction:column}.header-actions{flex-wrap:wrap}.fx-summary-grid{grid-template-columns:1fr}.fx-toolbar{align-items:stretch;flex-direction:column}.fx-toolbar .filters{flex-wrap:wrap}.source-meta{text-align:left}.currency-row{grid-template-columns:1fr}.currency-arrow{display:none}}
</style>
