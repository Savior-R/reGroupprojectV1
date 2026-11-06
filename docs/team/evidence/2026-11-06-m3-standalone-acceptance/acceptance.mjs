import { chromium } from 'playwright'

const BASE = 'http://127.0.0.1:5173'
const TOPIC = process.env.TOPIC ?? 'M3 独立验收试讲'
const OUT = '/tmp/m3-e2e'

const browser = await chromium.launch({
  headless: true,
  args: [
    '--use-fake-device-for-media-stream',
    '--use-fake-ui-for-media-stream',
    '--auto-select-desktop-capture-source=Entire screen',
    '--auto-select-tab-capture-source-by-title=about:blank',
    '--enable-usermedia-screen-capturing',
    '--allow-http-screen-capture',
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
  await page.screenshot({ path: `${OUT}/01-task-list.png`, fullPage: true })

  await step('open task detail', async () => {
    const card = page.locator('li', { hasText: TOPIC })
    await card.getByRole('button', { name: '查看详情' }).click()
    await page.getByTestId('start-task-session').waitFor({ timeout: 15_000 })
  })
  await page.screenshot({ path: `${OUT}/02-task-detail.png`, fullPage: true })

  await step('create session and open classroom gate', async () => {
    await page.getByTestId('start-task-session').click()
    await page.getByTestId('classroom-gate').waitFor({ timeout: 20_000 })
  })
  await page.screenshot({ path: `${OUT}/03-classroom-gate.png`, fullPage: true })

  await step('start recording (fake camera/mic/screen)', async () => {
    await page.getByTestId('start-recording').click()
    await page.getByTestId('end-session').waitFor({ timeout: 30_000 })
    await page.waitForFunction(() => {
      const button = document.querySelector('[data-testid="end-session"]')
      return button instanceof HTMLButtonElement && !button.disabled
    }, { timeout: 30_000 })
  })
  await page.waitForTimeout(6_000)
  await page.screenshot({ path: `${OUT}/04-classroom-recording.png`, fullPage: true })

  await step('end session and wait for review page', async () => {
    await page.getByTestId('end-session').click()
    await page.getByTestId('review-streams').waitFor({ timeout: 60_000 })
  })
  await page.screenshot({ path: `${OUT}/05-review-uploading.png`, fullPage: true })

  await step('wait for three uploaded streams', async () => {
    await page.getByTestId('submit-session').waitFor({ timeout: 60_000 })
    await page.waitForFunction(() => {
      const button = document.querySelector('[data-testid="submit-session"]')
      return button instanceof HTMLButtonElement && !button.disabled
    }, { timeout: 60_000 })
  })
  await page.screenshot({ path: `${OUT}/06-review-ready.png`, fullPage: true })

  await step('submit recording', async () => {
    await page.getByTestId('submit-session').click()
    await page.getByRole('button', { name: '确认提交' }).click()
    await page.getByTestId('review-submitted').waitFor({ timeout: 30_000 })
  })
  await step('render read-only replay', async () => {
    await page.getByTestId('replay-page').waitFor({ timeout: 30_000 })
    await page.getByTestId('replay-load-error').waitFor({ state: 'detached', timeout: 1 }).catch(() => {})
  })
  await page.screenshot({ path: `${OUT}/07-submitted-replay.png`, fullPage: true })

  const taskCard = await page.evaluate(async (topic) => {
    const response = await fetch('/api/v1/student/tasks', {
      headers: {
        'X-Account-Id': '00000000-0000-0000-0000-000000000020',
        'X-School-Id': '00000000-0000-0000-0000-000000000001',
        'X-Account-Type': 'STUDENT',
      },
    })
    const body = await response.json()
    return body.items.find((item) => item.topic === topic)
  }, TOPIC)
  console.log('task after submit:', JSON.stringify(taskCard))
  if (!taskCard || taskCard.assignment_status !== 'submitted') {
    throw new Error('task assignment status is not submitted')
  }

  console.log('ACCEPTANCE PASSED')
} catch (error) {
  await page.screenshot({ path: `${OUT}/failure.png`, fullPage: true }).catch(() => {})
  console.log('ACCEPTANCE FAILED:', error.message)
  console.log('page text:', (await page.locator('body').innerText().catch(() => '')).slice(0, 2000))
  process.exitCode = 1
} finally {
  await browser.close()
}
