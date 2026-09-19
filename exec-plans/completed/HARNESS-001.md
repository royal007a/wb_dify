# HARNESS-001 — 建立机器状态工程 Harness

## Outcome

以 `tasks.json` 为唯一任务状态，建立权限、原子执行、checkpoint、范围验证、证据和生成式 progress 闭环。

## Scope

- 新增 `harness/`、`exec-plans/`、`docs/harness/` 和技术债入口。
- 不改变 Hify 业务 API、数据库或线上服务。

## Acceptance

- Harness 状态与权限可由无第三方 Python 依赖校验。
- 同时最多一个运行任务，高风险任务需要审批引用。
- progress 手工漂移会失败。
- 五类代码范围有明确验证矩阵，migration 不允许静默跳过 PostgreSQL。

## Evidence

Bootstrap 验证记录在 `harness/evidence/HARNESS-001/bootstrap/`；后续所有任务由 `run-task.sh` 自动生成 run evidence。
