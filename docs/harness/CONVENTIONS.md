# Harness Conventions

## 任务

- ID 使用 `<DOMAIN>-NNN`，创建后不得复用。
- 一个任务只有一个可验证结果；不能在同一任务顺带实现无关功能。
- `acceptance` 写可观察结果，不写“完善一下”“优化代码”。
- `dependsOn` 只表达硬依赖；依赖必须完成后才能启动。
- `operation` 必须存在于 `permissions.yaml`，`risk` 必须与其映射一致。
- `tasks.json` 是状态唯一真源；`progress.md`、群消息和计划文档都不是。

## 计划与 checkpoint

- 复杂任务启动前在 `exec-plans/active/<ID>.md` 记录范围、步骤、验收和回滚边界。
- checkpoint 说明“已确认到哪里”和“下一步是什么”，同时保存 HEAD 与 working-tree 状态 hash。
- blocked 必须写具体原因和恢复条件；不得以“太难”作为原因。

## 证据

- 每个 run 独立目录，至少包含 `run.json` 与 `verification.json`。
- JSON manifest 可提交；日志默认不提交。必要日志应先脱敏再转成小型结构化证据。
- 证据声明必须写明 commit、数据集/配置、命令、退出码和未执行项。

## 修改状态

优先使用 `harness.py`/`run-task.sh` 迁移状态。必须人工修复 JSON 时，随后运行：

```bash
python3 harness/harness.py validate
python3 harness/harness.py render-progress
python3 harness/harness.py check-progress
```
