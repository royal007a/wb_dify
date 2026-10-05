import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/knowledge-bases**', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: { code: 200, message: 'success', data: [{ id:'kb-1', name:'产品手册', description:'canonical docs', chunkSize:600, chunkOverlap:80, enabled:true, documentCount:2, createdAt:'2026-09-21T00:00:00Z', updatedAt:'2026-09-21T00:00:00Z' }], total:1, page:1, size:10 } })
    return route.fulfill({ status: 201, json: { code:200,message:'success',data:'kb-new' } })
  })
  await page.route('**/api/v1/workflows**', route => {
    const workflow={id:'wf-1',name:'客服分流',description:'deterministic',schemaVersion:1,draftRevision:2,publishedVersionId:'wfv-1',createdAt:'2026-09-21T00:00:00Z',updatedAt:'2026-09-21T00:00:00Z',nodes:[{nodeKey:'start',type:'START',name:'开始',config:{}},{nodeKey:'end',type:'END',name:'结束',config:{output:'done'}}],edges:[{edgeKey:'e1',sourceNodeKey:'start',targetNodeKey:'end',defaultBranch:false}]}
    if(route.request().url().endsWith('/wf-1'))return route.fulfill({json:{code:200,message:'success',data:workflow}})
    if(route.request().url().endsWith('/wf-1/versions'))return route.fulfill({json:{code:200,message:'success',data:[{id:'wfv-1',workflowId:'wf-1',versionNo:1,schemaVersion:1,checksum:'abc',createdAt:'2026-09-21T00:00:00Z'}]}})
    return route.fulfill({ json: { code:200,message:'success',data:[workflow],total:1,page:1,size:10 } })
  })
  await page.route('**/api/v1/workflow-versions/wfv-1', route => route.fulfill({json:{code:200,message:'success',data:{id:'wfv-1',workflowId:'wf-1',versionNo:1,schemaVersion:1,checksum:'abc',createdAt:'2026-09-21T00:00:00Z',nodes:[{nodeKey:'start',type:'START',name:'开始',config:{}},{nodeKey:'end',type:'END',name:'结束',config:{output:'done'}}],edges:[{edgeKey:'e1',sourceNodeKey:'start',targetNodeKey:'end',defaultBranch:false}]}}}))
  await page.route('**/api/v1/mcp-servers**', route => route.fulfill({ json: { code:200,message:'success',data:[{id:'mcp-1',name:'订单查询',transport:'STREAMABLE_HTTP',endpointUrl:'https://mcp.example.com/mcp',enabled:true,serverRevision:3,schemaDigest:'1234567890abcdef',status:'READY',createdAt:'2026-09-21T00:00:00Z',updatedAt:'2026-09-21T00:00:00Z'}] } }))
})

test('advanced management consoles are routed and data-backed', async ({ page }) => {
  await page.goto('./knowledge')
  await expect(page.getByRole('heading', { name: '知识库' })).toBeVisible()
  await expect(page.getByText('产品手册')).toBeVisible()
  await page.getByRole('menuitem', { name: 'Workflow' }).click()
  await expect(page.getByRole('heading', { name: 'Workflow' })).toBeVisible()
  await expect(page.getByText('客服分流')).toBeVisible()
  await page.getByRole('button', { name: '画布' }).click()
  await expect(page.getByText('节点面板')).toBeVisible()
  await expect(page.getByText('属性面板')).toBeVisible()
  await expect(page.getByText('START', { exact: true }).first()).toBeVisible()
  await page.getByRole('button', { name: '＋ LLM', exact: true }).click()
  await expect(page.getByRole('textbox').last()).toHaveValue(/"maxOutputTokens": 1024/)
  await page.getByRole('button', { name: '＋ API_CALL', exact: true }).click()
  await expect(page.getByRole('textbox').last()).toHaveValue(/"method": "GET"/)
  await page.getByRole('button', { name: '＋ AGGREGATOR', exact: true }).click()
  await expect(page.getByRole('textbox').last()).toHaveValue(/"candidates":/)
  await expect(page.getByRole('textbox').last()).toHaveValue(/branch_a.answer/)
  await page.getByRole('button', { name: '版本 Diff' }).click()
  await expect(page.getByText('草稿 / 最新发布版本')).toBeVisible()
  await page.keyboard.press('Escape')
  await page.keyboard.press('Escape')
  await page.getByRole('menuitem', { name: 'MCP Servers' }).click()
  await expect(page.getByRole('heading', { name: 'MCP Servers' })).toBeVisible()
  await expect(page.getByText('订单查询')).toBeVisible()
  await expect(page.getByText('READY')).toBeVisible()
})

