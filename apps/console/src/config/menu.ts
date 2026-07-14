export type MenuItem = { path:string;title:string;roles?:string[] }
export type MenuGroup = { key:string;title:string;icon:string;roles?:string[];items:MenuItem[] }
export const menuGroups:MenuGroup[]=[
 {key:'assets',title:'模型中心',icon:'box',roles:['ADMIN'],items:[{path:'/provider-templates',title:'供应商模板'},{path:'/provider-channels',title:'供应商渠道'},{path:'/model-references',title:'公共模型参考库'},{path:'/public-price-references',title:'公共价格参考'},{path:'/model-deployments',title:'模型部署'},{path:'/service-models',title:'企业服务模型'},{path:'/capability-validations',title:'能力验证'},{path:'/provider-price-catalog',title:'供应商官方价格目录'},{path:'/price-versions',title:'模型生效价格'}]},
 {key:'sync',title:'同步中心',icon:'route',roles:['ADMIN'],items:[{path:'/data-sources',title:'模型数据源'},{path:'/sync-jobs',title:'模型同步任务'},{path:'/provider-price-sources',title:'价格源管理'},{path:'/provider-price-sync-runs',title:'价格同步任务'},{path:'/provider-price-snapshots',title:'价格原始快照'},{path:'/provider-price-diffs',title:'价格差异审核'},{path:'/model-discoveries',title:'模型发现'},{path:'/discovery-diffs',title:'模型差异审核'}]},
 {key:'access',title:'Key 中心',icon:'key',roles:['ADMIN'],items:[{path:'/keys',title:'Key 列表'},{path:'/approvals',title:'申请审批'},{path:'/versions',title:'发布记录'}]},
 {key:'org',title:'租户中心',icon:'users',roles:['ADMIN'],items:[{path:'/tenants',title:'租户管理'},{path:'/projects',title:'项目管理'},{path:'/apps',title:'应用管理'}]},
 {key:'cost',title:'成本与预算',icon:'wallet',roles:['ADMIN'],items:[{path:'/usage',title:'用量分析'},{path:'/budget-rules',title:'预算管理'},{path:'/cost-statements',title:'内部成本单'},{path:'/provider-reconciliations',title:'供应商对账'}]},
 {key:'routing',title:'路由中心',icon:'route',roles:['ADMIN'],items:[{path:'/routes',title:'路由策略'}]},
 {key:'observability',title:'观测中心',icon:'pulse',roles:['ADMIN'],items:[{path:'/logs',title:'调用日志'},{path:'/provider-health',title:'渠道健康'},{path:'/alerts',title:'告警事件'}]},
 {key:'security',title:'安全与审计',icon:'shield',roles:['ADMIN'],items:[{path:'/audit',title:'操作审计'},{path:'/sensitive-access',title:'敏感查看'},{path:'/error-codes',title:'错误码中心'}]},
 {key:'settings',title:'系统设置',icon:'settings',roles:['ADMIN'],items:[{path:'/system-settings',title:'系统基础设置'}]},
 {key:'tenant',title:'租户工作台',icon:'users',items:[{path:'/workspace',title:'我的业务空间'}]},
 {key:'developer',title:'开发者门户',icon:'code',items:[{path:'/quick-start',title:'快速开始'},{path:'/developer-models',title:'服务模型列表'},{path:'/playground',title:'Playground'}]}
]
export function visibleMenuGroups(roles:string[]){return menuGroups.filter(group=>!group.roles||group.roles.some(role=>roles.includes(role))).map(group=>({...group,items:group.items.filter(item=>!item.roles||item.roles.some(role=>roles.includes(role)))})).filter(group=>group.items.length)}
export function findMenuTitle(path:string){for(const group of menuGroups)for(const item of group.items)if(item.path===path)return item.title;return'页面未找到'}
