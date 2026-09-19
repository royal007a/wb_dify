# Hify 执行入口

任务状态的唯一真源是 `harness/tasks.json`；当前运行和 checkpoint 在 `harness/state.json`；人类可读摘要由 `harness/progress.md` 自动生成。本文不再维护第二份 checkbox backlog。

```bash
./harness/init.sh
python3 harness/harness.py validate
python3 harness/harness.py check-progress
```

- 当前进度：`harness/progress.md`
- 稳定实施阶段：`docs/IMPLEMENTATION_PLAN.md`
- 执行计划：`exec-plans/`
- 技术债：`tech-debt-tracker.md`
- Harness 制度与操作：`docs/harness/`

新增、调整或完成任务必须修改 `tasks.json` 并重新生成 progress；禁止手工编辑 `progress.md`。
