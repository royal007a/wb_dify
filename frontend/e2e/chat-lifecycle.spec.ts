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
      onopen: ((event: Event) => void) | null = null
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

test('cancel during the second creation targets only its late Run response', async ({ page }) => {
  await begin(page)
  await emit(page, 'run.completed', {}, '1')
  await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED')
  let creation: import('@playwright/test').Route | undefined
  const cancelled: string[] = []
  await page.route('**/api/v1/conversations/conversation-1/runs', route => { creation = route })
  await page.route('**/api/v1/runs/*/cancellations', route => {
    cancelled.push(route.request().url()); return route.fulfill({ status: 202, json: { ...run('RUNNING'), id: 'run-2' } })
  })
  await page.getByPlaceholder('输入消息；例如：计算 17 * 23').fill('second')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => Boolean(creation)).toBe(true)
  await page.getByRole('button', { name: '取消', exact: true }).click()
  expect(cancelled).toEqual([])
  await creation!.fulfill({ status: 202, json: { ...run('RUNNING'), id: 'run-2', streamUrl: '/api/v1/runs/run-2/events/stream' } })
  await expect.poll(() => cancelled.map(url => new URL(url).pathname)).toEqual(['/api/v1/runs/run-2/cancellations'])
})

for (const state of ['CANCELLED', 'TIMED_OUT']) test(`late ${state} clears provisional clarification before a new message`, async ({ page }) => {
  await page.route('**/api/v1/runs/run-1', route => route.fulfill({ json: run(state) }))
  await begin(page)
  await emit(page, 'continuation.decided', { action: 'CLARIFY', gapIds: ['stale-gap'] }, '1')
  await emit(page, 'run.cancelled', {}, '2')
  await expect(page.locator('.chat-toolbar p')).toContainText(state)
  await page.getByPlaceholder('输入消息；例如：计算 17 * 23').fill('new task')
  const request = page.waitForRequest(req => req.method() === 'POST' && req.url().endsWith('/runs'))
  await page.getByRole('button', { name: '运行', exact: true }).click()
  expect((await request).postDataJSON()).not.toHaveProperty('resume')
})

test('unknown creation outcome retries the same request key and user message', async ({ page }) => {
  const keys: string[] = [], bodies: unknown[] = []
  await page.route('**/api/v1/conversations/conversation-1/runs', route => {
    keys.push(route.request().headers()['idempotency-key']); bodies.push(route.request().postDataJSON())
    return keys.length === 1 ? route.abort('failed') : route.fulfill({ status: 200, json: run('COMPLETED', '已恢复提交结果') })
  })
  await page.goto('./chat')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试提交结果', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '新会话' })).toBeDisabled()
  await page.getByPlaceholder('输入消息；例如：计算 17 * 23').fill('不能偷偷换成另一个问题')
  await page.getByRole('button', { name: '重试提交结果', exact: true }).click()
  await expect.poll(() => keys.length).toBe(2)
  expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0]); expect(bodies[1]).toEqual(bodies[0])
  await expect(page.locator('.message.user')).toHaveCount(1)
})

test('terminal event during the last inflight poll schedules another reread', async ({ page }) => {
  let reads = 0, held: import('@playwright/test').Route | undefined
  await page.route('**/api/v1/runs/run-1', route => {
    reads++
    if (reads === 3) { held = route; return }
    return route.fulfill({ json: run(reads < 3 ? 'RUNNING' : 'COMPLETED', '最终结果') })
  })
  await begin(page)
  await page.evaluate(() => (window as unknown as { __streams: Array<{onerror: (event: Event) => void}> }).__streams[0].onerror(new Event('error')))
  await expect.poll(() => Boolean(held)).toBe(true)
  await emit(page, 'run.completed', {}, '1')
  await held!.fulfill({ json: run('RUNNING') })
  await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED')
  expect(reads).toBeGreaterThanOrEqual(4)
})

