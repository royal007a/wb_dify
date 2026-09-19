# ADR-0011：子任务状态与延迟消费确认

- 状态：Accepted
- 日期：2026-09-19

## 背景

子 Agent 完成并不代表父 Run 已经安全吸收结果。如果子输出一送达就被确认消费，父 Run 在提交前崩溃会同时丢失父结论和可重放的子输出。进程重启还会留下数据库为 RUNNING、实际没有执行者的孤儿任务。

## 决策

1. 子任务使用独立 `QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/LOST` 生命周期，并以 `outputRef + outputDigest` 引用输出，不把大结果复制进父状态。
2. 输出交付单独使用 `NONE → DELIVERED → CLAIMED → CONSUMED`。父 Run 运行中可 claim；只有父 Run 的 COMPLETED CAS 与 assistant message 事务已经提交，才允许 consume。
3. claim 带稳定 token，可安全重取；claim token 不匹配时拒绝确认。
4. 启动时把没有存活执行者的 RUNNING 子任务收敛为 LOST，并显式记录 `RETRY/REPLAN/RETURN_TO_USER`。LOST 不等同于失败或成功，也不自动重放副作用。
5. 当前只提供持久状态协议和 application service，不提供分布式 worker、并行调度或真实子 Agent 执行器。

## 后果

- 父 Run 崩溃前未提交时，claimed 输出仍可恢复；父 Run 成功后启动恢复会补做 consume。
- retrySafe 是调用方对任务副作用的声明；真正接入 write 子任务前仍需 side-effect ledger 与幂等执行键。
- 未来调度器必须使用实体 version 与锁语义，不能绕过本协议直接更新状态。
