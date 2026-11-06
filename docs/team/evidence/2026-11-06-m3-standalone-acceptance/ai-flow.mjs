import { chromium } from 'playwright'

const BASE = 'http://127.0.0.1:5173'
const TOPIC = process.env.TOPIC ?? 'M3 全链路验收'
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
const perceptionMessages = []
page.on('websocket', (socket) => {
  if (socket.url().includes('/perception')) {
    console.log('[perception ws]', socket.url())
    socket.on('framesent', (frame) => {
      if (typeof frame.payload === 'string') perceptionMessages.push(frame.payload)
    })
    socket.on('framereceived', (frame) => {
      if (typeof frame.payload === 'string') perceptionMessages.push(frame.payload)
    })
  }
})
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
  await step('open task and start recording', async () => {
    await page.goto('/student/tasks')
    await page.getByText(TOPIC, { exact: true }).waitFor({ timeout: 15_000 })
    const card = page.locator('li', { hasText: TOPIC })
    await card.getByRole('button', { name: '查看详情' }).click()
    await page.getByTestId('start-task-session').click()
    await page.getByTestId('classroom-gate').waitFor({ timeout: 20_000 })
    await page.getByTestId('start-recording').click()
    await page.waitForFunction(() => {
      const button = document.querySelector('[data-testid="end-session"]')
      return button instanceof HTMLButtonElement && !button.disabled
    }, { timeout: 30_000 })
  })

  await step('ask class and wait for a raised hand', async () => {
    await page.getByTestId('ask-class').click()
    await page.waitForFunction(() => {
      const button = document.querySelector('[data-testid="ask-class"]')
      return button instanceof HTMLButtonElement && button.disabled
    }, { timeout: 5_000 })
    await page.locator('.virtual-student-card--raised').first().waitFor({ timeout: 15_000 })
    const raised = page.locator('.virtual-student-card--raised').first()
    await raised.click()
  })
  await page.screenshot({ path: `${OUT}/20-waiting-hands.png`, fullPage: true })

  await step('call on student and receive AI answer in dialogue panel', async () => {
    await page.waitForFunction(() => {
      const button = document.querySelector('[data-testid="call-on"]')
      return button instanceof HTMLButtonElement && !button.disabled
    }, { timeout: 5_000 })
    await page.getByTestId('call-on').click()
    await page.getByTestId('dialogue-panel').getByText('这是模拟的学生回答。').waitFor({
      timeout: 15_000,
    })
  })
  await page.screenshot({ path: `${OUT}/21-dialogue-answer.png`, fullPage: true })

  await page.waitForTimeout(6_000)
  await step('end session and wait for review', async () => {
    await page.getByTestId('end-session').click()
    await page.getByTestId('review-streams').waitFor({ timeout: 60_000 })
  })
  await step('submit recording', async () => {
    await page.waitForFunction(() => {
      const button = document.querySelector('[data-testid="submit-session"]')
      return button instanceof HTMLButtonElement && !button.disabled
    }, { timeout: 60_000 })
    await page.getByTestId('submit-session').click()
    await page.getByRole('button', { name: '确认提交' }).click()
    await page.getByTestId('review-submitted').waitFor({ timeout: 30_000 })
  })
  await page.getByTestId('replay-page').waitFor({ timeout: 30_000 })
  await page.screenshot({ path: `${OUT}/22-ai-replay.png`, fullPage: true })

  console.log('perception messages:', JSON.stringify(perceptionMessages.slice(0, 10)))
  console.log('AI FLOW ACCEPTANCE PASSED')
} catch (error) {
  await page.screenshot({ path: `${OUT}/ai-flow-failure.png`, fullPage: true }).catch(() => {})
  console.log('AI FLOW ACCEPTANCE FAILED:', error.message)
  console.log('page text:', (await page.locator('body').innerText().catch(() => '')).slice(0, 3000))
  process.exitCode = 1
} finally {
  await browser.close()
}