test('healthy SSE reconnects reset consecutive read retry allowance', async ({ page }) => {
  let reads = 0
  await page.route('**/api/v1/runs/run-1', route => { reads++; return route.fulfill({ json: run('RUNNING') }) })
  await begin(page)
  for (let i = 0; i < 4; i++) {
    const previous = reads
    await page.evaluate(() => (window as unknown as { __streams: Array<{onerror: (event: Event) => void}> }).__streams[0].onerror(new Event('error')))
    await expect.poll(() => reads).toBeGreaterThan(previous)
    await page.evaluate(() => (window as unknown as { __streams: Array<{onopen?: (event: Event) => void}> }).__streams[0].onopen?.(new Event('open')))
  }
  await expect(page.getByRole('button', { name: '重试同步' })).not.toBeVisible()
})

test('cancel before conversation creation completes never submits a Run', async ({ page }) => {
  let held: import('@playwright/test').Route | undefined, runs = 0
  await page.route('**/api/v1/conversations', route => { held = route })
  page.on('request', req => { if (req.method() === 'POST' && req.url().endsWith('/runs')) runs++ })
  await page.goto('./chat')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await held!.fulfill({ status: 201, json: { id: 'conversation-1', agentVersionId: 'version-1' } })
  await expect(page.locator('.chat-toolbar p')).toContainText('尚未提交，已取消')
  expect(runs).toBe(0)
})

test('cancel after an unknown creation looks up the same key without creating again', async ({ page }) => {
  const keys: string[] = [], lookups: string[] = []; let cancellations = 0
  await page.route('**/api/v1/conversations/conversation-1/runs', route => {
    keys.push(route.request().headers()['idempotency-key'])
    return keys.length === 1 ? route.abort('failed') : route.fulfill({ status: 200, json: run('RUNNING') })
  })
  await page.route('**/api/v1/conversations/conversation-1/runs/by-key', route => {
    lookups.push(route.request().headers()['idempotency-key'])
    expect(route.request().method()).toBe('GET')
    return route.fulfill({ json: run('RUNNING') })
  })
  await page.route('**/api/v1/runs/run-1/cancellations', route => { cancellations++; return route.fulfill({ status: 202, json: run('RUNNING') }) })
  await page.goto('./chat')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试提交结果', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect.poll(() => cancellations).toBe(1)
  expect(keys).toHaveLength(1); expect(lookups).toEqual(keys)
})

test('persisted empty Gap clarification cannot silently start another task or loop sync', async ({ page }) => {
  await page.route('**/api/v1/runs/run-1', route => route.fulfill({ json: run('NEEDS_INPUT', '请补充') }))
  await page.route('**/api/v1/runs/run-1/events', route => route.fulfill({ json: [
    { id: 2, type: 'continuation.decided', payload: JSON.stringify({ action: 'CLARIFY', gapIds: [] }) },
  ] }))
  await begin(page)
  await emit(page, 'continuation.decided', { action: 'CLARIFY', gapIds: ['outdated-gap'] }, '1')
  await emit(page, 'run.needs_input', {}, '2')
  await expect(page.locator('.messages')).toContainText('缺少恢复所需的 Gap 标识')
  await expect(page.getByRole('button', { name: '重试同步' })).not.toBeVisible()
  await page.getByPlaceholder('输入消息；例如：计算 17 * 23').fill('cannot resume')
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '新会话' })).toBeEnabled()
})

test('persisted Gap ids supersede a provisional streaming clarification', async ({ page }) => {
  await page.route('**/api/v1/runs/run-1', route => route.fulfill({ json: run('NEEDS_INPUT') }))
  await page.route('**/api/v1/runs/run-1/events', route => route.fulfill({ json: [
    { id: 2, type: 'continuation.decided', payload: JSON.stringify({ action: 'CLARIFY', gapIds: ['current-gap'] }) },
  ] }))
  await begin(page)
  await emit(page, 'continuation.decided', { action: 'CLARIFY', gapIds: ['outdated-gap'] }, '1')
  await emit(page, 'run.needs_input', {}, '2')
  await expect(page.locator('.chat-toolbar p')).toContainText('NEEDS_INPUT')
  await page.getByPlaceholder('输入消息；例如：计算 17 * 23').fill('answer')
  const request = page.waitForRequest(req => req.method() === 'POST' && req.url().endsWith('/runs'))
  await page.getByRole('button', { name: '运行', exact: true }).click()
  expect((await request).postDataJSON().resume.gapIds).toEqual(['current-gap'])
})

