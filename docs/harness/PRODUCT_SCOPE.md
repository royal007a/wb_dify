# Harness Product Scope

## 目标

Hify Engineering Harness 让一次工程任务可被另一会话可靠恢复：任务边界、基线 commit、权限、checkpoint、验证结果与证据路径均为机器可读状态，而不是留在聊天上下文。

核心用户流：

1. 在 `tasks.json` 定义一个有验收条件、风险和验证范围的原子任务。
2. 必要时在 `exec-plans/active/` 写任务计划。
3. 通过 `run-task.sh` 独占运行；高风险任务携带审批引用。
4. 命令完成后自动运行验证矩阵，保存证据并迁移任务/计划状态。
5. 新会话从 `state.json`、checkpoint 和 evidence 恢复，不重新扫描全仓猜测进度。

## 非目标

- 不代替 Git、CI、人工 code review 或生产变更审批。
- 不让 Agent 自行扩大任务范围、修改权限策略或批准高风险动作。
- 不保存凭证、生产数据、完整 Prompt 秘密或无限制日志。
- 不把任务失败自动解释为可以回滚外部副作用。
- 不同时运行多个写任务；需要并行时必须使用独立 worktree 和独立状态存储，当前版本不支持。
