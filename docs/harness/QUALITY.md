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

## schema v3：新鲜执行与完成状态

- 当前verify通过maven_step.py运行，每步创建全新私有reports目录，不删/读旧target报告。父POM仅在hify.test.reportsDirectory属性存在时启用报告路径配置。原始XML含JVM属性/应用输出，不提交；只提交去正文的逐类摘要、预期集合、XML SHA和命令。
- expected-maven-suites.json是review过的类集合及最少测试记录数；完整backend还要与源文件列表一致。增删测试须同时更新清单。缺类、意外类、同名重复、汇总/用例不一致、非skip少于最小数、失败、错误、flake、fork退出非0均失败；skip记录使partial。禁用容器可能合并方法，skip数不能当精确漏测方法分母。标准输出不参与新执行计数，不能用打印假汇总影响报告。
- finish completed校验当前runId、HEAD、要求的scopes及步骤、schema v3、passed、零skip/失败/flake；拒绝无报告或partial。新完成记录保存verification SHA，validate重查。run-task保留partial原因且不忽略finish失败。
- 旧制度的completed记录不回写历史证据，也不代表旧skip已经补跑；SPEC-AUDIT-001、SPEC-CHAT-LIFECYCLE-003的描述注明partial，完整验收归SPEC-VERIFY-001。validate的历史兼容不构成旧记录已通过新门禁。
- 本制度防误操作/漏测，不是针对能改仓库、伪造XML/manifest的恶意写者的安全边界；不承诺用SHA证明证据来源不可伪造。原始日志缺失时SHA也不能重建日志。