test('an initial definite HTTP rejection allows corrected input', async ({ page }) => {
  await page.route('**/api/v1/conversations/conversation-1/runs', route => route.fulfill({ status: 400, json: { message: '参数错误' } }))
  await page.goto('./chat')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.locator('.chat-toolbar p')).toContainText('提交被拒绝')
  await expect(page.getByRole('button', { name: '重试提交结果' })).not.toBeVisible()
  await expect(page.getByPlaceholder('输入消息；例如：计算 17 * 23')).toHaveValue('现在几点？')
  await page.getByPlaceholder('输入消息；例如：计算 17 * 23').fill('corrected')
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeEnabled()
})

test('a later HTTP rejection does not erase an earlier ambiguous submission', async ({ page }) => {
  const keys: string[] = []
  await page.route('**/api/v1/conversations/conversation-1/runs', route => {
    keys.push(route.request().headers()['idempotency-key'])
    return route.fulfill({ status: keys.length === 1 ? 500 : 403, json: { message: keys.length === 1 ? 'temporarily unavailable' : 'permission denied' } })
  })
  await page.goto('./chat')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试提交结果' })).toBeVisible()
  await page.getByRole('button', { name: '重试提交结果' }).click()
  await expect.poll(() => keys.length).toBe(2)
  await expect(page.locator('.message.event')).toContainText('permission denied')
  await expect(page.getByRole('button', { name: '重试提交结果' })).toBeVisible()
  await expect(page.getByRole('button', { name: '新会话' })).toBeDisabled()
  expect(keys[0]).toBe(keys[1])
  await expect(page.locator('.message.event')).toHaveCount(1)
  await expect(page.getByRole('button', { name: '放弃等待（不取消服务端）', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '放弃等待（不取消服务端）', exact: true }).click()
  await expect(page.getByRole('button', { name: '新会话', exact: true })).toBeEnabled()
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeEnabled()
  await expect(page.locator('.messages')).toContainText('后台仍可能执行')
  await expect(page.getByPlaceholder('输入消息；例如：计算 17 * 23')).toHaveValue('现在几点？')
  expect(keys).toHaveLength(2)
})

test('missing lookup on cancel keeps the uncertainty and never sends a creation POST', async ({ page }) => {
  let creations = 0, lookups = 0
  await page.route('**/api/v1/conversations/conversation-1/runs', route => { creations++; return route.abort('failed') })
  await page.route('**/api/v1/conversations/conversation-1/runs/by-key', route => {
    lookups++; return route.fulfill({ status: 404, json: { message: 'not found yet' } })
  })
  await page.goto('./chat')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试提交结果' })).toBeVisible()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect.poll(() => lookups).toBe(1)
  await expect(page.locator('.chat-toolbar p')).toContainText('未确认取消')
  await page.getByRole('button', { name: '重试提交结果' }).click()
  await expect.poll(() => lookups).toBe(2)
  expect(creations).toBe(1)
  await expect(page.getByRole('button', { name: '放弃等待（不取消服务端）' })).toBeEnabled()
})

test('an edited draft is not overwritten by a late definite rejection', async ({ page }) => {
  let held: import('@playwright/test').Route | undefined
  await page.route('**/api/v1/conversations/conversation-1/runs', route => { held = route })
  await page.goto('./chat')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => Boolean(held)).toBe(true)
  await page.getByPlaceholder('输入消息；例如：计算 17 * 23').fill('已经修改的输入')
  await held!.fulfill({ status: 400, json: { message: '参数错误' } })
  await expect(page.locator('.chat-toolbar p')).toContainText('提交被拒绝')
  await expect(page.getByPlaceholder('输入消息；例如：计算 17 * 23')).toHaveValue('已经修改的输入')
})

