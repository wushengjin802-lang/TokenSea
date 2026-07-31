<template>
  <section class="page extraction-page">
    <header class="page-header extraction-header">
      <div>
        <div class="eyebrow">PRICE EVIDENCE REVIEW</div>
        <h1 class="page-title">价格文档提取审核</h1>
        <p class="page-desc">逐条核对官方原文证据与标准化价格。LLM、低置信度或校验告警记录必须完成审核后，才能进入现有价格差异流程。</p>
      </div>
      <div class="header-actions">
        <button class="btn" :disabled="loading" @click="loadRuns">刷新</button>
        <button class="btn primary" :disabled="!selectedRun || pendingCount > 0 || submitting" @click="submitRun">
          {{ submitting ? '提交中…' : '提交价格差异' }}
        </button>
      </div>
    </header>

    <div v-if="error" class="inline-alert danger">{{ error }}</div>
    <div v-if="message" class="inline-alert ok">{{ message }}</div>

    <section class="metric-strip">
      <article><span>待审核运行</span><strong>{{ runs.length }}</strong></article>
      <article><span>当前记录</span><strong>{{ records.length }}</strong></article>
      <article><span>仍待处理</span><strong class="warn-text">{{ pendingCount }}</strong></article>
      <article><span>证据完整率</span><strong>{{ evidenceRate }}</strong></article>
    </section>

    <div class="review-layout">
      <aside class="card run-panel">
        <div class="panel-heading">
          <div><small>抽取运行</small><strong>待审核任务</strong></div>
          <select v-model="runStatus" class="input compact" @change="loadRuns">
            <option value="REVIEW_REQUIRED">待审核</option>
            <option value="SUCCEEDED">已提交</option>
            <option value="FAILED">失败</option>
            <option value="">全部</option>
          </select>
        </div>
        <div v-if="loading" class="state-panel"><strong>正在读取抽取运行</strong></div>
        <button
          v-for="run in runs"
          v-else
          :key="run.id"
          :class="['run-item', { active: selectedRun?.id === run.id }]"
          @click="selectRun(run)"
        >
          <div class="run-title"><strong>{{ run.sourceName }}</strong><span :class="['pill', statusTone(run.status)]">{{ statusLabel(run.status) }}</span></div>
          <p>{{ run.documentType }} · {{ modeLabel(run.extractionMode) }}</p>
          <div class="run-counts"><span>确定性 {{ run.deterministicRecordCount || 0 }}</span><span>LLM {{ run.llmRecordCount || 0 }}</span><span>接受 {{ run.acceptedRecordCount || 0 }}</span></div>
          <time>{{ displayTime(run.createdAt) }}</time>
        </button>
        <div v-if="!loading && !runs.length" class="state-panel empty-state"><strong>没有待审核任务</strong><p>通用文档同步产生的人工审核记录会出现在这里。</p></div>
      </aside>

      <main class="review-main">
        <section v-if="selectedRun" class="card run-summary">
          <div class="summary-main">
            <div><small>价格源</small><strong>{{ selectedRun.sourceName }}</strong></div>
            <div><small>文档 / Schema</small><strong>{{ selectedRun.documentType }} · {{ selectedRun.schemaVersion }}</strong></div>
            <div><small>提取器链路</small><strong>{{ modeLabel(selectedRun.extractionMode) }}</strong></div>
            <div><small>平均置信度</small><strong>{{ percent(confidenceSummary.average) }}</strong></div>
            <div><small>证据完整率</small><strong>{{ percent(confidenceSummary.evidenceCompleteness) }}</strong></div>
          </div>
          <div class="source-ref" :title="snapshot.sourceEndpoint">{{ snapshot.sourceEndpoint || '—' }}</div>
        </section>

        <section v-if="selectedRun" class="card records-card">
          <div class="panel-heading records-heading">
            <div><small>标准化记录</small><strong>证据与校验结果</strong></div>
            <div class="record-filters">
              <select v-model="recordStatus" class="input compact">
                <option value="">全部状态</option>
                <option value="PENDING">待审核</option>
                <option value="ACCEPTED">已接受</option>
                <option value="CORRECTED">已修正</option>
                <option value="REJECTED">已驳回</option>
                <option value="NON_PRICE">非价格内容</option>
              </select>
            </div>
          </div>
          <div class="table-wrap">
            <table class="data-table extraction-table">
              <thead><tr><th>模型</th><th>方法</th><th>置信度</th><th>校验</th><th>证据位置</th><th>审核状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="record in filteredRecords" :key="record.id" :class="{ selected: selectedRecord?.id === record.id }" @click="selectedRecord=record">
                  <td><strong>{{ record.providerModelName }}</strong><small>{{ record.providerType }}</small></td>
                  <td>{{ methodLabel(record.extractionMethod) }}</td>
                  <td><span :class="['confidence', confidenceTone(record.confidence)]">{{ percent(record.confidence) }}</span></td>
                  <td><span :class="['pill', validationTone(record.validationStatus)]">{{ validationLabel(record.validationStatus) }}</span></td>
                  <td>{{ evidenceLocation(record) }}</td>
                  <td><span :class="['pill', statusTone(record.reviewStatus)]">{{ reviewLabel(record.reviewStatus) }}</span></td>
                  <td class="action-cell" @click.stop>
                    <button v-if="record.reviewStatus==='PENDING'" class="text-action" @click="openReview(record,'ACCEPTED')">接受</button>
                    <button v-if="record.reviewStatus==='PENDING'" class="text-action" @click="openReview(record,'CORRECTED')">修正</button>
                    <button v-if="record.reviewStatus==='PENDING'" class="text-action danger-text" @click="openReview(record,'REJECTED')">驳回</button>
                    <button v-if="record.reviewStatus==='PENDING'" class="text-action muted-text" @click="openReview(record,'NON_PRICE')">非价格</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section v-if="selectedRecord" class="evidence-grid">
          <article class="card evidence-card">
            <div class="panel-heading"><div><small>原始证据</small><strong>{{ evidenceLocation(selectedRecord) }}</strong></div><span class="hash">{{ selectedRecord.sourceHash || '—' }}</span></div>
            <pre>{{ selectedRecord.sourceText || '没有可显示的原文证据' }}</pre>
          </article>
          <article class="card normalized-card">
            <div class="panel-heading"><div><small>标准化价格</small><strong>{{ selectedRecord.providerModelName }}</strong></div><span :class="['pill', validationTone(selectedRecord.validationStatus)]">{{ validationLabel(selectedRecord.validationStatus) }}</span></div>
            <dl class="price-facts">
              <template v-for="item in normalizedFacts" :key="item.label"><dt>{{ item.label }}</dt><dd>{{ item.value }}</dd></template>
            </dl>
            <div v-if="validationMessages.length" class="validation-list"><p v-for="item in validationMessages" :key="item">{{ item }}</p></div>
          </article>
        </section>

        <div v-if="!selectedRun" class="card state-panel empty-state large"><strong>请选择一个抽取运行</strong><p>选择左侧任务后，可查看原文证据、标准化价格和审核动作。</p></div>
      </main>
    </div>

    <a-modal v-model:open="reviewVisible" :title="reviewTitle" :confirm-loading="reviewing" ok-text="确认" cancel-text="取消" width="760px" @ok="saveReview">
      <div class="review-form">
        <div class="review-tip">{{ reviewTip }}</div>
        <label v-if="reviewDecision==='CORRECTED'"><span>修正后的标准化记录 JSON</span><textarea v-model="correctionText" class="input json-editor" spellcheck="false"></textarea></label>
        <label><span>审核说明</span><textarea v-model.trim="reviewReason" class="input reason-editor" maxlength="1000" :placeholder="reviewDecision==='ACCEPTED'?'可选：说明证据核对结果':'必填：说明修正或拒绝原因'"></textarea></label>
      </div>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { errorMessage, get, postAction, queryPage } from '../api/client'
