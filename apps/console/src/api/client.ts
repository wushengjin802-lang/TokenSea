import axios, { type AxiosRequestConfig } from 'axios'

export const api = axios.create({ baseURL: import.meta.env.VITE_API_BASE || 'http://localhost:39211', timeout: 20000 })

function resolveBrowserServiceBase(configured: string) {
  const normalized = configured.replace(/\/+$/, '')
  if (typeof window === 'undefined') return normalized
  try {
    const url = new URL(normalized)
    const localHosts = new Set(['localhost', '127.0.0.1', '::1'])
    if (localHosts.has(url.hostname) && !localHosts.has(window.location.hostname)) {
      url.hostname = window.location.hostname
    }
    return url.toString().replace(/\/+$/, '')
  } catch {
    return normalized
  }
}

export const gatewayBase = resolveBrowserServiceBase(import.meta.env.VITE_GATEWAY_BASE || 'http://localhost:39212')

export type SessionIdentity = { userId?: string; username?: string; roles: string[]; tenantIds: string[] }
export type PageQuery = { page?: number; size?: number; keyword?: string; status?: string; scope?: string; productionStatus?: string; sort?: string; order?: 'asc' | 'desc' }
export type PageResult<T> = { items: T[]; total: number; serverPaged: boolean }

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('tokensea_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
api.interceptors.response.use((response) => response, (error) => {
  const status = error?.response?.status
  // 权限升级或重新部署后，旧令牌可能仍在浏览器中。无角色令牌收到
  // 403 时必须重新登录，以便获取最新的角色声明；有角色的租户越权
  // 仍保留 403，不强制清除会话。
  if (status === 401 || (status === 403 && identity().roles.length === 0)) {
    localStorage.removeItem('tokensea_token')
    if (location.pathname !== '/' && location.pathname !== '/login') location.assign('/login')
  }
  return Promise.reject(error)
})

export function errorMessage(error: unknown, location = '当前操作'): string {
  const value = error as any
  const responseData = value?.response?.data
  const detail = responseData?.data ?? responseData
  if (detail?.location || detail?.problem || detail?.action) {
    const parts = [
      detail.location ? `异常位置：${detail.location}` : '',
      detail.problem ? `异常原因：${detail.problem}` : '',
      detail.action ? `处理方式：${detail.action}` : '',
    ].filter(Boolean)
    return parts.join('；')
  }
  if (value?.response?.status === 403) {
    if (!identity().roles.includes('ADMIN')) return '异常位置：权限校验；异常原因：当前登录会话未包含平台管理员权限；处理方式：退出后重新登录'
    return detail?.message || detail?.detail || '异常位置：权限校验；异常原因：当前操作未获授权；处理方式：确认平台管理员角色和数据范围后重新登录'
  }
  const message = detail?.message || detail?.detail || detail?.error || value?.message
  if (value?.response?.status === 409 && ['Conflict', 'Request failed with status code 409'].includes(String(message))) {
    return `异常位置：${location}；异常原因：当前请求不满足业务状态条件，控制面未返回具体说明；处理方式：检查当前记录状态和必填配置后重新操作`
  }
  if (message) return message
  return '异常位置：当前操作；异常原因：服务未返回具体错误；处理方式：刷新页面后重试，并检查控制面运行日志'
}
function camelKey(key:string){return key.replace(/_([a-zA-Z0-9])/g,(_,letter:string)=>letter.toUpperCase())}
export function normalizePayload<T=any>(value:any):T {
  if(Array.isArray(value))return value.map(item=>normalizePayload(item)) as T
  if(value&&typeof value==='object'&&Object.getPrototypeOf(value)===Object.prototype){
    return Object.fromEntries(Object.entries(value).map(([key,item])=>[camelKey(key),normalizePayload(item)])) as T
  }
  return value as T
}
function unwrap<T>(payload: any): T {
  if (payload?.success === false) throw new Error(payload.message || '操作失败')
  return normalizePayload<T>(payload?.data ?? payload)
}
export function identity(): SessionIdentity {
  const token = localStorage.getItem('tokensea_token')
  if (!token) return { roles: [], tenantIds: [] }
  try {
    const raw = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const claims = JSON.parse(decodeURIComponent(Array.from(atob(raw), c => `%${c.charCodeAt(0).toString(16).padStart(2, '0')}`).join('')))
    return { userId: claims.sub, username: claims.username, roles: claims.roles || [], tenantIds: claims.tenant_ids || [] }
  } catch { return { roles: [], tenantIds: [] } }
}
export const isAdmin = () => identity().roles.includes('ADMIN')

export async function queryPage<T = any>(path: string, query: PageQuery = {}): Promise<PageResult<T>> {
  const response = await api.get(path, { params: query })
  const data: any = unwrap(response.data)
  if (Array.isArray(data)) return { items: data, total: data.length, serverPaged: false }
  const items = data?.items || data?.content || data?.records || []
  return { items, total: Number(data?.total ?? data?.totalElements ?? items.length), serverPaged: true }
}
export async function list<T = any>(path: string, config?: AxiosRequestConfig): Promise<T[]> {
  const response = await api.get(path, config)
  return unwrap<T[]>(response.data) || []
}
export async function get<T = any>(path: string): Promise<T> { return unwrap<T>((await api.get(path)).data) }
export async function create<T = any>(path: string, payload: any): Promise<T> { return unwrap<T>((await api.post(path, payload)).data) }
export async function update<T = any>(path: string, id: string, payload: any, method: 'put'|'patch' = 'put'): Promise<T> {
  return unwrap<T>((await api.request({ method, url: `${path}/${id}`, data: payload })).data)
}
export async function postAction<T = any>(path: string, payload: any = {}): Promise<T> { return unwrap<T>((await api.post(path, payload)).data) }
export async function patchAction<T = any>(path: string, payload: any = {}): Promise<T> { return unwrap<T>((await api.patch(path, payload)).data) }
export async function download(path: string, filename: string, params?: Record<string, any>) {
  const response = await api.get(path, { params, responseType: 'blob' })
  const url = URL.createObjectURL(response.data)
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = filename; anchor.click()
  URL.revokeObjectURL(url)
}
