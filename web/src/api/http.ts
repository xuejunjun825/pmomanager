/**
 * 请求层（TECHNICAL §6.3）
 * - baseURL 固定 /api，与生产 Nginx 同源前缀（CSRF Cookie 依赖同源）
 * - 错误一律归一为 ApiError：{status, code, message, fieldErrors}
 * - 401 交由注册的处理器（清空身份 + 跳登录），避免 http 层依赖 store（循环引用）
 */
import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig } from 'axios'
import type { ApiErrorBody, FieldError } from '@/types/api'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: FieldError[]
  readonly details: Record<string, unknown>
  readonly traceId?: string
  readonly url?: string

  constructor(init: {
    status: number
    code: string
    message: string
    fieldErrors?: FieldError[]
    details?: Record<string, unknown>
    traceId?: string
    url?: string
  }) {
    super(init.message)
    this.name = 'ApiError'
    this.status = init.status
    this.code = init.code
    this.fieldErrors = init.fieldErrors ?? []
    this.details = init.details ?? {}
    this.traceId = init.traceId
    this.url = init.url
  }

  /** 字段级错误（422 回填用） */
  fieldError(field: string): string | undefined {
    return this.fieldErrors.find((item) => item.field === field)?.message
  }

  get isNetwork(): boolean {
    return this.status === 0
  }
}

const NETWORK_MESSAGE = '网络连接失败，请检查网络后重试'
const TIMEOUT_MESSAGE = '请求超时，请稍后重试'

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  return typeof value === 'object' && value !== null && typeof (value as ApiErrorBody).code === 'string'
}

export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error

  if (axios.isAxiosError(error)) {
    const axiosError = error as AxiosError<unknown>
    const url = axiosError.config?.url
    if (axiosError.code === 'ECONNABORTED' || axiosError.code === 'ETIMEDOUT') {
      return new ApiError({ status: 0, code: 'TIMEOUT', message: TIMEOUT_MESSAGE, url })
    }
    const response = axiosError.response
    if (!response) {
      return new ApiError({ status: 0, code: 'NETWORK_ERROR', message: NETWORK_MESSAGE, url })
    }
    const body = response.data
    if (isApiErrorBody(body)) {
      return new ApiError({
        status: response.status,
        code: body.code,
        message: body.message || '请求失败',
        fieldErrors: body.fieldErrors ?? [],
        details: body.details ?? {},
        traceId: body.traceId,
        url,
      })
    }
    return new ApiError({
      status: response.status,
      code: `HTTP_${response.status}`,
      message: statusFallbackMessage(response.status),
      url,
    })
  }

  if (error instanceof Error) {
    return new ApiError({ status: 0, code: 'UNKNOWN', message: error.message || '未知错误' })
  }
  return new ApiError({ status: 0, code: 'UNKNOWN', message: '未知错误' })
}

function statusFallbackMessage(status: number): string {
  if (status === 401) return '登录已过期，请重新登录'
  if (status === 403) return '你没有执行该操作的权限'
  if (status === 404) return '数据不存在或已被移除'
  if (status === 409) return '当前状态不允许该操作'
  if (status === 422) return '提交的内容不完整或不合法'
  if (status === 503) return '服务暂不可用'
  if (status >= 500) return '服务异常，请稍后重试'
  return '请求失败'
}

export const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '/api',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  timeout: 15000,
  headers: { Accept: 'application/json' },
})

type UnauthorizedHandler = (error: ApiError) => void

let unauthorizedHandler: UnauthorizedHandler | null = null

/** main.ts 注册：清空身份并跳 /login?next= */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler
}

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const apiError = toApiError(error)
    if (apiError.status === 401 && unauthorizedHandler) {
      unauthorizedHandler(apiError)
    }
    return Promise.reject(apiError)
  },
)

/** 剥掉 AxiosResponse，页面只拿业务数据 */
export async function apiGet<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const response = await http.get<T>(url, config)
  return response.data
}

export async function apiPost<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const response = await http.post<T>(url, body, config)
  return response.data
}

export async function apiPut<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const response = await http.put<T>(url, body, config)
  return response.data
}

export async function apiPatch<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const response = await http.patch<T>(url, body, config)
  return response.data
}

export async function apiDelete<T = void>(url: string, config?: AxiosRequestConfig): Promise<T | void> {
  const response = await http.delete<T>(url, config)
  return response.data
}
