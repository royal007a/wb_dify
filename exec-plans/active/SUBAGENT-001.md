# SUBAGENT-001 — 子 Agent 状态与延迟消费确认

## Outcome

子任务有独立生命周期和输出引用；父 Run 只有成功提交后才确认消费；重启可收敛失联任务。

## Scope / Non-goals

- In：状态机、持久化、claim/ack、lost recovery、契约测试。
- Out：并行 worktree、通用任务调度器、分布式 worker。

## Steps and checkpoints

1. 状态机与消费语义测试。
2. 新增表、Repository 和 application service。
3. 接入父 Run 终态确认与启动恢复。
4. 跑 migration/runtime/backend 验证。

## Acceptance and evidence

- Verify scopes：backend,migration,runtime。
- Evidence：由 Harness 生命周期记录。

## Rollback / recovery boundary

仅新增子任务状态；lost 表示执行者消失，不自动重放外部副作用。
