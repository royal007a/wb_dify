# E1：应用关闭、取消与恢复

范围：单实例先停后启；不改 HTTP 路由、不改已发布能力快照、不做外部副作用回滚。

| 场景 | 可观察结果 | 验证 |
|---|---|---|
| 模型阻塞时关闭应用 | 无 cancelRequestedAt，不写 CANCELLED/FAILED/assistant；Run 留 RUNNING | RunShutdownIntegrationTest，真实 context.close + 真实线程池，阻塞模型夹具 |
| 已计算结果等待最终提交时关闭 | 已计算的终态正常提交；COMPLETED 仅一条助手消息，重启不再调模型 | RunShutdownIntegrationTest 的完成→关闭 latch；RunWorkflowControlTest 三种 Workflow 返回状态 |
| 完整最终模型响应与关闭竞争 | 仍经过取消/预算/FinishGate，可完成；含 tool call 的响应不能开启新工具 | QueryLoopTest；不是允许关闭后继续推理 |
| 重启同一隔离库 | 原 Run checkpoint.restored 后完成，不重复 user message | 同一测试新建第二应用上下文 |
| Workflow KNOWLEDGE 阻塞时关闭 | 旧执行/节点 INTERRUPTED、不执行 END；父 Run 未终态 | 同类独立应用测试，阻塞只读检索夹具 |
| Workflow 父 Run 恢复 | 同一发布版本的新执行 SUCCEEDED；旧 INTERRUPTED 保留 | 同一库第二上下文 |
| 用户先取消再关闭 | 重启前已写 CANCELLED 原因（不能靠 CANCELLED_DURING_RESTART 补写）；下次启动不调模型 | 独立上下文 + 持久取消 |
| 关闭与执行器拒绝竞争 | 不永久写 EXECUTOR_REJECTED，清除本次 owner | RunWorkflowControlTest，确定性模拟执行器交错 |
| 恢复行读取/拒绝终态写入失败 | 单条失败不终止扫描，其余完成 | RunWorkflowControlTest 两个故障点；非真实 DB 宕机演练 |
| 硬停留下旧 Workflow 行 | 旧 RUNNING→INTERRUPTED；启动后新行不变 | 显式孤儿夹具，启动 cutoff 验证；未实际 kill -9 |
| 关闭与 Provider 熔断 | HALF_OPEN 名额归还，无成功/失败样本 | ExecutionShutdownTest，受控阻塞操作 |
| 迁移 | V20 历史保留；V21/V22 向上迁移；合法状态可写、UNKNOWN 拒绝 | WorkflowTerminalMigrationTest + WorkflowTerminalPostgresTest |

`run.interrupted` 为非终态：`{version:1,runId,reason:APPLICATION_SHUTDOWN,recoverable:true}`。它只用于明确被关闭中断的未完成执行，不能仅凭 stopping 标志吞掉已计算终态。用户取消在持久行锁下仍优先。可恢复是调度资格，不等于保证成功；checkpoint、模型响应和工具权限仍可能使恢复失败。

预算修订（2026-10-07，本地受测源码 05ab7a0）：Chat 与 Workflow 都按持久 Run.createdAt 扣减 runTimeout；Chat 的检索和 QueryLoop.run/resume 接收同一个父控制，子控制只能收紧。同一 Run 重启不重新赠送完整时限；H2/PG 的预算内恢复和已到期恢复反例见 CHAT-RETRIEVAL-CONTROL-001 完整六范围证据。显式调用 resume API 会创建新 AgentRun，不能与恢复同一 Run 混称。关闭与期限同时被观察到时控制层仍以挂起优先，持久取消和最终交付另有检查；不宣称所有竞争顺序均已穷举。SPEC-RUN-BUDGET-001 的原清单尚待逐项独立收口，见其计划，不能把 pending 解读为上述旧重置缺陷仍存在。恢复起点使用墙钟差、后续使用单调剩余时长；执行检查是协作式，不保证硬墙钟返回上限，也未部署本轮源码。

边界：单实例内 owner 不是分布式租约；依赖下次启动，尚无定时重扫；关闭最多等待5秒，不保证阻塞驱动及时退出；DB不可用/强制退出可缺中断事件；恢复不保证外部副作用恰好一次。Workflow 与父 Run 的最终取消竞争不在本片验收范围。WorkflowRecovery 整体 UPDATE 失败和最初 Run 列表读取失败均可能阻止启动；按行隔离只覆盖读到列表后的父 Run 调度。子任务 LOST 扫描与父 Run 恢复的监听顺序尚未规定（待验证）；不可将父 Run 先恢复当成已有安全保证。
