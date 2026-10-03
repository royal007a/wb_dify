# Chat 创建、取消、恢复和同步契约

SPEC-CHAT-LIFECYCLE-002/003；当前页面生命周期与身份恢复协议。003新增只读GET by-key，明确区分找回身份、本地放弃等待和服务端取消。

## 一次提交的身份

点击运行时固定 message、resume、conversationId（取得后）及一次生成的 Idempotency-Key；清掉上一个 activeRun。网络中断、10秒请求超时、5xx或无法解析结果时，不知道服务端是否已经提交，必须保留本次快照并显示“重试提交结果”。点击重试重发同一消息/恢复数据/键，不追加第二个用户气泡，不采纳输入框后来编辑的内容。

结果不明时禁止暗中换key；新会话、切换Agent和普通运行保持锁定，但有显式“放弃等待（不取消服务端）”出口。首次明确400/401/403/404/405/406/409/415/422拒绝可结束本次提交并允许修正，输入框空时恢复原文，不覆盖用户已经修改的内容。若之前已经有结果不明的尝试，即使后续收到4xx，也不能据此断言旧请求从未提交。重复错误更新一条事件。重试返回终态时主动回读，不依赖还有新的SSE事件。

后端按conversation/key找已有记录在Provider/Agent可变准入之前；命中仍比较完整请求摘要，异体40901，不重新调度。新key继续校验准入。前端丢失第一次响应、之后停用Provider的恢复用真实Spring/H2测试证明，不由浏览器mock替代。

显式放弃仅在当前请求不在进行时可用：清除页面内pending、结束等待、切断旧流并使旧回调失效，脱离旧会话；保留已有输入或恢复原文，显示“后台仍可能执行”。不自动发送新请求，不展示“已取消”。用户之后再次提交可能是新的工作，不能宣称全局去重。

这不是跨页面的持久outbox：刷新/离开/明确放弃会丢失待提交快照；会话创建本身也没有幂等键，响应丢失可能遗留空会话。多标签或直接API以不同key恢复同一NEEDS_INPUT仍可能创建多条后续Run；不宣称全局exactly-once或后端一次性resume认领。服务故障持续时可选择继续查询或本地放弃，不把未知结果伪装为失败。

## 取消

- 创建尚未取得runId时只标记本次取消意图，绝不调用上一Run的取消接口。
- 如果还在创建Conversation、尚未发送Run请求，取消后不发送该Run。
- Run请求已经发出：等本次响应；若结果不明，则仅GET `/api/v1/conversations/{id}/runs/by-key`，携带原`Idempotency-Key`，取得身份后才取消该Run。取消意图一旦记录，“重试提交结果”也只做GET，不再POST创建。
- GET只查持久记录，不写消息/事件/Run，不提交执行；Provider停用不阻挡读取。no-store/Vary防止不同header键混用缓存。404是此刻未找到，不是原请求不存在或已取消；保留pending并提示“未确认取消”，允许再查或显式放弃。原POST晚到仍可能运行，本片没有取消墓碑。
- 已经终态则按终态回读，不谎称撤回既有结果。
- 取消请求也有10秒等待上限；失败可再次请求。代际标记防止晚到响应覆盖已确认终态或新页面。

## 澄清与同步

流内CLARIFY只是暂存。终态以GET Run为准；CANCELLED/TIMED_OUT/FAILED/COMPLETED/LIMIT_EXCEEDED均清除resume。NEEDS_INPUT重读持久事件，使用最后continuation的有效gapIds，不能沿用较早流事件里的过期Gap。

如果请求事件失败，允许重试同步；如果成功取到的持久事件确实没有有效Gap，则明确显示无法恢复，禁止将下一条消息偷偷作为新任务，允许显式新建会话。这是损坏/旧记录的防御路径；未证明当前正常后端会产生空Gap的NEEDS_INPUT。

SSE重连成功后重置本次断线的自动补读预算（最多3次）；已连接时不会因一次晚到的RUNNING读取继续消耗断线预算。另外连续6次短连接失败会关闭流、停止自动补读并保留“重试同步”。收到新的受处理业务事件或连接持续至少15秒才重置连续短断线计数，HTTP200/onopen本身不重置；初始heartbeat不算业务进展。人工同步只读取状态，流已暂停时不自动重开/重发消息。终态事件在GET进行中到达时，记录待补读，在当前请求结束后再读一次，不能丢掉最后一次唤醒。

## 可执行证据

`frontend/e2e/chat-lifecycle.spec.ts`：真实浏览器/Vue，HTTP和EventSource受控。002原21项及003退出/lookup/短断线扩展；不等于真实后端SSE、事务、网络代理或模型验证。后端同key唯一性由RunFlow/PostgresConcurrency测试独立证明，停用Provider后身份恢复由RunSubmissionIdentityTest证明。证据分别见 `docs/evidence/SPEC_CHAT_LIFECYCLE_REVIEW.md` 和 `docs/evidence/SPEC_CHAT_SUBMISSION_RECOVERY.md`。
