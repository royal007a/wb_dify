# Harness Quality Gates

## 验证矩阵

| Scope | 必须执行 | 说明 |
|---|---|---|
| harness | 状态校验、progress 防漂移、Python 单测、Shell 语法 | 不访问业务数据 |
| backend | Maven `hify-app -am` 测试 | 覆盖模块单元/集成测试 |
| migration | Docker 可用、迁移测试、真实 PostgreSQL 用例且 Skipped=0 | Docker 不可用即失败，不静默跳过 |
| runtime | QueryLoop/Plan/Evidence/RunFlow | 控制出口、恢复与终态 |
| eval | Intent 契约、规则、模型解析和 120 条基线 | 当前仅可复跑，指标阈值任务为 `EVAL-001` |
| frontend | TypeScript 检查与生产构建 | 浏览器 E2E 需要已运行环境，另行记录 |

## Definition of Done

任务只有在以下条件全部成立时才可 completed：

1. 验收项可由代码、测试或结构化人工证据逐项回答。
2. 任务命令退出 0，所有 `verifyScopes` 退出 0。
3. migration scope 在真实 PostgreSQL 上执行且没有 skip。
4. 文档中的“已实现”与代码入口、测试和运行证据一致。
5. 没有凭证或敏感业务数据进入日志/evidence。
6. `tasks.json/state.json/progress.md` 一致，计划已移入 completed。

测试未运行、环境不可用和测试跳过都不得写成通过。浏览器 E2E、真实付费 Provider 和生产部署只有存在独立 evidence 时才能声明完成。