import { formatDateTime } from '../format'

type Row = Record<string, any>
const runs=ref<Row[]>([]),records=ref<Row[]>([])
const selectedRun=ref<Row|null>(null),selectedRecord=ref<Row|null>(null)
const loading=ref(false),submitting=ref(false),reviewing=ref(false)
const error=ref(''),message=ref(''),runStatus=ref('REVIEW_REQUIRED'),recordStatus=ref('')
const snapshot=ref<Row>({}),confidenceSummary=ref<Row>({})
const reviewVisible=ref(false),reviewDecision=ref('ACCEPTED'),reviewRecord=ref<Row|null>(null)
const correctionText=ref(''),reviewReason=ref('')
const filteredRecords=computed(()=>recordStatus.value?records.value.filter(item=>item.reviewStatus===recordStatus.value):records.value)
const pendingCount=computed(()=>records.value.filter(item=>item.reviewStatus==='PENDING').length)
const evidenceRate=computed(()=>{if(!records.value.length)return'—';const count=records.value.filter(item=>String(item.sourceText||'').trim()).length;return percent(count/records.value.length)})
const normalizedRecord=computed(()=>selectedRecord.value?.normalizedRecord||{})
const normalizedFacts=computed(()=>[
  {label:'供应商',value:normalizedRecord.value.providerType||'—'},
  {label:'模型',value:normalizedRecord.value.providerModelName||'—'},
  {label:'币种',value:normalizedRecord.value.currency||'—'},
  {label:'输入单价',value:money(normalizedRecord.value.inputUnitPrice,normalizedRecord.value.currency)},
  {label:'输出单价',value:money(normalizedRecord.value.outputUnitPrice,normalizedRecord.value.currency)},
  {label:'计费单位',value:`${normalizedRecord.value.billingQuantity||'—'} ${normalizedRecord.value.billingBasis||''}`},
  {label:'区域',value:normalizedRecord.value.region||'global'},
  {label:'调用模式',value:normalizedRecord.value.requestMode||'STANDARD'},
])
const validationMessages=computed(()=>{
  const value=selectedRecord.value?.validationResult||{};
  return [...(value.errors||[]).map((item:string)=>`错误：${item}`),...(value.warnings||[]).map((item:string)=>`警告：${item}`)]
})
const reviewTitle=computed(()=>({ACCEPTED:'接受抽取记录',CORRECTED:'修正抽取记录',REJECTED:'驳回抽取记录',NON_PRICE:'标记为非价格内容'} as Row)[reviewDecision.value]||'审核抽取记录')
const reviewTip=computed(()=>({
  ACCEPTED:'确认原文证据与模型、币种、计费单位和价格完全一致。',
  CORRECTED:'仅修正文档明确支持的字段，修正结果会重新经过后端校验。',
  REJECTED:'该记录存在错误或证据不足，不允许进入价格差异。',
  NON_PRICE:'该段内容不是可发布的模型价格，例如说明文字、免费额度或营销信息。',
} as Row)[reviewDecision.value])

