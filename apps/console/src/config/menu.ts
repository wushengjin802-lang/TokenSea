export type MenuItem = { path:string;title:string;roles?:string[] }
export type MenuGroup = { key:string;title:string;icon:string;roles?:string[];defaultExpanded?:boolean;items:MenuItem[] }

export const menuGroups:MenuGroup[]=[
 {key:'operations',title:'日常运营',icon:'pulse',roles:['ADMIN'],defaultExpanded:true,items:[
  {path:'/keys',title:'API Key'},
  {path:'/logs',title:'调用日志'},
  {path:'/usage',title:'用量分析'},
  {path:'/alerts',title:'告警事件'},
  {path:'/provider-health',title:'渠道健康'}
 ]},
 {key:'model-config',title:'模型配置',icon:'box',roles:['ADMIN'],items:[
  {path:'/provider-channels',title:'供应商渠道'},
  {path:'/model-deployments',title:'模型部署'},
  {path:'/service-models',title:'企业服务模型'},
  {path:'/routes',title:'路由策略'},
  {path:'/reference-prices',title:'参考价格状态'}
 ]},
 {key:'org',title:'组织与权限',icon:'users',roles:['ADMIN'],items:[
  {path:'/accounts',title:'账户管理'},
  {path:'/roles',title:'角色管理'},
  {path:'/tenants',title:'租户管理'},
  {path:'/projects',title:'项目管理'},
  {path:'/apps',title:'应用管理'}
 ]},
 {key:'cost',title:'成本管理',icon:'wallet',roles:['ADMIN'],items:[
  {path:'/budget-rules',title:'预算管理'},
  {path:'/fx-rates',title:'汇率管理'},
  {path:'/cost-statements',title:'内部成本单'},
  {path:'/provider-billing-sources',title:'供应商账单源'},
  {path:'/provider-billing-sync-runs',title:'账单同步任务'},
  {path:'/provider-billing-records',title:'供应商账单明细'},
  {path:'/provider-billing-snapshots',title:'账单原始快照'},
  {path:'/provider-reconciliations',title:'供应商对账'}
 ]},
 {key:'governance',title:'高级治理',icon:'settings',roles:['ADMIN'],items:[
  {path:'/approvals',title:'治理审批'},
  {path:'/audit',title:'操作审计'},
  {path:'/system-settings',title:'系统基础设置'}
 ]},
 {key:'tenant',title:'租户工作台',icon:'users',items:[{path:'/workspace',title:'我的业务空间'}]},
 {key:'developer',title:'开发者门户',icon:'code',items:[
  {path:'/quick-start',title:'快速开始'},
  {path:'/developer-models',title:'服务模型列表'}
 ]}
]

export function visibleMenuGroups(roles:string[]){return menuGroups.filter(group=>!group.roles||group.roles.some(role=>roles.includes(role))).map(group=>({...group,items:group.items.filter(item=>!item.roles||item.roles.some(role=>roles.includes(role)))})).filter(group=>group.items.length)}
export function findMenuTitle(path:string){for(const group of menuGroups)for(const item of group.items)if(item.path===path)return item.title;return'页面未找到'}
