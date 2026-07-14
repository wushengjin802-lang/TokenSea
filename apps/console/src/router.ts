import { createRouter, createWebHistory } from 'vue-router'
import Login from './pages/Login.vue'
import Landing from './pages/Landing.vue'
import Layout from './layouts/MainLayout.vue'
import Dashboard from './pages/Dashboard.vue'
import DataPage from './pages/DataPage.vue'
import Keys from './pages/Keys.vue'
import Calls from './pages/Calls.vue'
import RoutePolicies from './pages/RoutePolicies.vue'
import Playground from './pages/Playground.vue'
import QuickStart from './pages/QuickStart.vue'
import DeveloperModels from './pages/DeveloperModels.vue'
import CostStatements from './pages/CostStatements.vue'
import TenantWorkspace from './pages/TenantWorkspace.vue'
import NotFound from './pages/NotAvailable.vue'
import { identity, isAdmin } from './api/client'
import { resources, resourceRoutes } from './config/resources'

const opts=(values:[string,unknown][])=>values.map(([label,value])=>({label,value}))
const admin={requiresAdmin:true}
const tenantStatus=opts([['草稿','DRAFT'],['启用','ACTIVE'],['暂停','SUSPENDED']])
const managedResources=resourceRoutes.map(([path,key])=>({path,component:DataPage,meta:admin,props:resources[key]}))

const routes=[
  {path:'/login',component:Login},
  {path:'/',component:Layout,children:[
    {path:'',component:Landing},
    {path:'dashboard',component:Dashboard,meta:admin},
    {path:'workspace',component:TenantWorkspace},
    {path:'tenants',component:DataPage,meta:admin,props:{title:'租户',desc:'管理租户责任边界、成本预算和模型范围。',apiPath:'/api/tenants',fields:['name','type','ownerName','contactEmail','modelScope','monthlyBudget','status'],labels:{name:'租户名称',type:'租户类型',ownerName:'负责人',contactEmail:'联系邮箱',modelScope:'模型范围',monthlyBudget:'月预算',status:'状态'},requiredFields:['name','type','modelScope'],numberFields:['monthlyBudget'],optionSources:{modelScope:{path:'/api/platform-models',label:'displayName',value:'platformModelName',multiple:true}},fieldOptions:{type:opts([['内部租户','INTERNAL'],['外部客户','EXTERNAL']]),status:tenantStatus},fieldTypes:{type:'select',modelScope:'multiselect',status:'select'},statusInForm:true,defaultFormValues:{type:'INTERNAL',status:'DRAFT'},activationStatus:'ACTIVE',activationPath:'activate',builtinActions:['启用并生成默认 Key'],builtinActionMap:{'启用并生成默认 Key':':id/activate'},statePath:'status',stateLabel:'变更租户状态'}},
    {path:'projects',component:DataPage,meta:admin,props:{title:'项目',desc:'按租户管理项目成本预算与负责人。',apiPath:'/api/projects',fields:['tenantId','name','ownerName','monthlyBudget','status'],labels:{tenantId:'所属租户',name:'项目名称',ownerName:'负责人',monthlyBudget:'月预算',status:'状态'},requiredFields:['tenantId','name'],numberFields:['monthlyBudget'],optionSources:{tenantId:{path:'/api/tenants',label:'name',value:'id'}},fieldOptions:{status:tenantStatus},fieldTypes:{tenantId:'select',status:'select'},statePath:'status'}},
    {path:'apps',component:DataPage,meta:admin,props:{title:'应用',desc:'维护真实业务应用与项目归属。',apiPath:'/api/apps',fields:['tenantId','projectId','name','ownerName','environment','status'],labels:{tenantId:'所属租户',projectId:'所属项目',name:'应用名称',ownerName:'负责人',environment:'环境',status:'状态'},requiredFields:['tenantId','projectId','name'],optionSources:{tenantId:{path:'/api/tenants',label:'name',value:'id'},projectId:{path:'/api/projects',label:'name',value:'id'}},fieldOptions:{environment:opts([['开发','DEV'],['测试','TEST'],['生产','PROD']]),status:tenantStatus},fieldTypes:{tenantId:'select',projectId:'select',environment:'select',status:'select'},statePath:'status'}},
    ...managedResources,
    {path:'routes',component:RoutePolicies,meta:admin},
    {path:'keys',component:Keys,meta:admin},
    {path:'usage',component:Calls,props:{mode:'usage'},meta:admin},
    {path:'logs',component:Calls,props:{mode:'logs'},meta:admin},
    {path:'cost-statements',component:CostStatements,meta:admin},
    {path:'quick-start',component:QuickStart},
    {path:'developer-models',component:DeveloperModels},
    {path:'playground',component:Playground},
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
