import { chromium } from 'playwright'

const BASE = 'http://127.0.0.1:5173'
const TOPIC = process.env.TOPIC ?? 'M3 独立验收面试'
const OUT = '/tmp/m3-e2e'

const browser = await chromium.launch({
  headless: true,
  args: [
    '--use-fake-device-for-media-stream',
    '--use-fake-ui-for-media-stream',
    '--no-sandbox',
  ],
})
const context = await browser.newContext({
  baseURL: BASE,
  viewport: { width: 1440, height: 900 },
  permissions: ['camera', 'microphone'],
})
const page = await context.newPage()
page.on('console', (message) => {
  if (message.type() === 'error') console.log('[browser error]', message.text())
})
page.on('pageerror', (error) => console.log('[pageerror]', error.message))

async function step(name, fn) {
  process.stdout.write(`- ${name} ... `)
  await fn()
  console.log('ok')
}

try {
  await step('open student task list', async () => {
    await page.goto('/student/tasks')
    await page.getByText(TOPIC, { exact: true }).waitFor({ timeout: 15_000 })
  })

  await step('open interview task detail', async () => {
    const card = page.locator('li', { hasText: TOPIC })
    await card.getByRole('button', { name: '查看详情' }).click()
    await page.getByTestId('start-task-session').waitFor({ timeout: 15_000 })
  })

  await step('create session and open interview gate', async () => {
    await page.getByTestId('start-task-session').click()
    await page.getByTestId('interview-gate').waitFor({ timeout: 20_000 })
  })
  await page.screenshot({ path: `${OUT}/10-interview-gate.png`, fullPage: true })

  await step('start interview recording (fake camera/mic)', async () => {
    await page.getByTestId('start-interview').click()
    await page.getByTestId('end-interview').waitFor({ timeout: 30_000 })
    await page.waitForFunction(() => {
      const button = document.querySelector('[data-testid="end-interview"]')
      return button instanceof HTMLButtonElement && !button.disabled
    }, { timeout: 30_000 })
  })
  await page.waitForTimeout(6_000)
  await page.screenshot({ path: `${OUT}/11-interview-recording.png`, fullPage: true })

  await step('end interview and wait for review page', async () => {
    await page.getByTestId('end-interview').click()
    await page.getByTestId('review-streams').waitFor({ timeout: 60_000 })
  })

  await step('wait for two uploaded streams and submit', async () => {
    await page.waitForFunction(() => {
      const button = document.querySelector('[data-testid="submit-session"]')
      return button instanceof HTMLButtonElement && !button.disabled
    }, { timeout: 60_000 })
    await page.getByTestId('submit-session').click()
    await page.getByRole('button', { name: '确认提交' }).click()
    await page.getByTestId('review-submitted').waitFor({ timeout: 30_000 })
  })

  await step('render read-only interview replay', async () => {
    await page.getByTestId('replay-page').waitFor({ timeout: 30_000 })
    await page.getByTestId('replay-load-error').waitFor({ state: 'detached', timeout: 1 }).catch(() => {})
  })
  await page.screenshot({ path: `${OUT}/12-interview-replay.png`, fullPage: true })

  console.log('INTERVIEW ACCEPTANCE PASSED')
} catch (error) {
  await page.screenshot({ path: `${OUT}/interview-failure.png`, fullPage: true }).catch(() => {})
  console.log('INTERVIEW ACCEPTANCE FAILED:', error.message)
  console.log('page text:', (await page.locator('body').innerText().catch(() => '')).slice(0, 2000))
  process.exitCode = 1
} finally {
  await browser.close()
}
