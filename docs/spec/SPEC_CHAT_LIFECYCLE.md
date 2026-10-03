# Chat 创建、取消、恢复和同步契约

SPEC-CHAT-LIFECYCLE-002；仅当前页面的生命周期与客户端协议，不增加后端端点。

## 一次提交的身份

点击运行时固定 message、resume、conversationId（取得后）及一次生成的 Idempotency-Key；清掉上一个 activeRun。网络中断、10秒请求超时、5xx或无法解析结果时，不知道服务端是否已经提交，必须保留本次快照并显示“重试提交结果”。点击重试重发同一消息/恢复数据/键，不追加第二个用户气泡，不采纳输入框后来编辑的内容。

结果不明时禁止新会话、切换Agent和普通运行，避免暗中换key。首次明确400/401/403/404/405/406/409/415/422拒绝可结束本次提交并允许修正；若之前已经有结果不明的尝试，即使后续收到4xx，也不能据此断言旧请求从未提交。重试返回终态时主动回读，不依赖还有新的SSE事件。

这不是跨页面的持久outbox：刷新/离开页面会丢失待提交快照；会话创建本身也没有幂等键，响应丢失可能遗留空会话。多标签或直接API以不同key恢复同一NEEDS_INPUT仍可能创建多条后续Run；本片不宣称全局exactly-once或后端一次性resume认领。服务故障持续时需恢复服务再重试，不把未知结果伪装为失败。

## 取消

- 创建尚未取得runId时只标记本次取消意图，绝不调用上一Run的取消接口。
- 如果还在创建Conversation、尚未发送Run请求，取消后不发送该Run。
- Run请求已经发出：等本次响应；若结果不明，则通过同key重试取得身份，再取消该Run。已经终态则按终态回读，不谎称撤回既有结果。
- 取消请求也有10秒等待上限；失败可再次请求。代际标记防止晚到响应覆盖已确认终态或新页面。

## 澄清与同步

流内CLARIFY只是暂存。终态以GET Run为准；CANCELLED/TIMED_OUT/FAILED/COMPLETED/LIMIT_EXCEEDED均清除resume。NEEDS_INPUT重读持久事件，使用最后continuation的有效gapIds，不能沿用较早流事件里的过期Gap。

如果请求事件失败，允许重试同步；如果成功取到的持久事件确实没有有效Gap，则明确显示无法恢复，禁止将下一条消息偷偷作为新任务，允许显式新建会话。这是损坏/旧记录的防御路径；未证明当前正常后端会产生空Gap的NEEDS_INPUT。

SSE重连成功后重置本次断线的自动补读预算（最多3次）；已连接时不会因一次晚到的RUNNING读取继续消耗断线预算。终态事件在GET进行中到达时，记录待补读，在当前请求结束后再读一次，不能丢掉最后一次唤醒。失败耗尽后保留“重试同步”，不重发用户消息。

## 可执行证据

`frontend/e2e/chat-lifecycle.spec.ts`：真实浏览器/Vue，HTTP和EventSource受控。覆盖原8项、6个红灯竞态及7个扩展边界；不等于真实后端SSE、事务、网络代理或模型验证。后端同key唯一性仍由RunFlow/PostgresConcurrency测试独立证明。最终frontend/runtime/harness门禁与证据见 `docs/evidence/SPEC_CHAT_LIFECYCLE_REVIEW.md`。
