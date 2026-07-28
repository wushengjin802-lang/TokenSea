import { createRouter, createWebHistory } from 'vue-router'
import Login from './pages/Login.vue'
import Landing from './pages/Landing.vue'
import Layout from './layouts/MainLayout.vue'
import Dashboard from './pages/Dashboard.vue'
import DataPage from './pages/DataPage.vue'
import Keys from './pages/Keys.vue'
import Calls from './pages/Calls.vue'
import UsageAnalysis from './pages/UsageAnalysis.vue'
import RoutePolicies from './pages/RoutePolicies.vue'
import QuickStart from './pages/QuickStart.vue'
import DeveloperModels from './pages/DeveloperModels.vue'
import CostStatements from './pages/CostStatements.vue'
import FxRates from './pages/FxRates.vue'
import TenantWorkspace from './pages/TenantWorkspace.vue'
import AccessControl from './pages/AccessControl.vue'
import Alerts from './pages/Alerts.vue'
import NotFound from './pages/NotAvailable.vue'
import { identity, isAdmin } from './api/client'
import { resources, resourceRoutes } from './config/resources'

const opts=(values:[string,unknown][])=>values.map(([label,value])=>({label,value}))
const admin={requiresAdmin:true}
const tenantStatus=opts([['草稿','DRAFT'],['启用','ACTIVE'],['暂停','SUSPENDED']])
const managedResources=resourceRoutes.filter(([path])=>path!=='alerts').map(([path,key])=>({path,component:DataPage,meta:admin,props:resources[key]}))

