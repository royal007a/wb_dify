# ADR-0007：原生 Provider 流统一投影为持久 Run 事件

- 状态：Accepted
- 日期：2026-09-13

## 背景

原型用同步模型请求，模型完成后才发送整段 `message.delta`，不能实现首 token 低延迟，也无法在供应商流阻塞时及时响应 Run 取消。

## 决策

ModelClient 增加流式入口；OpenAI/compatible、Anthropic、Gemini 分别解析原生 SSE，并仍返回统一 RuntimeMessage 供 tool call 循环使用。文本分片通过 RunObserver 写入持久 `message.delta`。共享 ExecutionControl 负责 cancellation/deadline；流开始后不做整个请求的自动重试。

浏览器仍订阅 Hify 的 RunEventBroker，而不是直连供应商，因此可继续使用 eventId replay、统一脱敏和终态协议。

## 后果

- 前端实时获得 token，协议差异不进入 Query Loop。
- 工具参数必须跨 delta 重组并在闭流后解析。
- Provider 断流后本次 Run 失败；已输出分片不能被无条件重试。后续如做续写，必须有供应商幂等能力和显式去重协议。
