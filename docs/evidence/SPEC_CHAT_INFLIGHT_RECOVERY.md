# Chat 在途恢复补强（005，未正式收口）

基线37aa151，代码f20bd34。任务仍blocked：本地Colima数据盘100%，约256MiB可用；本轮没有启动真实PG/Docker测试，没有连132或部署，也未删除共享资源。没有缩减任务的frontend/backend/harness验收范围。

证据目录：`harness/evidence/SPEC-CHAT-LIFECYCLE-005/SPEC-CHAT-LIFECYCLE-005-20261004T043548Z-029b3cb7/`。

## 实现与反例

- 移除人工重试入口的reading早退；按钮使用aria-busy而非禁用点击的loading。暂停的同Run SSE立即重建一次；reconcile的rereadRequested合并在途GET后的补读；已收事件ID和答案位置不变。终态已观察时不重建流。
- 未知提交已记取消意图时，按钮显示“查询取消结果”；原GET lookup路径不变，不POST。
- 新key的缺失/跨会话resume来源均返回ErrorCode.PARAM_ERROR（40000/HTTP400，固定“参数错误”），不回显ID。仍在写入前拒绝；同会话非NEEDS_INPUT保持409，命中幂等键优先比对请求摘要。

## 已实际执行（独立窄测，不等于全量验收）

1. 旧前端上，新加的RUNNING/COMPLETED两种held-GET用例均失败：实际点击被loading禁用。未使用force点击绕过按钮。
2. 旧后端上，单方法负例失败：跨会话来源返回可区分文案。首版断言拟用自定义固定文案，随后遵守既有ErrorCode约束改为PARAM_ERROR文案，再跑仍失败；两份日志均保留。
3. 首次修复后浏览器5项中3通过、2失败：新增测试把欢迎气泡也选进答案断言，属于测试夹具错误。改为last答案、总数对初始助手气泡恰好+1，未削弱“只交付一次”。这不是另一个产品缺陷。
4. 最终`chat-lifecycle.spec.ts` **34/34**：真实Chromium/Vue，所有HTTP和EventSource受控。held GET期间连续点击两次，只产生同Run的第二个流且没有并发GET；旧流回调和重复ID不追加内容。终态事件到达后，held GET返回RUNNING时仅补读一次；直接返回COMPLETED时不再补读。答案均只增加一份，POST计数仍1。另覆盖取消按钮仅GET及既有失效resume退出。
5. 指定H2：RunSubmissionIdentityTest **15/15**、RunFlowIntegrationTest **2/2**，失败/错误/skip/flaky全0。来自本轮新reports目录，不读取旧target/XML。缺失与跨会话错误全JSON相等，lookup未创建记录、Run/消息/事件不增、执行器无新增调用；同一来源在自身会话仍409；合法创建计数+1和调度+1，既有有效Gap恢复仍COMPLETED。
6. 独立`verify.sh --scope frontend,harness`七步exit0：typecheck、build、状态、进度、API生成、Python **64/64**、shell语法。manifest在`local-gate/verification.json`，head=f20bd34、finishedAt=2026-10-04T04:49:01Z、runId=null、testCoverage=not-assessed；它只代表选定命令，不能作为本任务完整验收或PG通过证据。

摘要：`narrow-summary.json`记录每次红/绿/夹具错误、日志SHA、backend/frontend树、空源码diff SHA；`h2-narrow-counts.json`由本轮XML逐testcase核对suite计数和15/2最少用例生成。原始日志/XML/浏览器失败截图保留本地且不提交；仅凭Git不能复验日志SHA或断言执行，不能将摘要当成签名。未生成“全路由通过”新报告。

## 复跑命令

前端启动独立Vite于127.0.0.1:18194（测试后已停止），在frontend目录：

```sh
E2E_BASE_URL=http://127.0.0.1:18194/ ./node_modules/.bin/playwright test e2e/chat-lifecycle.spec.ts --workers=1
```

H2在backend目录，选择器明确排除PG类：

```sh
mvn -B -pl hify-app -am -Dtest=RunSubmissionIdentityTest,RunFlowIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dhify.test.reportsDirectory=<new-empty-report-directory> test
```

完整backend、真实PG和本次真实网络SSE均未验证。runner预检守卫返回75，原始记录保留“task command failed”；tasks.json将实际环境阻碍明确记入blockedReason。恢复空间后必须按原完整范围重新跑runner，不能沿用窄测宣布完成。
