# Harness Quality Gates

## 验证矩阵

| Scope | 必须执行 | 说明 |
|---|---|---|
| harness | 状态校验、progress 防漂移、Python 单测、Shell 语法 | 不访问业务数据 |
| backend | 配置Testcontainers后Maven `hify-app -am`，逐类结果且零skip | 任何跳过为partial、退出非0，不以Maven 0掩盖PG未运行 |
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

verification schema v2保存每步command/exitCode/logSha256及Maven逐类tests/executed/failures/errors/skipped。commandResult只代表命令退出；result为passed/partial/failed，partial同样使verify退出非0。缺日志或缺所选Maven步骤的逐类结果为failed。非Maven步骤不编造用例计数，testCoverage可为not-assessed；passed也仅表示所选scope，非产品验收。跨backend/runtime/migration的重复用例不合计成独立测试。
历史schema v1原样保留；可用verification_report.py生成独立test-summary.json，显式注明是现存日志重新计数、不是重跑。
