import axios from 'axios'
import type { AxiosInstance, InternalAxiosRequestConfig } from 'axios'
import { ApiError } from '../types/api'
import type { ApiErrorBody } from '../types/api'
import { useAuthStore } from '../stores/auth'
import { getToken } from './auth'
import { decodeJwt } from './jwt'

/**
 * Shared Axios client (09 §8.2).
 *
 * - Base path `/api/v1` (dev proxy in vite.config.ts).
 * - **已登录时注入 Bearer token 与真实身份头**（从 JWT 解出），M3 页面因此
 *   按当前登录账号取数；未登录时回退到 dev 身份，保留 M3 无需登录即可调试的用法。
 * - Normalizes every failed request to `ApiError` carrying the backend
 *   `{ code, message, details?, trace_id }` body.
 *
 * 说明：生产环境由 Nginx `auth_request` 注入 `X-Account-*`，前端的同名头会被覆盖；
 * Spring 侧仅在 dev / test profile 接受请求头身份（`TestHeaderAuthFilter`），
 * 因此这里带头不会被用来伪造身份。
 */

export const API_BASE_URL = '/api/v1'

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as { code?: unknown; message?: unknown }
  return typeof candidate.code === 'string' && typeof candidate.message === 'string'
}

function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }

  if (axios.isCancel(error)) {
    return new ApiError({
      code: 'ABORTED',
      message: error instanceof Error ? error.message : '请求已取消',
      status: 0,
    })
  }

  if (axios.isAxiosError(error)) {
    const response = error.response
    if (response) {
      const body = isApiErrorBody(response.data) ? response.data : undefined
      return new ApiError({
        code: body?.code ?? `HTTP_${response.status}`,
        message: body?.message ?? error.message,
        details: body?.details,
        trace_id: body?.trace_id,
        status: response.status,
      })
    }
    return new ApiError({
      code: 'NETWORK_ERROR',
      message: error.message || '网络请求失败',
    })
  }

  return new ApiError({
    code: 'UNKNOWN_ERROR',
    message: error instanceof Error ? error.message : '未知错误',
  })
}

export const apiClient: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
})

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  const claims = decodeJwt(token)

  // 已登录：Bearer + 从 JWT 解出的真实身份（M3 的 CurrentUserProvider 读这些头）
  if (token && claims) {
    config.headers.set('Authorization', `Bearer ${token}`)
    config.headers.set('X-Account-Id', claims.sub)
    config.headers.set('X-Account-Type', claims.account_type)
    if (claims.school_id) {
      config.headers.set('X-School-Id', claims.school_id)
    }
    return config
  }

  // 未登录：保留 dev 身份，M3 页面可脱离 M1 登录独立调试
  const auth = useAuthStore()
  config.headers.set('X-Account-Id', auth.accountId)
  config.headers.set('X-School-Id', auth.schoolId)
  config.headers.set('X-Account-Type', auth.accountType)
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => Promise.reject(toApiError(error)),
)

export { ApiError }
export type { ApiErrorBody }