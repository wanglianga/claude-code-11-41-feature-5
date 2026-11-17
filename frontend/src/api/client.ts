import axios from 'axios'

const client = axios.create({ baseURL: '/api', timeout: 30000 })

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('scf-token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

client.interceptors.response.use(
  (resp) => resp,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('scf-token')
      if (location.pathname !== '/login') location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export interface ApiResp<T = any> {
  ok: boolean
  message: string | null
  data: T
}

export async function get<T = any>(url: string, params?: Record<string, any>): Promise<T> {
  const resp = await client.get<ApiResp<T>>(url, { params })
  if (!resp.data.ok) throw new Error(resp.data.message || '请求失败')
  return resp.data.data
}

export async function post<T = any>(url: string, body?: any): Promise<T> {
  const resp = await client.post<ApiResp<T>>(url, body)
  if (!resp.data.ok) throw new Error(resp.data.message || '请求失败')
  return resp.data.data
}

export default client
