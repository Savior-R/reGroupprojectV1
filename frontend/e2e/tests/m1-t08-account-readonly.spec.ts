import { expect, test } from '@playwright/test'
import { loadState, loginAs } from '../helpers/seed'

/**
 * M1-T08（10 §6.1）：账号抽屉只读；无修改资料 / 改密码接口。
 *
 * 当前实现（03 §3.9）：AppShell 头部展示用户名与「账号安全」入口；
 * 账号资料只读（/auth/me 仅 GET），无资料修改 / 密码修改表单与接口。
 */
test.describe('M1-T08 账号信息只读', () => {
  test('头部展示账号信息且无资料/密码修改入口', async ({ page }) => {
    const state = loadState()
    await loginAs(page, state.teacher)
    await page.goto('/')

    // 头部展示当前用户名（只读展示，非编辑框）
    await expect(page.locator('.app-shell__name')).toHaveText(state.teacher.username)
    // 提供「账号安全」入口
    await expect(page.locator('.app-shell__account-link')).toHaveText('账号安全')

    // 账号安全页：绑定 / 解绑能力存在，但不提供「修改密码」「修改资料」表单
    await page.locator('.app-shell__account-link').click()
    await expect(page).toHaveURL(/\/settings\/security$/)
    await expect(page.getByText('修改密码')).toHaveCount(0)
    await expect(page.getByText('修改资料')).toHaveCount(0)
    await expect(page.getByText('修改账号类型')).toHaveCount(0)
  })

  test('后端不提供资料修改 / 密码修改接口（只读契约）', async ({ request }) => {
    const state = loadState()
    const headers = { Authorization: `Bearer ${state.teacher.token}` }
    // /auth/me 只允许 GET；写方法一律 404（路由不存在）或 405
    const put = await request.put(`${state.api}/api/v1/auth/me`, { headers, data: { username: 'hack' } })
    const patch = await request.patch(`${state.api}/api/v1/auth/me`, { headers, data: { password: 'NewPassw0rd!' } })
    for (const response of [put, patch]) {
      expect([404, 405]).toContain(response.status())
    }
  })
})
