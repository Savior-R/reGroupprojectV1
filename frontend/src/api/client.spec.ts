import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError, CanceledError } from 'axios'
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios'
import { ApiError, apiClient } from './client'
import { useAuthStore } from '../stores/auth'

const SCHOOL_ID = '00000000-0000-0000-0000-000000000001'
const TEACHER_ID = '00000000-0000-0000-0000-000000000010'
const STUDENT_ID = '00000000-0000-0000-0000-000000000020'

function capturingAdapter(seen: InternalAxiosRequestConfig[]): AxiosAdapter {
  return async (config) => {
    seen.push(config)
    return {
      data: { ok: true },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }
}

/**
 * Mimic a real Axios adapter: non-2xx responses reject with an AxiosError that
 * carries the response body, exactly what the xhr/http adapters do.
 */
function errorAdapter(status: number, data: unknown): AxiosAdapter {
  return async (config) => {
    const response = {
      data,
      status,
      statusText: 'Error',
      headers: {},
      config,
    }
    throw new AxiosError(
      `Request failed with status code ${status}`,
      'ERR_BAD_REQUEST',
      config,
      undefined,
      response,
    )
  }
}

describe('apiClient', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('uses the /api/v1 base path', () => {
    expect(apiClient.defaults.baseURL).toBe('/api/v1')
  })

  it('injects dev identity headers', async () => {
    useAuthStore().setDevIdentity('teacher')
    const seen: InternalAxiosRequestConfig[] = []

    await apiClient.get('/sessions/session-1', { adapter: capturingAdapter(seen) })

    expect(seen).toHaveLength(1)
    expect(seen[0]!.headers.get('X-Account-Id')).toBe(TEACHER_ID)
    expect(seen[0]!.headers.get('X-School-Id')).toBe(SCHOOL_ID)
    expect(seen[0]!.headers.get('X-Account-Type')).toBe('TEACHER')
  })

  it('defaults to the student identity headers', async () => {
    const seen: InternalAxiosRequestConfig[] = []

    await apiClient.get('/sessions/session-1', { adapter: capturingAdapter(seen) })

    expect(seen[0]!.headers.get('X-Account-Id')).toBe(STUDENT_ID)
    expect(seen[0]!.headers.get('X-Account-Type')).toBe('STUDENT')
  })

  it('maps 422 to ApiError with fields', async () => {
    const body = {
      code: 'VALIDATION_ERROR',
      message: '字段校验失败',
      details: { fields: [{ field: 'task_id', message: '不能为空' }] },
      trace_id: 'trace-422',
    }
    const error = await apiClient
      .get('/sessions', { adapter: errorAdapter(422, body) })
      .catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.name).toBe('ApiError')
    expect(apiError.code).toBe('VALIDATION_ERROR')
    expect(apiError.message).toBe('字段校验失败')
    expect(apiError.details).toEqual(body.details)
    expect(apiError.trace_id).toBe('trace-422')
    expect(apiError.status).toBe(422)
  })

  it.each([401, 403, 409, 429, 503])(
    'maps %i responses to ApiError with the backend body',
    async (status) => {
      const body = {
        code: `ERR_${status}`,
        message: `status ${status}`,
        trace_id: `trace-${status}`,
      }
      const error = await apiClient
        .get('/sessions', { adapter: errorAdapter(status, body) })
        .catch((reason: unknown) => reason)

      expect(error).toBeInstanceOf(ApiError)
      const apiError = error as ApiError
      expect(apiError.code).toBe(body.code)
      expect(apiError.message).toBe(body.message)
      expect(apiError.trace_id).toBe(body.trace_id)
      expect(apiError.status).toBe(status)
    },
  )

  it('maps a cancelled request to ApiError with ABORTED', async () => {
    const adapter: AxiosAdapter = async () => {
      throw new CanceledError('请求已取消')
    }
    const error = await apiClient
      .get('/sessions', { adapter })
      .catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('ABORTED')
    expect((error as ApiError).status).toBe(0)
  })

  it('maps a network failure to ApiError', async () => {
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError('Network Error', 'ERR_NETWORK', config)
    }
    const error = await apiClient
      .get('/sessions', { adapter })
      .catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('NETWORK_ERROR')
  })
})