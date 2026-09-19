# Harness Architecture

```text
tasks.json ──validate──> harness.py <── permissions.yaml
    │                         │
    │                         ├── state.json / checkpoint
    │                         ├── progress.md (generated)
    │                         └── evidence/<task>/<run>/
    │
run-task.sh ──exclusive lock──┬── task command
                             └── verify.sh ── scoped test matrix

exec-plans/active ──success──> completed
                  └─failure──> blocked
```

## 权威数据

- `tasks.json`：唯一任务状态和 backlog；任何 Markdown 不得复制 pending/running/blocked 状态。
- `state.json`：当前运行、基线、checkpoint 和最近验证。
- `permissions.yaml`：操作到风险级别的唯一映射。文件使用 JSON 语法；JSON 是合法 YAML 1.2，可由 Python 标准库读取，避免运行时依赖 PyYAML。
- `task.schema.json`：交换契约；`harness.py validate` 额外检查依赖环、状态一致性、计划路径和权限匹配。
- `progress.md`：纯投影，手改会被 `check-progress` 拒绝。

## 生命周期

`pending|blocked -> running -> completed|blocked`。同一时刻最多一个 running；`run-task.sh` 同时使用目录锁和状态锁。开始时记录 baseline commit，关键阶段写 checkpoint；完成时记录 head、退出码与 verification manifest。

计划文件随状态移动：成功进入 `completed/`，失败进入 `blocked/`。移动是 Git 可恢复写入，不代表业务或外部副作用已回滚。
