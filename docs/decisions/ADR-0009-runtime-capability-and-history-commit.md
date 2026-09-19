# ADR-0009：Run 能力租约与 canonical history 提交

- 状态：Accepted
- 日期：2026-09-19

## 背景

Agent 发布版本已经固定配置和工具名称，但工具 schema/实现可能在 Run 接纳后变化；权限等待、取消和重启也会让旧 Attempt 在错误时间执行。另一方面，内存消息和 SSE 事件不能证明模型/工具结果已经成为可恢复的正式历史。

## 决策

1. Run 创建时从 AgentVersion 和有序 ToolDefinition 计算 `capabilityRevision` 与 `toolSchemaDigest` 并持久化。
2. QueryLoop 只使用该不可变能力视图；实际 runtime 不匹配时拒绝运行，不静默切换工具版本。
3. 每个工具 Attempt 建立执行租约；schema/权限检查后、实际调用前再次核对 run、attempt、capability revision 和取消状态。
4. 模型响应与最终 tool result 通过统一 writer 按 `operationId` 提交 canonical history：捕获序列化快照、持久化、回读校验、取得 revision、完成 `history.committed` 投影，随后才向 QueryLoop 返回。
5. 同一 Run 中相同 operationId 只能对应同一 semantic digest；内容不同视为冲突。当前保证是单数据库/单进程范围的可重放，不宣称外部副作用 exactly-once。

## 后果

- Run 恢复和审计拥有独立于瞬时 SSE 的正式历史 revision。
- 工具升级不会静默改变已接纳 Run；旧 Run 可能以 `CAPABILITY_MISMATCH` 失败，需要显式新建 Run。
- 历史 mutation 与事件投影仍存在进程崩溃窗口；未投影 commit 会在相同 operation 重放时补投影，消费者必须按 operation/revision 幂等。
- 外部 write 工具仍必须另建 side-effect ledger、目标幂等键和 compensation。
