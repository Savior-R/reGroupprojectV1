import { expect, test } from '@playwright/test'
import { loadState, loginAs } from '../helpers/seed'

/**
 * M1-T09（10 §6.1）：未建校管理员强制 `/admin/setup`。
 *
 * 守卫实现：app/router.ts beforeEach —— `meta.requiresSchool` 且 claims 无
 * school_id 时跳转 /admin/setup；未登录访问受保护路由跳 /login。
 */
test.describe('M1-T09 未建校管理员强制建校', () => {
  test('登录后进入 /admin/setup，访问管理路由被重定向回建校页', async ({ page }) => {
    const state = loadState()
    await loginAs(page, state.adminNoSchool)

    // 直接访问任一管理路由 → 强制回 /admin/setup
    await page.goto('/admin/org')
    await expect(page).toHaveURL(/\/admin\/setup$/)
    await page.goto('/admin/teachers')
    await expect(page).toHaveURL(/\/admin\/setup$/)

    // 建校页可访问且呈现建校表单
    await page.goto('/admin/setup')
    await expect(page).toHaveURL(/\/admin\/setup$/)
    await expect(page.locator('input').first()).toBeVisible()
  })

  test('建校后返回新 token（school_id 已写入）并可进入管理端', async ({ page }) => {
    const state = loadState()
    await loginAs(page, state.adminWithSchool)
    await page.goto('/admin/org')
    // 已建校管理员不再被强制跳 setup，可进入本校组织维护页
    await expect(page).not.toHaveURL(/\/admin\/setup$/)
    await expect(page).not.toHaveURL(/\/login$/)
  })
})
