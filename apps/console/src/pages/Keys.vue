<template><DataPage title="API Key" desc="创建、审批、生成和禁用 TokenSea API Key。明文只在生成时返回一次。" api-path="/api/keys" :fields="fields" :labels="labels" :required-fields="['tenantId','name','modelScope']" :number-fields="['budgetAmount','rpmLimit','tpmLimit','qpsLimit']" :option-sources="sources" :field-options="options" :builtin-actions="['审批通过','拒绝','生成密钥','禁用']" :builtin-action-map="actions" :allow-edit="false"/></template>
<script setup lang="ts">
import DataPage from'./DataPage.vue'
const fields=['tenantId','projectId','appId','name','keyPrefix','status','approvalStatus','modelScope','budgetAmount','rpmLimit','tpmLimit','qpsLimit','ipWhitelist','expiresAt']
const labels:Record<string,string>={tenantId:'租户',projectId:'项目',appId:'应用',name:'Key 名称',keyPrefix:'Key 前缀',status:'状态',approvalStatus:'审批状态',modelScope:'模型范围',budgetAmount:'预算',rpmLimit:'每分钟请求',tpmLimit:'每分钟 Token',qpsLimit:'每秒请求',ipWhitelist:'IP 白名单',expiresAt:'有效期'}
const sources={tenantId:{path:'/api/tenants',label:'name',value:'id'},projectId:{path:'/api/projects',label:'name',value:'id'},appId:{path:'/api/apps',label:'name',value:'id'},modelScope:{path:'/api/platform-models',label:'displayName',value:'platformModelName',multiple:true}}
const options={status:[{label:'待处理',value:'PENDING'},{label:'启用',value:'ACTIVE'},{label:'停用',value:'DISABLED'}],approvalStatus:[{label:'待审批',value:'PENDING'},{label:'已通过',value:'APPROVED'},{label:'已拒绝',value:'REJECTED'}]}
const actions={'审批通过':':id/approve','拒绝':':id/reject','生成密钥':':id/generate','禁用':':id/disable'}
</script>
