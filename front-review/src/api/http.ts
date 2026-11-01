import axios, { type AxiosRequestConfig } from 'axios'
import type { Response } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { gotoMainLoginOnce } from '@/utils/mainSite'
import { toast } from '@/components/Toast'

// HTTP 客户端：与 front/src/api/http.ts 同款口径手抄（唯一事实源在后端 Response{code,info,data}）。
// 差异点：本工程无路由级 /login，401/403 一律回主站登录（客户端路由守卫的服务端镜像）。

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
      useAuthStore().clearAuth()
      toast('请先使用审核员/管理员账号在主站登录')
      gotoMainLoginOnce()
    } else if (err.response?.status === 403) {
      toast('无权限操作')
    } else {
      toast('网络异常，请稍后重试')
    }
    return Promise.reject(err)
  }
)

/** silent = 调用方自己处理失败（业务错误码已由专用交互处理时用它防双弹） */
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
