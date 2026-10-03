import { expect, test } from '@playwright/test'

test.use({ trace: 'off' })
test('Demo session calls the clock after greeting and on repeated time questions', async ({ page }) => {
  await page.goto('./chat')
  await expect(page.getByRole('heading', { name: '对话' })).toBeVisible()
  await expect(page.locator('.chat-toolbar .el-select')).not.toHaveClass(/is-disabled/)
  // The seeded deployment defaults to Demo; wait for its label instead of clicking the select container.
  await expect(page.locator('.chat-toolbar .el-select')).toContainText('Demo Agent · v')
  await expect(page.locator('.message.assistant').first()).toContainText('本地规则模拟')
  let conversationId: string | undefined
  for (const input of ['hi', '现在几点', '现在几点？', '现在几点?']) {
    const response = page.waitForResponse(r => r.request().method() === 'POST' && /\/api\/v1\/conversations\/[^/]+\/runs$/.test(r.url()))
    await page.getByPlaceholder('输入消息；例如：计算 17 * 23').fill(input)
    await page.getByRole('button', { name: '运行', exact: true }).click()
    const run = await (await response).json()
    if (conversationId) expect(run.conversationId).toBe(conversationId)
    conversationId = run.conversationId
    await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED')
    await expect(page.getByRole('button', { name: '运行', exact: true })).toBeVisible()
    if (input === 'hi') {
      await expect(page.locator('.message.assistant').last()).toContainText('未调用真实大模型')
      await expect(page.locator('.chat-toolbar p')).toContainText('0 tools')
    } else {
      await expect(page.locator('.chat-toolbar p')).toContainText('1 tools')
      await expect(page.getByText('调用工具：current_time', { exact: true }).last()).toBeVisible()
      await expect(page.locator('.message.assistant').last()).toContainText(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/)
      await expect(page.locator('.message.assistant').last()).not.toContainText('已收到')
    }
  }
  if (process.env.E2E_TIME_SCREENSHOT) await page.screenshot({ path: process.env.E2E_TIME_SCREENSHOT, fullPage: true })
})
