
import axios from 'axios'
import { message } from 'ant-design-vue'

export const api = axios.create({ baseURL: import.meta.env.VITE_API_BASE || 'http://localhost:39211' })
export const gatewayBase = import.meta.env.VITE_GATEWAY_BASE || 'http://localhost:39212'

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('tokensea_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
api.interceptors.response.use((resp) => resp, (err) => {
  const msg = err?.response?.data?.message || err?.response?.data?.detail?.message || err.message || '请求失败'
  message.error(msg)
  return Promise.reject(err)
})

export async function list(path: string) {
  const r = await api.get(path)
  return r.data.data || []
}
export async function get(path: string) {
  const r = await api.get(path)
  return r.data.data
}
export async function create(path: string, payload: any) {
  const r = await api.post(path, payload)
  return r.data.data
}
export async function update(path: string, id: string, payload: any) {
  const r = await api.put(`${path}/${id}`, payload)
  return r.data.data
}
export async function remove(path: string, id: string) {
  const r = await api.delete(`${path}/${id}`)
  return r.data.data
}
export async function postAction(path: string, payload: any = {}) {
  const r = await api.post(path, payload)
  return r.data.data
}
export async function patchAction(path: string, payload: any = {}) {
  const r = await api.patch(path, payload)
  return r.data.data
}
export function unavailable(name: string) {
  message.warning(`${name} 暂不可用，入口已保留`)
}
