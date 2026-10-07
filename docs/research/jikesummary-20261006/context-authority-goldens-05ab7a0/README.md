# 05ab7a0 旧 writer 原文金样（准备证据）

2026-10-07 13:39:37Z–13:40:07Z，独立 backend archive / H2 采集，Maven exit 0。
`LegacyContextGoldenCaptureTest` 1 项、0 failures/errors/skips，67 类等其他产品测试没有运行。
采集脚本为 `harness/fixtures/context-authority/capture.py`，Java 采集器在同目录；两者 SHA
记录在 capture.json，并对照当前提交文件重算一致。只注入一个采集类，不改旧产品代码。

## 来源与固定文件

- baseline `05ab7a06f485a5d800009dedbbe566508bd9e775`，git archive 只导出 backend。
- archive SHA256 `794bd5c2d9446391b4ad6621e56faece0850a40920b06b9c6866a03a687b69c8`。
- source-sha256.json 记录 488 个 archive 原文件采集前的 SHA；采集后由驱动逐个重算，
  只记录 baselineFilesUnchanged 布尔结果，没有保存采集后完整清单；该检查不检测新增文件。
- Maven 日志 SHA256 `c24950869cc501467435a2cf995e0cf506be5b2084dfc28fef4aa431bf6e5c75`；
  日志及 JUnit 文本报告随本目录保存。原始 XML 留在 scratch，仅记录 SHA，不提交 JVM 环境属性。
- 19 份原文固定在 `backend/hify-app/src/test/resources/context-authority-05ab7a0/`，
  逐文件 SHA 在 capture.json 的 goldenSha256；复制后全部重算一致，不额外补换行或格式化。

## 确实执行的链路

1. Spring 原 ObjectMapper、真实 CommittedHistoryWriter/JPA/H2 写入并回读 model:1、tool:golden-call，
   校验 canonical 摘要、tool recovery 绑定摘要、原 prefix replay。
2. 通过反射调用旧 RAS 的 persistCheckpoint/restoreCheckpoint，比较恢复对象；保存实际
   messages_json、plan_json、context_json。不是将整个 ExecutionCheckpoint record 输出冒充 DB 格式。
3. 普通形态有 4 个真实 det_，实际 CanonicalDetailReader 回读并校验 JSON 摘要。
4. 知识形态使用旧 knowledgeContext 组装器及 initialState，先持久 checkpoint；raw memory
   的旧防护使 indexer 没有创建 det_。没有为知识资料伪造可公开回读的原始 memory。

## 不能据此宣布的结论

- 这是合成序列化/旧 reader 采集，不是来源门禁修复，任务仍 pending。不存在新实现通过的结论。
- 没有实际检索/发布语料、真实模型或工具调用；citation 输入不是“数据库里确实有该 chunk”的证明。
  tool recovery 是合法合成结构，不证明整个 QueryLoop 已执行；知识来源核实集成仍待实现。
- UUID/部分时间由旧工厂产生，固定的是本次产物；重新采集可能不同，不宣称跨次生成字节可复现。
- 只覆盖这些代表性形态，不是所有 JSON 嵌套值、所有历史版本或所有 checkpoint 元数据的穷举。
- 无 PG、完整六范围或部署。SHA 绑定文件，不是独立可信执行证明；新 writer/reader 必须另读这些
  固定 expected 验证，不得在测试中调用生成器后把输出当 expected。

没有后台服务/VM；Maven 已退出。mymacclaude 对提交 blob 独立只读复核无 P1/P2：
19 份文件、488 条来源 SHA、archive 和采集器摘要一致；未复跑采集。复核不替代新实现验收。
