# ADR-0013：分层上下文与迭代式历史召回

- 状态：Accepted
- 日期：2026-09-20

## 背景

把完整历史持续塞进模型会耗尽窗口，把全部历史只替换成摘要又会丢失关键细节。异步索引还存在刚写入但尚未可检索的窗口。模型需要先知道“有什么”，再在证据不足时精确取回原文，而不是由系统预先注入大量噪声。

## 决策

1. 模型视图保留最近 K 轮原文（默认 3）和 system 指令；更早内容只注入结构化 ContextSummary 与 Detail Catalog。刚发生的交互因此不依赖派生索引即可使用。
2. 提供只读工具 `history.search` 与 `history.detail`。search 支持关键词、类型、时间、实体和 top-k 过滤，结果只是 UNVERIFIED navigation；detail 按 ref 回读 canonical history 并校验 digest，才产生 VERIFIED evidence。
3. search 会打开“需要 canonical detail”的 blocking Gap；detail 验证成功后关闭该 Gap。FinishGate 不允许只凭摘要、目录或 search 结果完成。
4. 历史召回有独立 calls/token/latency 预算，并复用 ExecutionContextState 的语义指纹检测重复调查；连续无新增 canonical evidence 时转 CLARIFY，不能机械重搜。
5. P0 使用可移植的关键词/时间/类型/实体候选与确定性排序。版本化 12 例召回集显示 literal lexical Top1 仅 50%，错误集中在中文语义改写；因此按 ADR-0014 启用 pgvector + 加权 RRF。多跳错误成为主要来源前仍不引入图数据库和 reranker。

## 后果

- ContextManager 仍只产生模型输入，不进入工具控制或修改 canonical history。
- ToolRuntime 通过受能力快照约束的扩展机制装配历史工具，执行前继续校验 run/attempt lease 和取消状态。
- 搜索索引可重建、可替换；最终回答的历史依据始终能回到 source revision/message index。
- 模型多一次或两次工具调用会增加延迟，所以必须同时评测端到端成功率、P95 和额外 token。
