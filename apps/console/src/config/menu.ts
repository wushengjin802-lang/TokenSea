
export type MenuItem = { path: string; title: string; implemented?: boolean; icon?: string; description?: string }
export type MenuGroup = { key: string; title: string; icon: string; items: MenuItem[] }
export const menuGroups: MenuGroup[] = [
  { key: 'model', title: '模型', icon: 'box', items: [
    { path: '/providers', title: '供应商管理', implemented: true },
    { path: '/models', title: '模型目录', implemented: true },
    { path: '/deployments', title: '模型部署' },
    { path: '/pricing', title: '模型价格', implemented: true },
    { path: '/capabilities', title: '能力标签' },
  ]},
  { key: 'access', title: '访问', icon: 'key', items: [
    { path: '/keys', title: 'Key 列表', implemented: true },
    { path: '/key-wizard', title: 'Key 创建向导', implemented: true },
    { path: '/key-approval', title: 'Key 申请审批' },
    { path: '/key-rotation', title: 'Key 轮换' },
    { path: '/key-risk', title: '泄露与异常' },
  ]},
  { key: 'tenant', title: '租户', icon: 'users', items: [
    { path: '/tenants', title: '租户管理', implemented: true },
    { path: '/departments', title: '组织 / 部门' },
    { path: '/projects', title: '项目 / 应用', implemented: true },
    { path: '/members', title: '成员与角色' },
  ]},
  { key: 'billing', title: '计费', icon: 'wallet', items: [
    { path: '/usage', title: '用量统计', implemented: true },
    { path: '/budgets', title: '预算管理', implemented: true },
    { path: '/packages', title: '套餐与余额' },
    { path: '/billing', title: '账单管理', implemented: true },
    { path: '/reconciliation', title: '对账导出' },
  ]},
  { key: 'route', title: '路由', icon: 'route', items: [
    { path: '/routes', title: '路由策略', implemented: true },
    { path: '/aliases', title: '模型别名' },
    { path: '/fallbacks', title: 'Fallback 策略' },
    { path: '/load-balance', title: '负载均衡组' },
  ]},
  { key: 'observe', title: '观测', icon: 'box', items: [
    { path: '/logs', title: '调用日志', implemented: true },
    { path: '/monitoring', title: '指标监控', implemented: true },
    { path: '/provider-health', title: '供应商健康' },
    { path: '/alerts', title: '告警事件' },
  ]},
  { key: 'security', title: '安全', icon: 'shield', items: [
    { path: '/audit', title: '操作审计', implemented: true },
    { path: '/risk-rules', title: '风控规则' },
    { path: '/content-safety', title: '内容安全' },
    { path: '/log-masking', title: '日志脱敏' },
  ]},
  { key: 'developer', title: '开发', icon: 'code', items: [
    { path: '/api-docs', title: 'API 文档', implemented: true },
    { path: '/sdk', title: 'SDK 示例', implemented: true },
    { path: '/playground', title: 'Playground', implemented: true },
    { path: '/access-guide', title: '接入指南', implemented: true },
  ]},
  { key: 'system', title: '系统', icon: 'box', items: [
    { path: '/iam', title: '用户权限' },
    { path: '/sso', title: '登录集成' },
    { path: '/secrets', title: '密钥托管' },
    { path: '/deployment', title: '部署配置' },
  ]},
]
export function findMenuTitle(path: string) {
  for (const group of menuGroups) for (const item of group.items) if (item.path === path) return item.title
  return '功能入口'
}
