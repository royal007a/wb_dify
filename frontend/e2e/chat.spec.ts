import { expect, test } from '@playwright/test'

test('published Agent completes a streamed calculator run', async ({ page }) => {
  await page.goto('./chat')

  await expect(page.getByRole('heading', { name: '对话' })).toBeVisible()
  await expect(page.locator('.chat-toolbar .el-select')).not.toHaveClass(/is-disabled/)

  const conversationResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && response.url().endsWith('/api/v1/conversations'))
  const runResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && /\/api\/v1\/conversations\/[^/]+\/runs$/.test(response.url()))

  await page.getByPlaceholder('输入消息；例如：计算 17 * 23').fill('计算 17 * 23')
  await page.getByRole('button', { name: '运行' }).click()

  const conversation = await (await conversationResponse).json()
  expect(conversation.agentVersionId).toBeTruthy()
  const run = await (await runResponse).json()
  expect(run.agentVersionId).toBe(conversation.agentVersionId)
  expect(run.streamUrl).toContain(`/api/v1/runs/${run.id}/events/stream`)

  await expect(page.getByText('调用工具：calculator')).toBeVisible()
  await expect(page.locator('.message.assistant').last()).toContainText('391')
  await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED')
  await expect(page.locator('.composer-actions')).toContainText('固定版本')
  if (process.env.E2E_SCREENSHOT) {
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: process.env.E2E_SCREENSHOT, fullPage: true })
  }
})
