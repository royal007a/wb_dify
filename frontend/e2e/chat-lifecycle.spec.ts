import { expect, test, type Page } from '@playwright/test'

// UI contract tests only: HTTP and EventSource are controlled, not a backend SSE acceptance test.
// Allow browser startup on shared developer machines; individual UI assertions retain the 10s limit.
test.setTimeout(120_000)
test.use({ channel: 'chromium-headless-shell', trace: 'off', viewport: { width: 1440, height: 900 } })
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    class ControlledSource extends EventTarget {
      closed = false
      onerror: ((event: Event) => void) | null = null
      constructor(public url: string) {
        super()
        ;(window as unknown as { __streams: ControlledSource[] }).__streams.push(this)
      }
      close() { this.closed = true }
    }
    ;(window as unknown as { __streams: ControlledSource[] }).__streams = []
    Object.defineProperty(window, 'EventSource', { value: ControlledSource, configurable: true })
  })
  await page.route('**/api/v1/agents**', route => route.fulfill({ json: {
    code: 200, message: 'success', data: [{ id: 'agent-1', name: '受控测试 Agent', enabled: true,
      publishedVersionId: 'version-1', publishedVersionNo: 1 }], total: 1, page: 1, size: 100,
  } }))
  await page.route('**/api/v1/mcp-servers', route => route.fulfill({ json: { code: 200, message: 'success', data: [] } }))
  await page.route('**/api/v1/conversations', route => route.fulfill({ status: 201,
    json: { id: 'conversation-1', agentId: 'agent-1', agentVersionId: 'version-1' } }))
  await page.route('**/api/v1/conversations/conversation-1/runs', route => route.fulfill({ status: 202, json: run('RUNNING') }))
  await page.route('**/api/v1/runs/run-1', route => route.fulfill({ json: run('COMPLETED', '完整的持久化答案') }))
})

function run(state: string, outputMessage = '') {
  return { id: 'run-1', conversationId: 'conversation-1', state, outputMessage, turns: 1, toolCalls: 0,
    agentVersionId: 'version-1', agentSnapshotDigest: 'digest', streamUrl: '/api/v1/runs/run-1/events/stream' }
}

async function begin(page: Page) {
  await page.goto('./chat')
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => page.evaluate(() => (window as unknown as { __streams: unknown[] }).__streams.length)).toBe(1)
}

async function emit(page: Page, kind: string, data: object, id = '') {
  await page.evaluate(({ kind, data, id }) => {
    const streams = (window as unknown as { __streams: EventTarget[] }).__streams
    streams[streams.length - 1].dispatchEvent(new MessageEvent(kind, { data: JSON.stringify(data), lastEventId: id }))
  }, { kind, data, id })
}

test('running conversation cannot be replaced and replayed deltas are not duplicated', async ({ page }) => {
  await begin(page)
  await expect(page.getByRole('button', { name: '新会话' })).toBeDisabled()
  await emit(page, 'message.delta', { content: '片段' }, '11')
  await emit(page, 'message.delta', { content: '片段' }, '11')
  await expect(page.locator('.message.assistant').last().locator('div')).toHaveText('片段')
  await emit(page, 'run.completed', {}, '12')
  await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED')
  await expect(page.getByRole('button', { name: '新会话' })).toBeEnabled()
})

test('terminal state replaces partial stream with canonical output', async ({ page }) => {
  await begin(page)
  await emit(page, 'message.delta', { content: '不完整' }, '1')
  await emit(page, 'run.completed', {}, '2')
  await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED')
  await expect(page.locator('.message.assistant').last()).toContainText('完整的持久化答案')
  await expect(page.locator('.message.assistant').last()).not.toContainText('不完整')
})

test('failed terminal reread can be explicitly retried without resubmitting message', async ({ page }) => {
  let unavailable = true, creations = 0
  await page.route('**/api/v1/runs/run-1', route => unavailable
    ? route.fulfill({ status: 503, json: { message: '暂时无法同步' } })
    : route.fulfill({ json: run('COMPLETED', '同步成功') }))
  page.on('request', req => { if (req.method() === 'POST' && req.url().endsWith('/runs')) creations++ })
  await begin(page)
  await emit(page, 'run.completed', {}, '1')
  await expect(page.getByRole('button', { name: '重试同步' })).toBeVisible()
  await expect(page.locator('.chat-toolbar p')).toContainText('同步失败')
  unavailable = false
  await page.getByRole('button', { name: '重试同步' }).click()
  await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED')
  await expect(page.locator('.message.assistant').last()).toContainText('同步成功')
  expect(creations).toBe(1)
})

test('leaving the page closes the stream and old callbacks cannot alter a new page', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await begin(page)
  await page.getByRole('menuitem', { name: 'MCP Servers' }).click()
  await expect.poll(() => page.evaluate(() => (window as unknown as { __streams: Array<{closed: boolean}> }).__streams[0].closed)).toBe(true)
  await page.getByRole('menuitem', { name: '对话', exact: true }).click()
  await emit(page, 'message.delta', { content: '旧会话不能写到这里' }, '9')
  await expect(page.locator('.messages')).not.toContainText('旧会话不能写到这里')
  expect(errors).toEqual([])
})

test('cancel failure is visible and retryable', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/v1/runs/run-1/cancellations', route => route.fulfill({ status: 503, json: { message: '稍后再试' } }))
  await begin(page)
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.locator('.chat-toolbar p')).toContainText('取消失败')
  await expect(page.getByRole('button', { name: '取消', exact: true })).toBeEnabled()
  expect(errors).toEqual([])
})

test('connection error reconciles a terminal Run even when the terminal event is missing', async ({ page }) => {
  await begin(page)
  await page.evaluate(() => (window as unknown as { __streams: Array<{onerror: (event: Event) => void}> }).__streams[0].onerror(new Event('error')))
  await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED')
  await expect(page.locator('.message.assistant').last()).toContainText('完整的持久化答案')
})

test('a late cancellation response cannot overwrite an already reconciled terminal status', async ({ page }) => {
  let pending: import('@playwright/test').Route | undefined
  await page.route('**/api/v1/runs/run-1/cancellations', route => { pending = route })
  await begin(page)
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect.poll(() => Boolean(pending)).toBe(true)
  await emit(page, 'run.completed', {}, '1')
  await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED')
  const response = page.waitForResponse(res => res.url().endsWith('/cancellations'))
  await pending!.fulfill({ status: 202, json: run('COMPLETED') })
  await response
  await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED')
})

test('poll-based clarification recovers Gap references from persisted events', async ({ page }) => {
  await page.route('**/api/v1/runs/run-1', route => route.fulfill({ json: run('NEEDS_INPUT', '请补充参数') }))
  await page.route('**/api/v1/runs/run-1/events', route => route.fulfill({ json: [
    { id: 1, sequence: 1, type: 'continuation.decided', payload: JSON.stringify({ action: 'CLARIFY', gapIds: ['gap-1'] }) },
  ] }))
  await begin(page)
  await page.evaluate(() => (window as unknown as { __streams: Array<{onerror: (event: Event) => void}> }).__streams[0].onerror(new Event('error')))
  await expect(page.locator('.chat-toolbar p')).toContainText('NEEDS_INPUT')
  await page.getByPlaceholder('输入消息；例如：计算 17 * 23').fill('参数为 7')
  const created = page.waitForRequest(req => req.method() === 'POST' && req.url().endsWith('/runs'))
  await page.getByRole('button', { name: '运行', exact: true }).click()
  expect((await created).postDataJSON().resume).toEqual({ runId: 'run-1', gapIds: ['gap-1'] })
})