function displayTime(value:any){return formatDateTime(value)||'—'}
function percent(value:any){const number=Number(value);return Number.isFinite(number)?`${(number*100).toFixed(1)}%`:'—'}
function money(value:any,currency:any){if(value===null||value===undefined||value==='')return'—';return`${currency||''} ${new Intl.NumberFormat('zh-CN',{maximumFractionDigits:8}).format(Number(value))}`.trim()}
function statusLabel(value:string){return({REVIEW_REQUIRED:'待审核',SUCCEEDED:'已提交',FAILED:'失败',RUNNING:'执行中'} as Row)[value]||value||'—'}
function reviewLabel(value:string){return({PENDING:'待审核',ACCEPTED:'已接受',CORRECTED:'已修正',REJECTED:'已驳回',NON_PRICE:'非价格'} as Row)[value]||value||'—'}
function validationLabel(value:string){return({VALID:'通过',WARNING:'有警告',INVALID:'未通过',PENDING:'待校验'} as Row)[value]||value||'—'}
function modeLabel(value:string){return({DETERMINISTIC:'确定性提取',DETERMINISTIC_LLM:'确定性 + LLM',SPECIALIZED:'专用解析器'} as Row)[value]||value||'—'}
function methodLabel(value:string){return String(value||'').startsWith('LLM')?'LLM Schema 映射':'确定性映射'}
function statusTone(value:string){return['SUCCEEDED','ACCEPTED','CORRECTED'].includes(value)?'ok':['FAILED','REJECTED','NON_PRICE'].includes(value)?'danger':'warn'}
function validationTone(value:string){return value==='VALID'?'ok':value==='INVALID'?'danger':'warn'}
function confidenceTone(value:any){const number=Number(value);return number>=.9?'high':number>=.75?'medium':'low'}
function evidenceLocation(row:Row){return[row.pageNumber?`第 ${row.pageNumber} 页`:'',row.tableIndex!==null&&row.tableIndex!==undefined?`表 ${Number(row.tableIndex)+1}`:'',row.rowIndex!==null&&row.rowIndex!==undefined?`行 ${Number(row.rowIndex)+1}`:''].filter(Boolean).join(' / ')||'文档片段'}
function notify(text:string){message.value=text;window.setTimeout(()=>{if(message.value===text)message.value=''},5000)}
async function loadRuns(){loading.value=true;error.value='';try{const result=await queryPage<Row>('/api/price-document-extraction-runs',{status:runStatus.value||undefined,size:100,sort:'createdAt',order:'desc'});runs.value=result.items;if(selectedRun.value){const current=runs.value.find(item=>item.id===selectedRun.value?.id);if(current)await selectRun(current);else clearSelection()}else if(runs.value.length)await selectRun(runs.value[0])}catch(e){error.value=errorMessage(e,'价格文档抽取运行')}finally{loading.value=false}}
function clearSelection(){selectedRun.value=null;selectedRecord.value=null;records.value=[];snapshot.value={};confidenceSummary.value={}}
async function selectRun(run:Row){error.value='';try{const detail:any=await get(`/api/price-document-extraction-runs/${run.id}`);selectedRun.value={...run,...detail};records.value=detail.records||[];snapshot.value=detail.snapshot||{};confidenceSummary.value=detail.confidenceSummary||{};selectedRecord.value=records.value.find(item=>item.reviewStatus==='PENDING')||records.value[0]||null}catch(e){error.value=errorMessage(e,'抽取运行详情')}}
function openReview(record:Row,decision:string){reviewRecord.value=record;reviewDecision.value=decision;reviewReason.value='';correctionText.value=JSON.stringify(record.normalizedRecord||{},null,2);reviewVisible.value=true}
async function saveReview(){if(!reviewRecord.value)return;if(reviewDecision.value!=='ACCEPTED'&&!reviewReason.value){error.value='修正、驳回或标记非价格时必须填写审核说明';return}let correction:Row={};if(reviewDecision.value==='CORRECTED'){try{correction=JSON.parse(correctionText.value)}catch{error.value='修正记录不是有效 JSON';return}}reviewing.value=true;error.value='';try{await postAction(`/api/price-document-extracted-records/${reviewRecord.value.id}/review`,{decision:reviewDecision.value,correction,reason:reviewReason.value});reviewVisible.value=false;notify('抽取记录审核结果已保存');if(selectedRun.value)await selectRun(selectedRun.value);await loadRuns()}catch(e){error.value=errorMessage(e,'价格文档抽取审核')}finally{reviewing.value=false}}
async function submitRun(){if(!selectedRun.value||pendingCount.value>0)return;const reason=window.prompt('请输入提交价格差异的说明（可选）','已完成全部原文证据核对')||'';submitting.value=true;error.value='';try{const result:any=await postAction(`/api/price-document-extraction-runs/${selectedRun.value.id}/submit`,{reason});notify(`已提交：接受 ${result.accepted||0} 条，生成或更新差异 ${result.changed||0} 条`);clearSelection();await loadRuns()}catch(e){error.value=errorMessage(e,'提交价格差异')}finally{submitting.value=false}}
onMounted(loadRuns)
</script>

