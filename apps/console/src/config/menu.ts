export type MenuItem = { path:string;title:string;roles?:string[] }
export type MenuGroup = { key:string;title:string;icon:string;roles?:string[];items:MenuItem[] }
export const menuGroups:MenuGroup[]=[
 {key:'assets',title:'模型资产',icon:'box',roles:['ADMIN'],items:[{path:'/provider-templates',title:'供应商模板'},{path:'/provider-channels',title:'供应商渠道'},{path:'/model-references',title:'公共模型参考库'},{path:'/model-deployments',title:'模型部署'},{path:'/service-models',title:'企业服务模型'},{path:'/capability-validations',title:'能力验证'},{path:'/price-versions',title:'成本价格'}]},
 {key:'sync',title:'同步中心',icon:'route',roles:['ADMIN'],items:[{path:'/data-sources',title:'数据源管理'},{path:'/sync-jobs',title:'同步任务'},{path:'/model-discoveries',title:'模型发现'},{path:'/discovery-diffs',title:'差异审核'}]},
 {key:'access',title:'访问治理',icon:'key',roles:['ADMIN'],items:[{path:'/keys',title:'Virtual Key'},{path:'/approvals',title:'治理审批'},{path:'/versions',title:'发布与回滚'}]},
 {key:'org',title:'租户体系',icon:'users',roles:['ADMIN'],items:[{path:'/tenants',title:'租户'},{path:'/projects',title:'项目'},{path:'/apps',title:'应用'}]},
 {key:'cost',title:'成本与预算',icon:'wallet',roles:['ADMIN'],items:[{path:'/usage',title:'用量分析'},{path:'/budget-rules',title:'预算管理'},{path:'/cost-statements',title:'内部成本单'},{path:'/provider-reconciliations',title:'供应商对账'}]},
 {key:'runtime',title:'路由与观测',icon:'route',roles:['ADMIN'],items:[{path:'/routes',title:'Fallback 与负载均衡'},{path:'/logs',title:'调用日志'},{path:'/provider-health',title:'渠道健康'},{path:'/alerts',title:'告警'}]},
 {key:'security',title:'审计与设置',icon:'shield',roles:['ADMIN'],items:[{path:'/audit',title:'操作审计'},{path:'/sensitive-access',title:'敏感查看'},{path:'/error-codes',title:'错误码'},{path:'/system-settings',title:'系统基础设置'}]},
 {key:'tenant',title:'租户工作台',icon:'users',items:[{path:'/workspace',title:'我的业务空间'}]},
 {key:'developer',title:'开发者中心',icon:'code',items:[{path:'/quick-start',title:'快速开始'},{path:'/developer-models',title:'可访问服务模型'},{path:'/playground',title:'Playground'}]}
]
export function visibleMenuGroups(roles:string[]){return menuGroups.filter(group=>!group.roles||group.roles.some(role=>roles.includes(role))).map(group=>({...group,items:group.items.filter(item=>!item.roles||item.roles.some(role=>roles.includes(role)))})).filter(group=>group.items.length)}
export function findMenuTitle(path:string){for(const group of menuGroups)for(const item of group.items)if(item.path===path)return item.title;return'页面未找到'}
