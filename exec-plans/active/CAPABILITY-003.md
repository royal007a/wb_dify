# CAPABILITY-003

目标：把 MCP 已有的不可变发布快照扩展为可执行的故障契约，而不是只验证正常路径。

1. MCP 阻塞 HTTP 请求接入共享 `ExecutionControl`，轮询取消与 deadline，取消时终止异步请求。
2. 故障注入覆盖服务 503、短 deadline 和运行中取消，并验证错误分类及响应时限。
3. 模拟 `orderId -> reference` schema 漂移；发布两个 AgentVersion，验证旧版本仍引用旧 revision/schema 并可执行。
4. 通过 `Last-Event-ID` 验证持久化工具事件和终态事件可 replay，且游标之前事件不会重复发送。
5. 运行 backend/runtime 验证矩阵，证据写入 Harness。

边界：不新增 WRITE MCP 工具，不引入通用重试或补偿；本任务只固化 READ 工具的不可变能力与故障行为。
