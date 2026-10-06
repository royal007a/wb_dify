// Deployed UI + persisted Mock runs; no paid model, no existing conversations modified.
const fs = require('node:fs');
const path = require('node:path');
const { chromium, expect } = require('../frontend/node_modules/@playwright/test');
const root = path.resolve(__dirname, '..');
const state = JSON.parse(fs.readFileSync(path.join(root, 'harness/state.json')));
if (state.currentTaskId !== 'CONTEXT-ROLLOUT-001') throw Error('Atomic rollout task required');
const evidence = path.join(root, state.evidencePath);
const base = 'https://118.196.123.132/hify/';
(async () => {
  const browser = await chromium.launch({ channel: 'chrome' });
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const checks = [];
  const createdRecords = { conversations: [], runs: [] };
  let passed = false;
  try {
    for (const [name, inputs] of [['calculator', ['计算 17 * 23']], ['clock', ['hi', '现在几点', '现在几点？', '现在几点?']]]) {
      const page = await context.newPage();
      await page.goto(base + 'chat');
      await expect(page.getByRole('heading', { name: '对话' })).toBeVisible();
      await expect(page.locator('.chat-toolbar .el-select')).toContainText('· v', { timeout: 15000 });
      await page.getByRole('combobox').focus();
      await page.getByRole('combobox').press('ArrowDown');
      await page.getByRole('option', { name: /^Demo Agent · v\d+$/ }).click();
      await expect(page.locator('.message.assistant').first()).toContainText('本地规则模拟');
      const created = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/api/v1/conversations'));
      let conversation;
      const runs = [];
      for (const input of inputs) {
        const before = await page.locator('.message.assistant').count();
        const pending = page.waitForResponse(r => r.request().method() === 'POST' && /\/api\/v1\/conversations\/[^/]+\/runs$/.test(r.url()));
        await page.getByPlaceholder('输入消息；例如：计算 17 * 23').fill(input);
        await page.getByRole('button', { name: '运行', exact: true }).click();
        const response = await pending;
        expect(response.status()).toBe(202);
        const run = await response.json();
        if (run.id) createdRecords.runs.push(run.id);
        if (!conversation) {
          const creation = await created;
          expect(creation.status()).toBe(201);
          conversation = await creation.json();
          if (conversation.id) createdRecords.conversations.push(conversation.id);
          expect(conversation.agentVersionId).toBeTruthy();
        }
        expect(run.conversationId).toBe(conversation.id);
        expect(run.agentVersionId).toBe(conversation.agentVersionId);
        expect(run.streamUrl).toContain(`/api/v1/runs/${run.id}/events/stream`);
        await expect(page.getByRole('button', { name: '运行', exact: true })).toBeVisible({ timeout: 30000 });
        await expect(page.locator('.message.assistant')).toHaveCount(before + 1);
        await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED');
        const persistedResponse = await context.request.get(base + 'api/v1/runs/' + run.id);
        expect(persistedResponse.status()).toBe(200);
        const persisted = await persistedResponse.json();
        expect(persisted.state).toBe('COMPLETED');
        expect(persisted.agentVersionId).toBe(conversation.agentVersionId);
        const answer = page.locator('.message.assistant').last();
        if (name === 'calculator') {
          await expect(page.getByText('调用工具：calculator')).toBeVisible();
          await expect(answer).toContainText('391');
          expect(persisted.outputMessage).toContain('391');
        } else if (input === 'hi') {
          await expect(answer).toContainText('未调用真实大模型');
          expect(persisted.outputMessage).toContain('未调用真实大模型');
          await expect(page.locator('.chat-toolbar p')).toContainText('0 tools');
        } else {
          await expect(page.getByText('调用工具：current_time', { exact: true }).last()).toBeVisible();
          await expect(answer).toContainText(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/);
          await expect(answer).not.toContainText('已收到');
          expect(persisted.outputMessage).toMatch(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/);
          expect(persisted.outputMessage).not.toContain('已收到');
          await expect(page.locator('.chat-toolbar p')).toContainText('1 tools');
        }
        runs.push({ id: run.id, state: persisted.state, output: persisted.outputMessage, streamUrl: run.streamUrl });
      }
      await expect(page.locator('.composer-actions')).toContainText('固定版本');
      checks.push({ case: name, conversationId: conversation.id, agentVersionId: conversation.agentVersionId, runs });
      await page.close();
    }
    passed = true;
  } finally {
    fs.writeFileSync(path.join(evidence, 'browser-live.json'), JSON.stringify({
      base, passed, tlsCertificateValidation: false, checks, createdRecords,
      scope: 'Explicit Demo selection, not default-first-option assertion. Real deployed UI/backend, Mock model. Own two conversations/five runs retained.'
    }, null, 2) + '\n');
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
