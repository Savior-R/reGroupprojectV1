import { expect, it } from 'vitest'
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios'
import { authClient, updateClass } from './auth'

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
