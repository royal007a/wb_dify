# CONTEXT-INTEGRITY-001 验证记录

用户授权实现/验证/部署，第一批仅H1上下文压缩保真；H2-H5仍独立候选。发布另设任务，本文不证明已部署。

## 实际红绿对照

基线898caa7，计划fdd09a1，新增ContextManagerTest七项与QueryLoopTest一项。

命令（backend目录）：`mvn -B -o -pl hify-chat -am -Dtest=ContextManagerTest,QueryLoopTest -Dsurefire.failIfNoSpecifiedTests=false test`。

- 最终红灯fixture：2026-10-06，旧生产实现、同一组测试32项，8 failures / 0 errors / 0 skip，exit 1。外部工具文本用例特意在它之后放旧assistant消息，确保旧压缩器把工具内容放到system，而不是恰好留在末两条。
- 修复后相同命令：32项，0 failures/errors/skip，exit 0。ContextManagerTest 11项，QueryLoopTest 21项。
- 生产路径覆盖ContextManager真实归档/重测和QueryLoop模型零调用；合法短策略正对照调用模型一次并COMPLETED。假ModelClient不证明真实模型遵循策略。
- 原始日志本地`/tmp/hify-context-red-final-20261006.log`和`/tmp/hify-context-green-20261006.log`，无付费模型或远端业务请求。日志不等同可信签名。

覆盖无关键词、多system、短外部指令不升权、当前多工具闭包、无user边界保守处理、策略与当前轮过大拒绝、原历史不变、先归档再压缩。所有保留消息原样、原顺序；不新增system摘要。

## 边界

- 输入已有system消息一律保留，不重新判定其来源；长期记忆投影的来源治理、早期用户偏好持久化仍独立。
- 当前轮过长会被拒绝，不再通过丢掉用户问题/部分工具消息换取模型请求成功。
- 没有新schema/迁移、依赖、模型调用或WRITE权限。
- 完整门禁与既有关停/恢复诊断待补；首轮STRUCTURED失败证据不改写。窄测不是部署准入。

## 首轮完整门禁：失败且主动中止后续阶段

被测代码8e855b0，run `CONTEXT-INTEGRITY-001-20261006T032435Z-81dc643c`，命令为 `DOCKER_CONTEXT=colima-hify-verify-20261004 ./harness/run-task.sh CONTEXT-INTEGRITY-001 -- true`。

- harness Python 78项通过。
- migration-postgres实际125项，0 failures / 1 error / 0 skipped / 0 flaky。错误为 `RunShutdownPostgresTest.computedResultIsCommittedOnShutdownWithoutReplayOnPostgres` 超过60秒。中断时日志栈落在第二个Spring context的close、Lettuce资源销毁；这不是根因已经确定的证明。
- 在独立review确认长单轮工具累积的可用性缺口后，03:39Z只终止该run的已核实进程树，未停止Colima、Docker或其他项目。backend阶段刚开始，runtime/eval/frontend未完成；本轮记录为blocked/130，无完整passed manifest。
- 旧失败记录不变；这次不部署。125项摘要为真实本轮新报告，不能用此前9项窄测通过顶替。

独立review：fdd09a1..8e855b0存在单轮多个未达字符阈值的工具结果累积超预算；后续将补按预算逐项归档，原样保留system/user和工具配对。上游RAG及记忆目录本身可能已经是system，本片不承诺修复其来源信任；局部测试名称需要收窄。

## Review补强窄测

新增两个通过生产ContextManager的回归：单user六轮万字结果；最新批次包含两次并行工具调用。修复前同一34项测试出现2 errors（两项均为ContextWindowExceededException），修复后34/34、0 failures/errors/skips。命令与首轮相同，日志为`/tmp/hify-context-rescue-red-20261006.log`和`/tmp/hify-context-rescue-green-20261006.log`。

