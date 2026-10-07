# 来源权限：旧 writer 金样采集准备

2026-10-07 首次采集已完成，1 项用例通过，19 份原始文件已冻结；详见
`docs/research/jikesummary-20261006/context-authority-goldens-05ab7a0/README.md`。
本目录不是产品实现，也不是新来源门禁已经通过的证据。
只将 `LegacyContextGoldenCaptureTest.java` 注入 05ab7a0 的干净 backend archive，
不放进当前 `src/test` 自动发现范围。只能在协调的测试窗口执行
`python3 harness/fixtures/context-authority/capture.py`；每次建新 scratch、不覆盖旧输出。

采集要求：

1. 固定完整 baseline `05ab7a06f485a5d800009dedbbe566508bd9e775`，用 git archive
   导出 backend 到新 scratch；保留 archive SHA 和逐个受跟踪源码文件 SHA。
2. 仅注入该 Java 采集器，记录它的 SHA，不修改旧 src/main、POM 或已有测试。
3. 运行 hify-app -am 指定这一个类，使用新 reports 目录、独立 H2 库和新的输出目录；
   不连 Docker、生产或真实 Provider。Maven/测试日志、退出码和 JUnit 结果全部保留。
4. 原输出不得重新格式化：文件内不额外加换行。生成后逐文件计算 SHA256，固定进 Git。
5. 当前实现之后只能读取这些固定 expected；生成器不能作为普通测试中的 expected 工厂。

采集器用真实 Spring ObjectMapper、生产 CommittedHistoryWriter、checkpoint 写/读方法、
indexer 和 detail reader；反射只用于访问已有私有 checkpoint/知识拼装方法，不另抄序列化。
plain 金样含 system/user/assistant tool_calls/tool 四种形态，RAG 金样含旧 system 资料
前缀及 knowledge claim。知识历史不应被 raw memory 索引，所以不会冒造对应 det_。

输入全部是合成资料。旧 `ExecutionPlan.initial`、`StepAttempt.start` 和知识初始 state
会产生 UUID/时间：固定的是第一次成功采集的原文字节，不宣称重新生成每次都字节相同。
H2 的生成键/投影时间不作为协议金样；checkpoint 持久的三份 JSON 分别保存，不把整个
ExecutionCheckpoint 序列化当成数据库格式。工具 recovery state 是合成场景，不证明完整
QueryLoop 曾执行，也不证明引用的 chunk 实际存在；来源证明与恢复门禁另走集成用例。

已采集只证明旧 writer 自身和原字节来源；待验证新实现对固定金样的 reader/replay/detail
兼容、完整来源门禁及其余验收。不得把旧基线的 1 项通过冒充新防护验收。
