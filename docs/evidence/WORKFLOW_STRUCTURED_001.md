# WORKFLOW-STRUCTURED-001 实现与验证记录

设计基线 6dc579f；当前是实现提交前的窄测记录，完整门禁和真实模型结果尚未取得。无 132 部署、无 Dify/hify-cc 修改。

## 实际执行的窄测（2026-10-05）

- `WorkflowStructuredOutputTest,WorkflowGraphValidatorTest,WorkflowAggregationTest,WorkflowExternalNodesPolicyTest,WorkflowEngineTest`：76 项，fail/error/skip 均 0，15:16:26 CST 结束。
- 随后新增 Agent 和取消/截止对照，再跑 `WorkflowExternalNodesIntegrationTest,WorkflowStructuredOutputTest`：18+14 项，fail/error/skip 均 0，15:19:43 CST 结束；这是 H2、MockMvc 加本机假 HTTP Server，未调用真实模型。两组有重叠，不相加。
- `npm run typecheck` 通过；Vite 127.0.0.1:5187 上 `management.spec.ts` 4/4，通过受控路由精确核对 schema 请求体，不是线上浏览器。
- 从 7ddff72 导出的隔离源码运行 `LegacyLlmWriterProbeTest`：1/1，15:09:21 CST 结束。固定原文金样位于 `backend/hify-workflow/src/test/resources/workflow/legacy-llm-7ddff72.json`，去掉末尾换行后 SHA256 为 `34667cc85f6c10f31e3f3f9a9b61c024b89f66e3d35c04a6f9717f569d1f3f7e`。探针源码在本目录 fixtures；不是用新 writer 现场生成期望值。

## 正反对照

严格 schema/类型/字段集合、重复键、尾随数据、深度/数字/UTF-8 边界；0/false/空值、精确小数、数组 JSON；旧 LLM 无 schema 原文及 SHA；附加 schema 的输入预算；新数组条件和聚合静态拒绝；未知/非必经字段不放宽。

HTTP fixture：发布冻结后修改草稿，旧版本继续接受而新版本拒绝；格式失败不产生结构化字段、不执行 END、不会追加修复调用；成功和失败的 Agent 使用同一会话，成功增加助手消息，失败不增加；取消和截止优先于迟到的非法 JSON。非法 schema 在写工作流之前拒绝。

## 真实模型与完整门禁（待执行）

`harness/structured-live-check.py` 只在本任务 active 时运行，独占本机 28083、内存 H2、Ollama qwen2.5:0.5b。预先固定 3 个合成输入×2 次，逐次保存，至少有一个可用成功样例才通过 live 命令；所有失败无 END。格式失败率和答案匹配数分别报告，不能把脚本 passed 理解成所有样本通过。后续由同一个原子任务执行 harness/backend/frontend 门禁。

未实现/未证明：任意 JSON Schema、约束解码、自动修复、语义事实验证、完整存储性能、零传输重试、生产部署。代码围栏拒绝是显式契约，真实模型格式失败不是悄悄丢弃的样本。