补强在压缩仍超限时逐项归档较早结果：保留全部消息配对、system/user和最新批次，每步重测且只接受体积减少的替换。两个用例都断言仅归档最早两个结果；六轮用例逐条比对未替换消息，并将归档原文与canonical输入比对。manager层无user超限、可变输入列表不修改和小上下文完整内容断言也已补充；局部防升权测试改名为`localCompactorDoesNotPromoteUnarchivedToolTextToSystemPolicy`。

这仍是窄测，不顶替六scope门禁。首轮PG红灯尚未完成诊断，不具备部署条件。

## 错峰后的关停窄测（不是完整门禁）

2026-10-06 12:08:33北京时间，在759baf7、相同60秒限制和原断言下，`RunShutdownPostgresTest`两项全部执行，0 failures/errors/skips，Maven退出0。独立Colima profile恢复，未改默认profile或Dify，未操作132。命令仍为`mvn -B -o -Dapi.version=1.44 -pl hify-app -am -Dtest=RunShutdownPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test`，沿用验证profile的DOCKER_HOST、Testcontainers覆盖和本机非代理JVM设置。

computed-result场景首次启动2.723s、关停开始2.902s、关停返回3.014s、持久化断言3.085s、第二次启动4.943s、恢复断言4.971s、关闭返回5.048s。对照前次相同场景99.376s，说明这次没有复现慢启动；只凭错峰转绿不能确定前次超时的唯一根因，也不删除前次两项超时记录。

本机日志`/tmp/hify-context-shutdown-window-20261006.log`，SHA256 `58233c8b6ed5788c0d9758a9600cf5ef1678292146bda1a6b60ece13d2612bd5`。接下来重新执行完整六scope门禁；未通过前仍不得部署。

## 最终完整门禁（未部署）

新run：`CONTEXT-INTEGRITY-001-20261006T040856Z-68651cf6`，被测`e5763977dc5b7d1ec09e0b41d9f53aa483a080e4`；invocation `233e5e37-85d3-4f11-bf61-da14be7a6c1a`。2026-10-06T04:21:20Z，harness将任务标记completed；schema3 verification SHA256为`4fdd72874721b1bee80a3f7e4af06ff36c38179198939a9e69f0170c2e5e135f`，六scope全部passed。

| scope | 本次执行 | failures/errors/skips/flaky |
|---|---:|---|
| backend | 714/714 | 全0 |
| migration-postgres | 125/125 | 全0 |
| runtime | 35/35 | 全0 |
| eval | 24/24 | 全0 |

跨scope有重复用例，不合计为独立测试数量。提交的verification记录Harness Python步骤exit=0，但没有结构化用例数；“78项”来自发布方本机本轮harness-python-tests.log的运行摘要，原日志未提交，因此不是archive离线复核已验证的执行数量，也不从源码def数量推算。前端typecheck/build通过；未重新运行浏览器或真实模型。本轮真实PG包括关停用例，旧失败不删除，也不把错峰转绿解释为已唯一确定根因。

从本次新鲜XML导出脱敏method-evidence并生成行为映射：57 pass、0 fail、0 not-run，68条路由、38功能组仅表示具名子场景映射，不宣称所有功能组合通过。命令为`python3 harness/behavior_report.py --evidence-dir harness/evidence/CONTEXT-INTEGRITY-001/CONTEXT-INTEGRITY-001-20261006T040856Z-68651cf6 --export-methods harness/evidence/CONTEXT-INTEGRITY-001/CONTEXT-INTEGRITY-001-20261006T040856Z-68651cf6/method-evidence.json`。离线复算将最后一个参数换为`--method-evidence`读取同文件。

源码树身份与空diff见同run的source-identity.json。原始日志/XML按仓库规则不提交；可移植文件是当次执行的脱敏投影，不是签名或独立重跑。Claude对adc6385的独立探针关闭长单轮P1，剩余P3（复杂度、最新批次合计超限仍拒绝、补充断言）保留。部署仍单独执行，此门禁不代表132已更新，也不代表MCP接入问题已修复。