test('repeated opens followed by errors have a finite automatic reconnect budget', async ({ page }) => {
  let reads = 0
  await page.route('**/api/v1/runs/run-1', route => { reads++; return route.fulfill({ json: run('RUNNING') }) })
  await begin(page)
  await page.evaluate(() => {
    const source = (window as unknown as { __streams: Array<{onopen: (event: Event) => void; onerror: (event: Event) => void}> }).__streams[0]
    for (let i = 0; i < 6; i++) { source.onopen(new Event('open')); source.onerror(new Event('error')) }
  })
  await expect(page.getByRole('button', { name: '重试同步' })).toBeVisible()
  await expect.poll(() => page.evaluate(() => (window as unknown as { __streams: Array<{closed: boolean}> }).__streams[0].closed)).toBe(true)
  const before = reads
  await page.getByRole('button', { name: '重试同步' }).click()
  await expect.poll(() => reads).toBeGreaterThan(before)
  await expect(page.getByRole('button', { name: '重试同步' })).toBeVisible()
})

test('already terminal creation reconciles without a live terminal event', async ({ page }) => {
  await page.route('**/api/v1/conversations/conversation-1/runs', route => route.fulfill({ status: 200, json: run('COMPLETED') }))
  await begin(page)
  await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED')
  await expect(page.locator('.messages')).toContainText('完整的持久化答案')
})

test('lookup can find a late original submission after an earlier miss without POST retries', async ({ page }) => {
  let posts = 0, reads = 0, cancelled = 0
  await page.route('**/api/v1/conversations/conversation-1/runs', route => { posts++; return route.abort('failed') })
  await page.route('**/api/v1/conversations/conversation-1/runs/by-key', route => ++reads === 1
    ? route.fulfill({ status: 404, json: { message: 'not yet' } }) : route.fulfill({ json: run('RUNNING') }))
  await page.route('**/api/v1/runs/run-1/cancellations', route => { cancelled++; return route.fulfill({ status: 202, json: run('RUNNING') }) })
  await page.goto('./chat')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试提交结果' })).toBeVisible()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.locator('.chat-toolbar p')).toContainText('未确认取消')
  await page.getByRole('button', { name: '重试提交结果' }).click()
  await expect.poll(() => cancelled).toBe(1)
  expect(posts).toBe(1); expect(reads).toBe(2)
})

test('lookup cannot attach or cancel a Run from another conversation', async ({ page }) => {
  let cancelled = 0
  await page.route('**/api/v1/conversations/conversation-1/runs', route => route.abort('failed'))
  await page.route('**/api/v1/conversations/conversation-1/runs/by-key', route => route.fulfill({ json: { ...run('RUNNING'), conversationId: 'wrong' } }))
  await page.route('**/cancellations', route => { cancelled++; return route.fulfill({ json: run('RUNNING') }) })
  await page.goto('./chat')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试提交结果' })).toBeVisible()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.locator('.chat-toolbar p')).toContainText('未确认取消')
  expect(cancelled).toBe(0)
  expect(await page.evaluate(() => (window as unknown as { __streams: unknown[] }).__streams.length)).toBe(0)
})

test('stable connections reset the short-disconnect streak', async ({ page }) => {
  await page.clock.install()
  await begin(page)
  for (let i = 0; i < 8; i++) {
    await page.evaluate(() => (window as unknown as { __streams: Array<{onopen: (event: Event) => void}> }).__streams[0].onopen(new Event('open')))
    await page.clock.fastForward(16_000)
    await page.evaluate(() => (window as unknown as { __streams: Array<{onerror: (event: Event) => void}> }).__streams[0].onerror(new Event('error')))
  }
  expect(await page.evaluate(() => (window as unknown as { __streams: Array<{closed: boolean}> }).__streams[0].closed)).toBe(false)
  await expect(page.getByRole('button', { name: '重试同步' })).not.toBeVisible()
})

test('replayed duplicate events cannot reset the short-disconnect budget', async ({ page }) => {
  await begin(page)
  for (let i = 0; i < 6; i++) {
    await page.evaluate(() => (window as unknown as { __streams: Array<{onopen: (event: Event) => void}> }).__streams[0].onopen(new Event('open')))
    await emit(page, 'tool.call.started', { tool: 'same replay' }, 'same-id')
    await page.evaluate(() => (window as unknown as { __streams: Array<{onerror: (event: Event) => void}> }).__streams[0].onerror(new Event('error')))
  }
  expect(await page.evaluate(() => (window as unknown as { __streams: Array<{closed: boolean}> }).__streams[0].closed)).toBe(true)
  await expect(page.getByRole('button', { name: '重试同步' })).toBeVisible()
})
