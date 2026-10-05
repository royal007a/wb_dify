# WORKFLOW-STRUCTURED-001 实现与验证记录

设计基线 6dc579f，实现 58f7820。首轮完整门禁于 2026-10-05 16:01:29 CST 失败，任务保持 blocked；真实模型样例通过不替代完整门禁。无 132 部署、无 Dify/hify-cc 修改。

## 实际执行的窄测（2026-10-05）

- `WorkflowStructuredOutputTest,WorkflowGraphValidatorTest,WorkflowAggregationTest,WorkflowExternalNodesPolicyTest,WorkflowEngineTest`：76 项，fail/error/skip 均 0，15:16:26 CST 结束。
- 随后新增 Agent 和取消/截止对照，再跑 `WorkflowExternalNodesIntegrationTest,WorkflowStructuredOutputTest`：18+14 项，fail/error/skip 均 0，15:19:43 CST 结束；这是 H2、MockMvc 加本机假 HTTP Server，未调用真实模型。两组有重叠，不相加。
- `npm run typecheck` 通过；Vite 127.0.0.1:5187 上 `management.spec.ts` 4/4，通过受控路由精确核对 schema 请求体，不是线上浏览器。
- 从 7ddff72 导出的隔离源码运行 `LegacyLlmWriterProbeTest`：1/1，15:09:21 CST 结束。固定原文金样位于 `backend/hify-workflow/src/test/resources/workflow/legacy-llm-7ddff72.json`，去掉末尾换行后 SHA256 为 `34667cc85f6c10f31e3f3f9a9b61c024b89f66e3d35c04a6f9717f569d1f3f7e`。探针源码在本目录 fixtures；不是用新 writer 现场生成期望值。

## 正反对照

严格 schema/类型/字段集合、重复键、尾随数据、深度/数字/UTF-8 边界；0/false/空值、精确小数、数组 JSON；旧 LLM 无 schema 原文及 SHA；附加 schema 的输入预算；新数组条件和聚合静态拒绝；未知/非必经字段不放宽。

HTTP fixture：发布冻结后修改草稿，旧版本继续接受而新版本拒绝；格式失败不产生结构化字段、不执行 END、不会追加修复调用；成功和失败的 Agent 使用同一会话，成功增加助手消息，失败不增加；取消和截止优先于迟到的非法 JSON。非法 schema 在写工作流之前拒绝。

## 首轮完整门禁：失败，保留原记录

目录：`harness/evidence/WORKFLOW-STRUCTURED-001/WORKFLOW-STRUCTURED-001-20261005T072732Z-184c341f`。命令：`DOCKER_CONTEXT=colima-hify-verify-20261004 ./harness/run-task.sh WORKFLOW-STRUCTURED-001 -- python3 harness/structured-live-check.py`，最终 exit 1。

- verification schema 3，head 58f7820，invocation `78aba058-7c21-409b-9580-22cca0d440af`，result failed；8 步中 backend-tests exit 1，其他 7 步 exit 0。Harness Python 78 项通过，frontend typecheck/build 通过。
- backend 98 类、704 项全部执行，1 failure、2 errors、0 skipped、0 flakyAttempts。不能写成“704 项通过”。WorkflowStructuredOutputTest 14/14、WorkflowExternalNodesIntegrationTest 18/18 通过，仅证明各自范围。
- RunShutdownIntegrationTest：`startupInterruptsOldWorkflowRowsButNeverNewlyAcceptedExecutions` 超过 45 秒；`computedClarificationCommitsDuringShutdownWithoutRestartingTheLoop` 的 computed latch 10 秒等待未满足。
- HistoryRecoveryPostgresTest：`realRestartReplaysCanonicalOperations(boolean)[2]` 超过 60 秒。
- 这些测试及 QueryLoop/RunApplicationService 在本切片中没有改动，但这不证明失败仅由环境引起。采样到的后续测试主线程在 Spring 类路径扫描，关停日志也有资源回收 InterruptedException；尚未完成根因判定，没有放宽断言、自动重跑或丢弃首轮红灯。
- `failure-summary.json` 记录失败选择器、摘要 SHA 和恢复条件；`source-identity.json` 绑定源树。原始日志/XML 留在本地且不提交，不生成把失败门禁包装为成功的行为报告。

恢复条件：先单独诊断关停/重启等待失败，保留正反对照，再以新的 run 目录重跑完整 harness/backend/frontend；本轮证据不回写。

## 真实模型：独立范围通过

`harness/structured-live-check.py` 在本任务 active 时运行，独占本机 28083、内存 H2、Ollama qwen2.5:0.5b。预先固定 3 个合成输入×2 次，逐次保存，至少有一个可用成功样例才通过 live 命令；失败时必须无 END。此次 6/6 schema 合法并 SUCCEEDED、6/6 合成答案匹配，观察格式失败率 0，耗时 9814/309/239/214/194/260ms。对应 head 58f7820、sourceDiffEmpty=true，应用随后正常关闭。格式失败率和答案匹配数分别报告；6 条样例不是模型质量基准，不能推断其他模型或线上成功率。

未实现/未证明：任意 JSON Schema、约束解码、自动修复、语义事实验证、完整存储性能、零传输重试、生产部署。代码围栏拒绝是显式契约，真实模型格式失败不是悄悄丢弃的样本。

## 独立静态复核与保留 P3

Claude 对 6dc579f..58f7820 只读复核，无 P0/P1/P2；只独立重算金样 SHA，没有构建或运行测试。保留四条 P3：取消/截止端到端用例没有独立突变证明新增解析前后检查（旧 checkModelBudget 可能兜底）；schema 8192 字节上限是防御性限制，未证明可由合法属性组合触发；孤立 UTF-16 代理项没有额外拒绝；ArrayNode 仍是共享可变实例，当前没有修改调用方。不得将这次静态通过外推为完整门禁通过。
