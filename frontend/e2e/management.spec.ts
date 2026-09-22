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
  await page.getByRole('button', { name: '版本 Diff' }).click()
  await expect(page.getByText('草稿 / 最新发布版本')).toBeVisible()
  await page.keyboard.press('Escape')
  await page.keyboard.press('Escape')
  await page.getByRole('menuitem', { name: 'MCP Servers' }).click()
  await expect(page.getByRole('heading', { name: 'MCP Servers' })).toBeVisible()
  await expect(page.getByText('订单查询')).toBeVisible()
  await expect(page.getByText('READY')).toBeVisible()
})
