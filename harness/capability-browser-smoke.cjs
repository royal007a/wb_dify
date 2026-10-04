// Real deployed browser; synthetic conversations only, no application edits.
const fs = require('node:fs');
const path = require('node:path');
const { chromium, expect } = require('../frontend/node_modules/@playwright/test');
const root = path.resolve(__dirname, '..');
const state = JSON.parse(fs.readFileSync(path.join(root, 'harness/state.json')));
if (state.currentTaskId !== 'CAPABILITY-ROLLOUT-001') throw Error('Atomic rollout task required');
const evidence = path.join(root, state.evidencePath);
const base = 'https://118.196.123.132/hify/';
(async () => {
  const browser = await chromium.launch({ channel: 'chrome' });
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const checks = [];
  try {
    for (const [name, inputs] of [['calculator', ['计算 17 * 23']], ['clock', ['hi', '现在几点', '现在几点？', '现在几点?']]]) {
      const page = await context.newPage();
      await page.goto(base + 'chat');
      await expect(page.getByRole('heading', { name: '对话' })).toBeVisible();
      const select = page.locator('.chat-toolbar .el-select');
      await expect(select).not.toHaveClass(/is-disabled/);
      await select.click();
      await page.getByRole('option', { name: /^Demo Agent · v\d+$/ }).click();
      await expect(select).toContainText('Demo Agent · v');
      await expect(page.locator('.message.assistant').first()).toContainText('本地规则模拟');
      let conversationId;
      let agentVersionId;
      const runs = [];
      for (const input of inputs) {
        const pending = page.waitForResponse(r => r.request().method() === 'POST' && /\/api\/v1\/conversations\/[^/]+\/runs$/.test(r.url()));
        await page.getByPlaceholder('输入消息；例如：计算 17 * 23').fill(input);
        await page.getByRole('button', { name: '运行', exact: true }).click();
        const response = await pending;
        expect(response.status()).toBe(202);
        const run = await response.json();
        expect(run.agentVersionId).toBeTruthy();
        if (conversationId) {
          expect(run.conversationId).toBe(conversationId);
          expect(run.agentVersionId).toBe(agentVersionId);
        }
        conversationId = run.conversationId;
        agentVersionId = run.agentVersionId;
        await expect(page.locator('.chat-toolbar p')).toContainText('COMPLETED');
        const answer = page.locator('.message.assistant').last();
        if (name === 'calculator') {
          await expect(page.getByText('调用工具：calculator')).toBeVisible();
          await expect(answer).toContainText('391');
        } else if (input === 'hi') {
          await expect(answer).toContainText('未调用真实大模型');
          await expect(page.locator('.chat-toolbar p')).toContainText('0 tools');
        } else {
          await expect(page.getByText('调用工具：current_time', { exact: true }).last()).toBeVisible();
          await expect(answer).toContainText(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/);
          await expect(page.locator('.chat-toolbar p')).toContainText('1 tools');
        }
        const persistedResponse = await context.request.get(base + 'api/v1/runs/' + run.id);
        expect(persistedResponse.status()).toBe(200);
        const persisted = await persistedResponse.json();
        expect(persisted.state).toBe('COMPLETED');
        expect(persisted.agentVersionId).toBe(agentVersionId);
        runs.push({ id: run.id, state: persisted.state, output: persisted.outputMessage });
      }
      await expect(page.locator('.composer-actions')).toContainText('固定版本');
      checks.push({ case: name, conversationId, agentVersionId, runs });
      await page.close();
    }
    fs.writeFileSync(path.join(evidence, 'browser-live.json'), JSON.stringify({
      base, tlsCertificateValidation: false, checks,
      scope: 'Explicit Demo selection; real deployed backend, Mock model. Synthetic conversations retained.'
    }, null, 2) + '\n');
    console.log('Two browser scenarios passed; five persisted runs completed.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
