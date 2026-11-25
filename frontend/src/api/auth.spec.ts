import { expect, it } from 'vitest'
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios'
import { authClient, listSecurityEvents, updateClass } from './auth'

/** 捕获 updateClass 发出的请求配置（沿用 client.spec.ts 的 adapter 模式）。 */
function capturingAdapter(seen: InternalAxiosRequestConfig[]): AxiosAdapter {
  return async (config) => {
    seen.push(config)
    return {
      data: { id: 'class-1', major_id: 'major-1', grade: '2024', name: '语文2401', active: true },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }
}

/** 验证 M1-F-02 新增的 updateClass 请求映射：前端 camelCase → 后端 snake_case。 */
it('updateClass maps majorId to major_id and PATCHes the class endpoint', async () => {
  const seen: InternalAxiosRequestConfig[] = []
  authClient.defaults.adapter = capturingAdapter(seen)

  await updateClass('class-1', {
    majorId: 'major-1',
    grade: '2024',
    name: '语文2401',
    active: true,
  })

  expect(seen).toHaveLength(1)
  expect(seen[0]!.method).toBe('patch')
  expect(seen[0]!.url).toBe('/admin/classes/class-1')
  expect(JSON.parse(seen[0]!.data as string)).toEqual({
    major_id: 'major-1',
    grade: '2024',
    name: '语文2401',
    active: true,
  })
})

it('updateClass omits undefined fields', async () => {
  const seen: InternalAxiosRequestConfig[] = []
  authClient.defaults.adapter = capturingAdapter(seen)

  await updateClass('class-1', { active: false })

  expect(JSON.parse(seen[0]!.data as string)).toEqual({ active: false })
})

/** 登录记录（03 §3.9）：GET /auth/security/events，limit 作为查询参数。 */
it('listSecurityEvents GETs the security events endpoint with limit', async () => {
  const seen: InternalAxiosRequestConfig[] = []
  const events = [
    {
      id: 'event-1',
      event_type: 'login',
      method: 'password',
      provider: null,
      success: true,
      ip: '127.0.0.1',
      user_agent: 'vitest',
      created_at: '2026-11-25T05:00:00Z',
    },
  ]
  authClient.defaults.adapter = async (config) => {
    seen.push(config)
    return { data: events, status: 200, statusText: 'OK', headers: {}, config }
  }

  const result = await listSecurityEvents(5)

  expect(seen).toHaveLength(1)
  expect(seen[0]!.method).toBe('get')
  expect(seen[0]!.url).toBe('/auth/security/events')
  expect(seen[0]!.params).toEqual({ limit: 5 })
  expect(result).toEqual(events)
})
