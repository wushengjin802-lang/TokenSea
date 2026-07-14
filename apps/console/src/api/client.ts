import axios, { type AxiosRequestConfig } from 'axios'

export const api = axios.create({ baseURL: import.meta.env.VITE_API_BASE || 'http://localhost:39211', timeout: 20000 })
export const gatewayBase = import.meta.env.VITE_GATEWAY_BASE || 'http://localhost:39212'

export type SessionIdentity = { userId?: string; username?: string; roles: string[]; tenantIds: string[] }
export type PageQuery = { page?: number; size?: number; keyword?: string; status?: string; sort?: string; order?: 'asc' | 'desc' }
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

export function errorMessage(error: unknown): string {
  const value = error as any
  if (value?.response?.status === 403) {
    if (!identity().roles.includes('ADMIN')) return '当前登录会话未包含平台管理员权限，请退出后重新登录'
    return value?.response?.data?.message || value?.response?.data?.detail || '当前账号无权访问该租户范围'
  }
  return value?.response?.data?.message || value?.response?.data?.detail || value?.message || '请求失败，请稍后重试'
}
function camelKey(key:string){return key.replace(/_([a-z])/g,(_,letter:string)=>letter.toUpperCase())}
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
