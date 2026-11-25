import { defineConfig } from '@playwright/test'

/**
 * M1 E2E（M1-T08 / T09 / T10，docs/design/10 §6.1）。
 *
 * 前置条件（见 e2e/README.md）：
 * - 后端：隔离目录 bootRun，SERVER_PORT=8091，SPRING_PROFILES_ACTIVE=test，
 *   SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/shijiangtai_e2e
 * - 前端：`VITE_API_TARGET=http://localhost:8091 npm run dev`（5173）
 *
 * 覆盖变量允许 CI 覆盖：
 *   E2E_BASE_URL   前端地址（默认 http://localhost:5173）
 *   E2E_API_URL    后端地址（默认 http://localhost:8091）
 */
export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    headless: true,
    screenshot: 'only-on-failure',
  },
  globalSetup: './helpers/global-setup.ts',
})
