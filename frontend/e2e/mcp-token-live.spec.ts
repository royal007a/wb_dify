import { expect, test } from '@playwright/test'

// Opt-in deployment smoke: only a fresh disabled record and non-secret synthetic tokens.
test.use({ trace: 'off' })
test('live direct Token saves, stays write-only, preserves, replaces and clears', async ({ page, baseURL }) => {
  test.skip(process.env.MCP_TOKEN_LIVE !== '1', 'Requires explicitly enabled deployment smoke')
  const api = new URL('./api/v1/mcp-servers', baseURL).toString()
  const name = 'token-smoke-' + Date.now()
  let id: string | undefined
  try {
    await page.goto('./mcp')
    await page.getByRole('button', { name: '添加 Server' }).click()
    const create = page.getByRole('dialog', { name: '添加 MCP Server' })
    await create.getByLabel('名称', { exact: true }).fill(name)
    await create.getByLabel('Endpoint').fill('https://example.com/mcp')
    await create.locator('.el-switch').click() // never make outbound requests
    await expect(create.getByRole('switch')).not.toBeChecked()
    await create.locator('.el-select__wrapper').click()
    await page.getByRole('option', { name: '直接输入 Token', exact: true }).click()
    await create.getByLabel('Token', { exact: true }).fill('fake-deployment-token-one')
    const createdPromise = page.waitForResponse(response => response.url() === api && response.request().method() === 'POST')
    await create.getByRole('button', { name: '保存', exact: true }).click()
    const created = await createdPromise
    expect(created.status()).toBe(201)
    id = (await created.json()).data
    await expect(create).not.toBeVisible()
    const read = async () => (await (await page.request.get(api + '/' + id)).json()).data
    expect(await read()).toMatchObject({ credentialMode: 'TOKEN', credentialConfigured: true, credentialRef: null })
    expect(JSON.stringify(await read())).not.toContain('fake-deployment-token')
    await page.reload()
    const edit = async () => {
      await page.getByRole('row').filter({ hasText: name }).getByRole('button', { name: '编辑', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: '编辑 MCP Server' })
      await expect(dialog).toBeVisible()
      return dialog
    }
    let dialog = await edit()
    await expect(dialog.getByText('已配置 Token（加密保存，不回显）')).toBeVisible()
    await dialog.getByRole('button', { name: '保存', exact: true }).click() // KEEP
    await expect(dialog).not.toBeVisible()
    expect(await read()).toMatchObject({ credentialMode: 'TOKEN', credentialConfigured: true })
    dialog = await edit()
    await dialog.locator('.el-select__wrapper').click()
    await page.getByRole('option', { name: '直接输入 Token', exact: true }).click()
    await expect(dialog.getByLabel('Token', { exact: true })).toHaveValue('')
    await dialog.getByLabel('Token', { exact: true }).fill('fake-deployment-token-two')
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(dialog).not.toBeVisible()
    expect(await read()).toMatchObject({ credentialMode: 'TOKEN', credentialConfigured: true })
    dialog = await edit()
    if (process.env.MCP_TOKEN_SCREENSHOT) {
      await dialog.locator('.el-select__wrapper').click()
      await page.getByRole('option', { name: '直接输入 Token', exact: true }).click()
      await expect(dialog.getByLabel('Token', { exact: true })).toHaveValue('')
      await page.screenshot({ path: process.env.MCP_TOKEN_SCREENSHOT, fullPage: true })
    }
    await dialog.locator('.el-select__wrapper').click()
    await page.getByRole('option', { name: '清除鉴权', exact: true }).click()
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(dialog).not.toBeVisible()
    expect(await read()).toMatchObject({ credentialMode: 'NONE', credentialConfigured: false, credentialRef: null })
  } finally {
    if (id) expect((await page.request.delete(api + '/' + id)).ok()).toBeTruthy()
  }
})
