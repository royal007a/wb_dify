# Chat UI 生命周期修复

任务 `SPEC-CHAT-UI-001`，基线 `37bb462`。仅前端，不修改后端协议、数据库、部署、旧会话或真实凭据。

## 红灯证据

新增 `frontend/e2e/chat-lifecycle.spec.ts`：真实浏览器/Vue页面，HTTP 与 EventSource 受控。首轮默认30s在 beforeEach 准备阶段超时，后续主动停止；这不能算功能反例，日志 `/tmp/hify-chat-lifecycle-red.log`。

第二轮单worker、测试总超时120s、最多1个失败，真实到达页面并开始Run，断言“新会话按钮应禁用”失败：实际按钮仍可点击。日志 `/tmp/hify-chat-lifecycle-red2.log`，截图/trace在 frontend/test-results 对应目录。其余6项未跑，不能称全部红灯复现。新测试保留120s浏览器准备余量，单条UI断言仍为原来的10s，不放宽生产HTTP/运行预算。

## 变更与测试边界

- 运行期间禁止新会话；页面退出关闭事件源/计时器，代际标记阻止旧Promise与事件污染新页面。
- 按SSE事件id去重；终态重新读取Run，用持久化完整输出对账已显示的部分文本。
- 同步失败显示“重试同步”，只重读状态，不重发用户消息；单次只读HTTP最多10s，自动补读最多3次，之后由用户重试。
- 断线但未收到终态事件时，读取Run来收敛；不能把RUNNING错误当作已完成。
- NEEDS_INPUT缺失continuation事件时，从已有events端点恢复gapIds；缺恢复信息不能无声发送全新任务。
- 取消失败可见；晚到的取消响应不得覆盖已经确认的终态。

`npm run typecheck` 已通过。8项浏览器矩阵及最终typecheck/build结果以本任务runner证据为准；在执行结束前不能写成通过。

首次修复回归 `/tmp/hify-chat-lifecycle-green.log`：首条通过，第二条触发测试总120s超时，snapshot已显示COMPLETED和完整持久化答案；trace分阶段为Create page47.27s、Navigate58.06s、功能断言约1s。停止余下运行，不能把第二条算通过。改用已安装的Playwright chromium-headless-shell，关闭trace录制，显式1440宽视口；8个场景和10s断言保持不变。无需安装新浏览器或改动已有Chrome用户会话。

这些受控测试证明前端状态管理，不证明后端事务/SSE提交顺序正确，也不替代真实Provider、PostgreSQL、部署端到端验证。
