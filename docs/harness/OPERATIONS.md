# Harness Operations

## 初始化与检查

```bash
./harness/init.sh
./harness/verify.sh --scope harness
```

`init.sh` 只创建 Harness 目录、校验状态并重生成 progress，不修改业务数据。

## 执行任务

```bash
./harness/run-task.sh EVAL-001 -- ./scripts/implement-eval-gate.sh
./harness/run-task.sh --approval-ref 'lark:om_xxx' DEPLOY-001 -- ./deploy/release.sh
```

运行前工作树必须干净。执行器创建独占锁、记录 baseline、运行命令、按任务 `verifyScopes` 验证，并将结果写入 evidence。高风险任务没有审批引用会被拒绝。

## 恢复

1. 查看 `state.json.currentTaskId` 与 `evidencePath`。
2. 查看任务 checkpoint、`run.json` 和日志；确认外部系统真实状态。
3. 若进程已不存在但状态仍 running，人工将原因记录后用 `harness.py finish <ID> blocked ...` 收敛；不得直接删除状态。
4. 修复后从 blocked 重新启动，会创建新的 run/evidence，不覆盖历史。

`.run-lock` 只防并发进程。只有确认没有存活执行器、并已核对 `state.json` 后，才可清理陈旧锁目录。

## 验证和发布

- 日常使用 `--scope auto`；任务验收使用显式 `verifyScopes`。
- migration scope 要求 Docker 和真实 PostgreSQL，跳过即失败。
- 发布前先生成备份和审批引用；发布后记录健康检查、浏览器 smoke 和回滚点。
- `verification.json` 是本次运行证据；日志不是长期状态源。
