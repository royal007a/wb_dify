# E1：应用关闭、取消与恢复

范围：单实例先停后启；不改 HTTP 路由、不改已发布能力快照、不做外部副作用回滚。

| 场景 | 可观察结果 | 验证 |
|---|---|---|
| 模型阻塞时关闭应用 | 无 cancelRequestedAt，不写 CANCELLED/FAILED/assistant；Run 留 RUNNING | RunShutdownIntegrationTest，真实 context.close + 真实线程池，阻塞模型夹具 |
| 重启同一隔离库 | 原 Run checkpoint.restored 后完成，不重复 user message | 同一测试新建第二应用上下文 |
| Workflow KNOWLEDGE 阻塞时关闭 | 旧执行/节点 INTERRUPTED、不执行 END；父 Run 未终态 | 同类独立应用测试，阻塞只读检索夹具 |
| Workflow 父 Run 恢复 | 同一发布版本的新执行 SUCCEEDED；旧 INTERRUPTED 保留 | 同一库第二上下文 |
| 用户先取消再关闭 | CANCELLED 优先；下次启动不调模型 | 独立上下文 + 持久取消 |
| 关闭与执行器拒绝竞争 | 不永久写 EXECUTOR_REJECTED，清除本次 owner | RunWorkflowControlTest，确定性模拟执行器交错 |
| 恢复行读取/拒绝终态写入失败 | 单条失败不终止扫描，其余完成 | RunWorkflowControlTest 两个故障点；非真实 DB 宕机演练 |
| 硬停留下旧 Workflow 行 | 旧 RUNNING→INTERRUPTED；启动后新行不变 | 显式孤儿夹具，启动 cutoff 验证；未实际 kill -9 |
| 关闭与 Provider 熔断 | HALF_OPEN 名额归还，无成功/失败样本 | ExecutionShutdownTest，受控阻塞操作 |
| 迁移 | V20 历史保留；V21/V22 向上迁移；合法状态可写、UNKNOWN 拒绝 | WorkflowTerminalMigrationTest + WorkflowTerminalPostgresTest |

`run.interrupted` 为非终态：`{version:1,runId,reason:APPLICATION_SHUTDOWN,recoverable:true}`。可恢复是调度资格，不等于保证成功；Run 预算、checkpoint、模型响应和工具权限仍可能使恢复失败。

边界：单实例内 owner 不是分布式租约；依赖下次启动，尚无定时重扫；关闭最多等待5秒，不保证阻塞驱动及时退出；DB不可用/强制退出可缺中断事件；恢复不保证外部副作用恰好一次。Workflow 与父 Run 的最终取消竞争不在本片验收范围。