<style scoped>
.extraction-page{padding-bottom:24px}.extraction-header{display:flex;align-items:flex-end;justify-content:space-between;gap:20px}.eyebrow{margin-bottom:5px;color:#2c67cb;font-size:10px;font-weight:800;letter-spacing:.18em}.header-actions{display:flex;gap:8px}.inline-alert.ok{border-color:#b8e7ce;background:#effbf4;color:#146c43}.metric-strip{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin:16px 0}.metric-strip article{display:flex;min-height:76px;flex-direction:column;justify-content:center;padding:14px 18px;border:1px solid #e1e8f2;border-radius:12px;background:linear-gradient(145deg,#fff,#f8fbff);box-shadow:0 6px 18px rgba(35,61,103,.05)}.metric-strip span{color:#7a879a;font-size:10px;font-weight:700}.metric-strip strong{margin-top:4px;color:#17243d;font-size:23px}.warn-text{color:#b66a10!important}.review-layout{display:grid;grid-template-columns:310px minmax(0,1fr);gap:14px;align-items:start}.run-panel{position:sticky;top:14px;max-height:calc(100vh - 170px);overflow:auto;padding:0}.panel-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:14px 16px;border-bottom:1px solid #e5ebf3}.panel-heading>div{display:flex;flex-direction:column}.panel-heading small{color:#8894a6;font-size:9px;font-weight:700;letter-spacing:.08em}.panel-heading strong{margin-top:2px;color:#263650;font-size:13px}.compact{width:118px;padding:6px 8px;font-size:10px}.run-item{display:block;width:100%;padding:13px 15px;border:0;border-bottom:1px solid #edf1f6;background:#fff;text-align:left;cursor:pointer;transition:background .16s,border-color .16s}.run-item:hover{background:#f7faff}.run-item.active{border-left:3px solid #2d68d4;background:#f1f6ff}.run-title{display:flex;align-items:center;justify-content:space-between;gap:8px}.run-title strong{overflow:hidden;color:#253653;font-size:12px;text-overflow:ellipsis;white-space:nowrap}.run-item p{margin:5px 0;color:#6e7c91;font-size:10px}.run-counts{display:flex;flex-wrap:wrap;gap:7px;color:#52637d;font-size:9px}.run-item time{display:block;margin-top:7px;color:#99a3b2;font-size:9px}.pill{display:inline-flex;padding:3px 7px;border-radius:999px;font-size:9px;font-weight:800;white-space:nowrap}.pill.ok{background:#e7f7ee;color:#118a50}.pill.warn{background:#fff2d9;color:#9b5a00}.pill.danger{background:#fdeaea;color:#b33b3b}.review-main{display:grid;gap:14px;min-width:0}.run-summary{padding:14px 16px}.summary-main{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px}.summary-main>div{display:flex;min-width:0;flex-direction:column}.summary-main small{color:#8591a3;font-size:9px}.summary-main strong{margin-top:3px;overflow:hidden;color:#253653;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.source-ref{margin-top:12px;padding-top:10px;border-top:1px dashed #dce4ef;overflow:hidden;color:#748299;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.records-card{overflow:hidden}.records-heading{padding-bottom:11px}.record-filters{display:flex!important;flex-direction:row!important}.table-wrap{max-height:360px;overflow:auto}.extraction-table{min-width:900px}.extraction-table tr{cursor:pointer}.extraction-table tr.selected{background:#f2f7ff}.extraction-table td strong{display:block;color:#263750;font-size:11px}.extraction-table td small{display:block;margin-top:2px;color:#8995a6;font-size:9px}.confidence{font-weight:800}.confidence.high{color:#0e8d50}.confidence.medium{color:#a7650e}.confidence.low{color:#b53c3c}.action-cell{white-space:nowrap}.text-action{margin-right:7px;border:0;background:transparent;color:#2464c7;font-size:10px;cursor:pointer}.danger-text{color:#b43d3d}.muted-text{color:#7c8797}.evidence-grid{display:grid;grid-template-columns:minmax(0,1.05fr) minmax(0,.95fr);gap:14px}.evidence-card,.normalized-card{min-width:0;overflow:hidden}.hash{max-width:180px;overflow:hidden;color:#9aa5b5;font-family:monospace;font-size:8px;text-overflow:ellipsis;white-space:nowrap}.evidence-card pre{max-height:340px;margin:0;padding:18px;overflow:auto;background:#182235;color:#dce8f8;font-family:"Cascadia Code","Microsoft YaHei",monospace;font-size:10px;line-height:1.7;white-space:pre-wrap}.price-facts{display:grid;grid-template-columns:110px minmax(0,1fr);margin:0;padding:15px 16px}.price-facts dt,.price-facts dd{margin:0;padding:7px 0;border-bottom:1px solid #edf1f6;font-size:10px}.price-facts dt{color:#8490a2}.price-facts dd{color:#263750;font-weight:700}.validation-list{margin:0 16px 16px;padding:10px 12px;border:1px solid #f0d8b2;border-radius:8px;background:#fff9ed}.validation-list p{margin:3px 0;color:#87551a;font-size:9px;line-height:1.5}.large{min-height:420px}.review-form{display:grid;gap:14px}.review-tip{padding:10px 12px;border:1px solid #d9e5f8;border-radius:8px;background:#f5f8fd;color:#536782;font-size:11px;line-height:1.6}.review-form label{display:grid;gap:6px}.review-form label>span{color:#526079;font-size:11px;font-weight:700}.json-editor{min-height:300px;padding:12px;font-family:"Cascadia Code",monospace;font-size:10px;line-height:1.6;resize:vertical}.reason-editor{min-height:90px;padding:10px;resize:vertical}@media(max-width:1280px){.review-layout{grid-template-columns:260px minmax(0,1fr)}.summary-main{grid-template-columns:repeat(3,1fr)}.evidence-grid{grid-template-columns:1fr}}@media(max-width:860px){.extraction-header{align-items:stretch;flex-direction:column}.metric-strip{grid-template-columns:repeat(2,1fr)}.review-layout{grid-template-columns:1fr}.run-panel{position:static;max-height:360px}.summary-main{grid-template-columns:repeat(2,1fr)}}
</style>
