import { expect, test } from '@playwright/test'
import { loadState, loginAs } from '../helpers/seed'

/**
 * M1-T10（10 §6.1）：学生 / 教师访问管理员路由 403。
 *
 * 前端守卫：无 admin 角色 → /403（ForbiddenPage）；
 * 后端 RBAC：携带非管理员 token 访问 /api/v1/admin/** → 403 FORBIDDEN。
 */
test.describe('M1-T10 管理端越权 403', () => {
  for (const role of ['student', 'teacher'] as const) {
    test(`${role} 访问管理路由被重定向到 /403`, async ({ page }) => {
      const state = loadState()
      await loginAs(page, state[role])
      await page.goto('/admin/org')
      await expect(page).toHaveURL(/\/403$/)
      await page.goto('/admin/teachers')
      await expect(page).toHaveURL(/\/403$/)
    })
  }

  test('后端 RBAC：学生 / 教师 token 访问 /api/v1/admin/** 返回 403', async ({ request }) => {
    const state = loadState()
    for (const role of ['student', 'teacher'] as const) {
      const response = await request.get(`${state.api}/api/v1/admin/majors`, {
        headers: { Authorization: `Bearer ${state[role].token}` },
      })
      expect(response.status()).toBe(403)
      const body = (await response.json()) as { code?: string }
      expect(body.code ?? 'FORBIDDEN').toBe('FORBIDDEN')
    }
  })
})
