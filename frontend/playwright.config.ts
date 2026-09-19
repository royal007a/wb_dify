import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 0,
  reporter: 'line',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:5173/',
    channel: process.env.PLAYWRIGHT_CHANNEL || 'chrome',
    ignoreHTTPSErrors: process.env.E2E_IGNORE_HTTPS_ERRORS === 'true',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
})