test('structured LLM schema example sends exact bounded schema without changing prompt (mock HTTP)', async ({ page }) => {
  let saved: { nodes: Array<{ type: string; config: Record<string, unknown> }> } | undefined
  await page.route('**/api/v1/workflows/wf-1', async route => {
    if (route.request().method() !== 'PUT') return route.fallback()
    saved = route.request().postDataJSON()
    return route.fulfill({ json: { code: 200, message: 'success', data: null } })
  })
  await page.goto('./workflows')
  await page.getByRole('button', { name: '画布', exact: true }).click()
  await page.getByRole('button', { name: '＋ LLM', exact: true }).click()
  const editor = page.locator('.props textarea')
  await editor.fill(JSON.stringify({ providerId: 'p', modelId: 'm', prompt: '{{start.userMessage}}', maxOutputTokens: 2048, outputVariable: 'answer' }))
  await page.getByTestId('structured-example').click()
  await expect(editor).toHaveValue(/"additionalProperties": false/)
  await page.getByRole('button', { name: '保存并校验', exact: true }).click()
  await expect.poll(() => saved?.nodes.find(n => n.type === 'LLM')?.config).toEqual({
    providerId: 'p', modelId: 'm', prompt: '{{start.userMessage}}', maxOutputTokens: 2048, outputVariable: 'answer',
    outputSchema: { type: 'object', properties: { summary: { type: 'string', maxLength: 1000 }, items: { type: 'array', maxItems: 5, items: { type: 'string', maxLength: 200 } } }, required: ['summary', 'items'], additionalProperties: false },
  })
})

test('aggregation config editor sends literal candidates in draft JSON (mock HTTP)', async ({ page }) => {
  let saved: { nodes: Array<{ type: string; config: Record<string, unknown> }> } | undefined
  await page.route('**/api/v1/workflows/wf-1', async route => {
    if (route.request().method() !== 'PUT') return route.fallback()
    saved = route.request().postDataJSON()
    return route.fulfill({ json: { code: 200, message: 'success', data: null } })
  })
  await page.goto('./workflows')
  await page.getByRole('button', { name: '画布', exact: true }).click()
  await page.getByRole('button', { name: '＋ AGGREGATOR', exact: true }).click()
  await expect(page.getByText('标量聚合：每条路径恰有一个候选')).toBeVisible()
  await page.getByRole('textbox').last().fill(JSON.stringify({ candidates: ['refund.answer', 'general.answer'], outputVariable: 'answer' }))
  await page.getByRole('button', { name: '保存并校验', exact: true }).click()
  await expect.poll(() => saved?.nodes.find(node => node.type === 'AGGREGATOR')?.config).toEqual({ candidates: ['refund.answer', 'general.answer'], outputVariable: 'answer' })
})

test('config editor preserves sequential invalid text and blocks save across node switches', async ({ page }) => {
  let writes = 0
  let saved: { nodes: Array<{ type: string; config: Record<string, unknown> }> } | undefined
  await page.route('**/api/v1/workflows/wf-1', async route => {
    if (route.request().method() !== 'PUT') return route.fallback()
    writes++
    saved = route.request().postDataJSON()
    return route.fulfill({ json: { code: 200, message: 'success', data: null } })
  })
  await page.goto('./workflows')
  await page.getByRole('button', { name: '画布', exact: true }).click()
  await page.getByRole('button', { name: '＋ AGGREGATOR', exact: true }).click()
  const editor = page.locator('.props textarea')
  await editor.fill('')
  await editor.pressSequentially('{"candidates": [', { delay: 15 })
  await expect(editor).toHaveValue('{"candidates": [')
  await expect(page.getByRole('button', { name: '保存并校验', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '发布', exact: true })).toBeDisabled()
  await page.locator('.surface .node').filter({ hasText: '开始' }).click()
  await expect(page.getByRole('button', { name: '保存并校验', exact: true })).toBeDisabled()
  await page.locator('.surface .node').filter({ hasText: 'AGGREGATOR' }).click()
  await expect(editor).toHaveValue('{"candidates": [')
  await editor.press('End')
  await editor.pressSequentially('"left.result", "right.result"], "outputVariable": "answer"}', { delay: 10 })
  await expect(editor).toHaveValue('{"candidates": ["left.result", "right.result"], "outputVariable": "answer"}')
  await expect(page.getByRole('button', { name: '保存并校验', exact: true })).toBeEnabled()
  expect(writes).toBe(0)
  await editor.fill('null')
  await expect(page.getByRole('button', { name: '发布', exact: true })).toBeDisabled()
  await editor.fill('[]')
  await expect(page.getByRole('button', { name: '保存并校验', exact: true })).toBeDisabled()
  await editor.fill('{"candidates": ["left.result", "right.result"], "outputVariable": "answer"}')
  await page.getByRole('button', { name: '保存并校验', exact: true }).click()
  await expect.poll(() => saved?.nodes.find(n => n.type === 'AGGREGATOR')?.config)
    .toEqual({ candidates: ['left.result', 'right.result'], outputVariable: 'answer' })
  expect(writes).toBe(1)
})