const routes=[
  {path:'/login',component:Login},
  {path:'/',component:Layout,children:[
    {path:'',component:Landing},
    {path:'dashboard',component:Dashboard,meta:admin},
    {path:'alerts',component:Alerts,meta:admin},
    {path:'workspace',component:TenantWorkspace},
    {path:'accounts',component:AccessControl,props:{mode:'users'},meta:admin},
    {path:'roles',component:AccessControl,props:{mode:'roles'},meta:admin},
    {path:'tenants',component:DataPage,meta:admin,props:{title:'租户',desc:'管理租户责任边界、成本预算和可用服务模型。租户可先保存为草稿，启用前必须至少配置一个可用服务模型；启用租户不会自动创建 API Key。',apiPath:'/api/tenants',fields:['name','type','ownerName','contactEmail','modelScope','monthlyBudget','status'],labels:{name:'租户名称',type:'租户类型',ownerName:'负责人',contactEmail:'联系邮箱',modelScope:'可用服务模型',monthlyBudget:'月预算',status:'状态'},requiredFields:['name','type'],numberFields:['monthlyBudget'],optionSources:{modelScope:{path:'/api/platform-models/published',label:'displayName',value:'platformModelName',multiple:true}},fieldOptions:{type:opts([['内部租户','INTERNAL'],['外部客户','EXTERNAL']]),status:tenantStatus},fieldTypes:{type:'select',modelScope:'multiselect',status:'select'},statusInForm:true,defaultFormValues:{type:'INTERNAL',status:'DRAFT'},activationStatus:'ACTIVE',activationPath:'activate',builtinActions:['启用租户'],builtinActionMap:{'启用租户':':id/activate'},statePath:'status',stateLabel:'变更租户状态'}},
    {
      path:'projects',component:DataPage,meta:admin,props:{
        title:'项目',desc:'按租户管理项目，并集中查看关联应用、Virtual Key、用量、成本与预算占用。',apiPath:'/api/projects',
        fields:['tenantName','name','ownerName','appCount','keyCount','activeKeyCount','monthlyRequests','monthlyCostCny','budgetUsagePercent','status','lastCallAt'],
        editableFields:['tenantId','name','ownerName','monthlyBudget'],
        detailFields:['tenantName','name','ownerName','monthlyBudget','status','createdAt','updatedAt'],
        labels:{tenantId:'所属租户',tenantName:'所属租户',name:'项目名称',ownerName:'负责人',monthlyBudget:'月预算（CNY）',appCount:'应用数',keyCount:'Key 数',activeKeyCount:'启用 Key',monthlyRequests:'本月请求',monthlyTokens:'本月 Token',monthlyCostCny:'本月成本（CNY）',budgetUsagePercent:'预算使用率（%）',unconvertedCostCount:'未折算成本记录',lastCallAt:'最近调用',status:'状态'},
        requiredFields:['tenantId','name'],numberFields:['monthlyBudget'],
        optionSources:{tenantId:{path:'/api/tenants',label:'name',value:'id'}},
        fieldOptions:{status:tenantStatus},fieldTypes:{tenantId:'select',status:'select'},statePath:'status',stateLabel:'变更项目状态',
        detailSections:[
          {title:'本月概览',path:'/api/projects/:id/overview',fields:['appCount','keyCount','activeKeyCount','monthlyRequests','monthlyTokens','monthlyCostCny','budgetUsagePercent','unconvertedCostCount','lastCallAt'],labels:{appCount:'应用数',keyCount:'Key 数',activeKeyCount:'启用 Key',monthlyRequests:'本月请求',monthlyTokens:'本月 Token',monthlyCostCny:'本月成本（CNY）',budgetUsagePercent:'预算使用率（%）',unconvertedCostCount:'未折算成本记录',lastCallAt:'最近调用'}},
          {title:'关联应用',path:'/api/projects/:id/apps',fields:['name','environment','ownerName','keyCount','activeKeyCount','monthlyRequests','monthlyCostCny','status'],labels:{name:'应用',environment:'环境',ownerName:'负责人',keyCount:'Key 数',activeKeyCount:'启用 Key',monthlyRequests:'本月请求',monthlyCostCny:'本月成本（CNY）',status:'状态'}},
          {title:'关联 Virtual Key',path:'/api/projects/:id/keys',fields:['name','keyPrefix','appName','status','approvalStatus','budgetAmount','expiresAt'],labels:{name:'Key 名称',keyPrefix:'前缀',appName:'应用',status:'状态',approvalStatus:'审批状态',budgetAmount:'预算',expiresAt:'有效期'}},
          {title:'最近调用',path:'/api/projects/:id/usage',fields:['createdAt','apiKeyName','modelAlias','totalTokens','costAmount','currency','costCny','status','latencyMs'],labels:{createdAt:'时间',apiKeyName:'Virtual Key',modelAlias:'服务模型',totalTokens:'总 Token',costAmount:'原始成本',currency:'原币种',costCny:'折算 CNY',status:'状态',latencyMs:'耗时（毫秒）'}}
        ]
      }
    },
    {
      path:'apps',component:DataPage,meta:admin,props:{
        title:'应用',desc:'应用严格归属于租户和项目，并集中查看关联 Virtual Key、调用量、成本和运行状态。',apiPath:'/api/apps',
        fields:['tenantName','projectName','name','environment','ownerName','keyCount','activeKeyCount','monthlyRequests','monthlyTokens','monthlyCostCny','successRate','status','lastCallAt'],
        editableFields:['tenantId','projectId','name','ownerName','environment'],
        detailFields:['tenantName','projectName','name','environment','ownerName','status','createdAt','updatedAt'],
        labels:{tenantId:'所属租户',tenantName:'所属租户',projectId:'所属项目',projectName:'所属项目',name:'应用名称',ownerName:'负责人',environment:'环境',keyCount:'Key 数',activeKeyCount:'启用 Key',monthlyRequests:'本月请求',monthlyTokens:'本月 Token',monthlyCostCny:'本月成本（CNY）',successRate:'成功率（%）',unconvertedCostCount:'未折算成本记录',lastCallAt:'最近调用',status:'状态'},
        requiredFields:['tenantId','projectId','name'],
        optionSources:{tenantId:{path:'/api/tenants',label:'name',value:'id'},projectId:{path:'/api/projects?tenantId={tenantId}',label:'name',value:'id',dependsOn:'tenantId'}},
        fieldOptions:{environment:opts([['开发','DEV'],['测试','TEST'],['生产','PROD']]),status:tenantStatus},fieldTypes:{tenantId:'select',projectId:'select',environment:'select',status:'select'},statePath:'status',stateLabel:'变更应用状态',
        detailSections:[
          {title:'本月概览',path:'/api/apps/:id/overview',fields:['keyCount','activeKeyCount','monthlyRequests','monthlyTokens','monthlyCostCny','successRate','unconvertedCostCount','lastCallAt'],labels:{keyCount:'Key 数',activeKeyCount:'启用 Key',monthlyRequests:'本月请求',monthlyTokens:'本月 Token',monthlyCostCny:'本月成本（CNY）',successRate:'成功率（%）',unconvertedCostCount:'未折算成本记录',lastCallAt:'最近调用'}},
          {title:'关联 Virtual Key',path:'/api/apps/:id/keys',fields:['name','keyPrefix','status','approvalStatus','modelScope','budgetAmount','rpmLimit','tpmLimit','expiresAt'],labels:{name:'Key 名称',keyPrefix:'前缀',status:'状态',approvalStatus:'审批状态',modelScope:'允许调用的服务模型',budgetAmount:'预算',rpmLimit:'每分钟请求',tpmLimit:'每分钟 Token',expiresAt:'有效期'}},
          {title:'最近调用',path:'/api/apps/:id/usage',fields:['createdAt','apiKeyName','modelAlias','totalTokens','costAmount','currency','costCny','status','latencyMs'],labels:{createdAt:'时间',apiKeyName:'Virtual Key',modelAlias:'服务模型',totalTokens:'总 Token',costAmount:'原始成本',currency:'原币种',costCny:'折算 CNY',status:'状态',latencyMs:'耗时（毫秒）'}}
        ]
      }
    },
    ...managedResources,
    {path:'routes',component:RoutePolicies,meta:admin},
    {path:'keys',component:Keys,meta:admin},
    {path:'usage',component:UsageAnalysis,meta:admin},
    {path:'logs',component:Calls,props:{mode:'logs'},meta:admin},
    {path:'cost-statements',component:CostStatements,meta:admin},
    {path:'fx-rates',component:FxRates,meta:admin},
    {path:'quick-start',component:QuickStart},
    {path:'developer-models',component:DeveloperModels},
    {path:'playground',redirect:'/quick-start'},
    {path:'providers',redirect:'/provider-channels'},
    {path:'models',redirect:'/service-models'},
    {path:'pricing',redirect:'/price-versions'},
    {path:'billing',redirect:'/cost-statements'},
    {path:':pathMatch(.*)*',component:NotFound}
  ]}
]
const router=createRouter({history:createWebHistory(),routes})
router.beforeEach(to=>{if(to.path==='/')return true;if(to.path==='/login')return localStorage.getItem('tokensea_token')?(isAdmin()?'/dashboard':'/workspace'):true;if(!localStorage.getItem('tokensea_token'))return{path:'/login',query:{redirect:to.fullPath}};if(to.meta.requiresAdmin&&!identity().roles.includes('ADMIN'))return'/workspace';return true})
export default router
