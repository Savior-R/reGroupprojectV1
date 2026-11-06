import axios from 'axios'
import type { AxiosInstance, InternalAxiosRequestConfig } from 'axios'
import { ApiError } from '../types/api'
import type { ApiErrorBody } from '../types/api'
import { useAuthStore } from '../stores/auth'

/**
 * Shared Axios client (09 §8.2).
 *
 * - Base path `/api/v1` (dev proxy in vite.config.ts).
 * - Injects the dev identity headers from the auth store.
 * - Normalizes every failed request to `ApiError` carrying the backend
 *   `{ code, message, details?, trace_id }` body.
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