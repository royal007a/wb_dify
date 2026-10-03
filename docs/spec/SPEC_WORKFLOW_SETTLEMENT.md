# Workflow 执行事实与 Run 交付结算

范围：同实例、正常数据库可用；不修改发布版本，不改执行历史，不新增跨模块 repository 依赖。

| Workflow 已观察的结果 | 父行锁时取消已提交 | 父 Run | 助手消息 | Workflow 投影 |
|---|---|---|---|---|
| SUCCEEDED | 否 | COMPLETED | 1 | workflow.completed |
| SUCCEEDED | 是 | CANCELLED | 0 | workflow.completed |
| FAILED | 否 | FAILED / WORKFLOW_ERROR | 0 | workflow.failed |
| FAILED | 是 | CANCELLED | 0 | workflow.failed |
| INTERRUPTED | 否 | RUNNING，可恢复 | 0 | workflow.interrupted + run.interrupted |
| INTERRUPTED | 是 | CANCELLED | 0 | workflow.interrupted + run.cancelled |

所有投影携带 version=2、执行 ID/版本/checksum、executionState 与 runState、runTerminalReason、assistantCommitted。保留 state 为执行状态。旧 v1 事件原样重放，消费方以父 run.* 事件判断交付完成。

时序规则：

- 节点异常首次观察时分类；首次观察已存在停止信号时仍可分类为停止，不能宣称一律保留业务失败。
- 已分类的节点 FAILED，不被随后保存期间的取消或期限改写。
- END 已计算并通过控制检查后，晚到期限不撤销执行成功；在父行锁前已持久化的用户取消仍可取消交付。期限是执行截止，不是数据库事务必须在该时刻前提交的保证。
- 已完成 Future 的异常先读取；阻塞中任务继续受取消/期限保护；成功返回仍检查控制条件。
- Workflow 返回后，投影与父终态、助手消息、run.* 事件原子提交，外部不能读到其中一半。Workflow 执行记录本身先行提交，是独立事实，不纳入父事务。
- 已终态 Run 的重复结算不重复写事件/消息。执行前取消或过期不伪造不存在的 workflowRunId。

验证：WorkflowSettlementIntegrationTest（H2）与 WorkflowSettlementPostgresTest（真实隔离 PG）各四种组合；latch 卡住执行返回前和父事务提交前；真实取消 HTTP、SSE Last-Event-ID 重放；WorkflowControlTest/WorkflowEngineTest 确定性取消和期限交错；RunShutdownIntegrationTest 真实关闭路径。

未保证：数据库在执行事实保存后、父结算前故障时可能缺投影；关闭恢复可能启动新的 Workflow 执行；跨实例派发、外部副作用恰好一次、canonical model:N 恢复重放不在本片范围。
