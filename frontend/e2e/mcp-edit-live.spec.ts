import { expect, test } from '@playwright/test'

// Explicit deployment smoke. Never edit existing configurations or capture credentials in traces.
test.use({ trace: 'off' })
test('live MCP edit persists through the API and reload', async ({ page, baseURL }) => {
  test.skip(process.env.MCP_EDIT_LIVE !== '1', 'Requires explicitly enabled live deployment smoke')
  const api = new URL('./api/v1/mcp-servers', baseURL).toString()
  const name = 'edit-smoke-' + Date.now()
  const payload = { name, endpointUrl: 'https://example.com/mcp', credentialRef: '', enabled: false }
  const created = await page.request.post(api, { data: payload })
  expect(created.status()).toBe(201)
  const id = (await created.json()).data as string
  try {
    await page.goto('./mcp')
    await page.getByRole('row').filter({ hasText: name }).getByRole('button', { name: '编辑', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '编辑 MCP Server' })
    await expect(dialog.getByLabel('名称', { exact: true })).toHaveValue(name)
    await dialog.getByLabel('名称', { exact: true }).fill(name + '-updated')
    await dialog.getByLabel('Endpoint').fill('https://example.com/updated-mcp')
    await dialog.getByLabel('凭证引用').fill('env:MCP_EDIT_SMOKE_TOKEN')
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(dialog).not.toBeVisible()
    await page.reload()
    await expect(page.getByRole('row').filter({ hasText: name + '-updated' })).toBeVisible()
    const saved = (await (await page.request.get(api + '/' + id)).json()).data
    expect(saved).toMatchObject({ id, name: name + '-updated', endpointUrl: 'https://example.com/updated-mcp', credentialRef: 'env:MCP_EDIT_SMOKE_TOKEN', enabled: false })
    const invalid = await page.request.put(api + '/' + id, { data: { ...payload, credentialRef: 'raw-test-value-not-a-secret' } })
    expect(invalid.status()).toBe(400)
    // Screenshot only the table, never an existing credential input.
    if (process.env.MCP_EDIT_SCREENSHOT) await page.screenshot({ path: process.env.MCP_EDIT_SCREENSHOT, fullPage: true })
  } finally {
    const archived = await page.request.delete(api + '/' + id)
    expect(archived.ok()).toBeTruthy()
  }
})
