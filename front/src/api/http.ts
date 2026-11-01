import axios, { type AxiosRequestConfig } from 'axios'
import type { Response } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'
import { toast } from '@/components/Toast'

const instance = axios.create({
  baseURL: '/api',
  timeout: 10000
})

instance.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

instance.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      const auth = useAuthStore()
      const wasLoggedIn = !!auth.token
      auth.clearAuth()
      // 登录页自身不跳转，避免循环
      const current = router.currentRoute.value
      if (current.path !== '/login') {
        if (wasLoggedIn) toast('登录已过期，请重新登录')
        router.push({ path: '/login', query: { redirect: current.fullPath } })
      }
    } else if (err.response?.status === 403) {
      toast('无权限操作')
    } else {
      toast('网络异常，请稍后重试')
    }
    return Promise.reject(err)
  }
)

/** silent = 调用方自己处理失败（默认会对非 200 统一 toast，业务 code 已被专用交互处理时用它防双弹） */
async function unwrap<T>(promise: Promise<{ data: Response<T> }>, silent = false): Promise<T> {
  const res = await promise
  const body = res.data
  if (body.code !== '200') {
    if (!silent) toast(body.info || '请求失败')
    throw new ApiError(body.code, body.info)
  }
  return body.data
}

export class ApiError extends Error {
  constructor(
    public code: string,
    info: string
  ) {
    super(info)
  }
}

type HttpOpts = AxiosRequestConfig & { silent?: boolean }

export function httpGet<T>(url: string, params?: object, opts?: HttpOpts): Promise<T> {
  return unwrap(instance.get<Response<T>>(url, { params, ...opts }), opts?.silent)
}

export function httpPost<T>(url: string, data?: unknown, config?: HttpOpts): Promise<T> {
  return unwrap(instance.post<Response<T>>(url, data, config), config?.silent)
}

export function httpDelete<T>(url: string, data?: unknown): Promise<T> {
  return unwrap(instance.delete<Response<T>>(url, { data }))
}

export function httpPut<T>(url: string, data?: unknown): Promise<T> {
  return unwrap(instance.put<Response<T>>(url, data))
}
