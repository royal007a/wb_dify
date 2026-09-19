# Execution Plans

- `active/`：仅当前 running 任务的执行计划。
- `completed/`：已通过验证的计划与验收叙事。
- `blocked/`：失败原因、现有 checkpoint 和恢复条件。

任务状态以 `harness/tasks.json` 为准；目录只是与任务关联的可读执行叙事。`run-task.sh` 在完成或阻塞时移动 active 计划并同步 `planPath`。
