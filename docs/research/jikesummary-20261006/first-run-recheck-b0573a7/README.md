# 首次 Run 与索引失败的 H2 定向复验

源码固定 `b0573a75d3fb0f361d9f1805e4a7774a4878a96b`，两次开始和结束时工作树均干净。
没有修改产品、测试、等待时限、断言或 mock；增量 Maven，不是 clean 构建。
原失败保留在 `harness/evidence/OBS-CORRELATION-001/OBS-CORRELATION-001-20261007T094455Z-55fb4156/`。

| 本轮范围 | 结果 | 外层耗时 |
| --- | --- | --- |
| 两个原失败方法 | 2/2，零 failure/error/skip | 42.374 秒 |
| 两个完整 H2 测试类 | 41/41，零 failure/error/skip | 38.674 秒 |

两方法为 `KnowledgeAdmissionShutdownTest#resumeKeepsFrozenKReferencesAfterArchiveAndDoesNotRetrieveAgain`
和 `WorkflowKnowledgeReviewTest#indexingMetadataFailurePersistsFailureWithoutDeletingExistingChunks`。
完整类分别为 22 项和 19 项。方法执行已包含原业务断言，不是仅检查 Spring 能启动。
两次是不同执行，不能把 2 和 41 相加当作独立覆盖。

`methods/` 和 `classes/` 各保留原 Maven 日志、逐类 txt、运行命令和退出码、GC/安全点日志、
开始前 vm_stat，以及从原 XML 提取的逐类/逐方法摘要和 XML 哈希。原 XML 留在本机私有目录，
不提交环境属性。SHA256SUMS 对提交材料逐字计算。这是本机观测，不是签名构建证明。

## 能确认和不能确认

- 当前源码在本次两种 H2 运行范围内未复现原失败；完整类运行也未复现同类用例干扰。
- 不能排除原全量运行的跨类交互、资源压力、时序或产品问题。没有控制变量实验，
  不能把变快归因于停止 VM、缓存热身、swap 或其他单一原因。
- 开始前只停止了自己的验证 Colima profile；Dify/default 未停止。本轮未启动容器、
  浏览器或应用服务，没有连接 132 或付费模型。Maven heap 256MiB、测试 heap 768MiB、
  Spring context cache 2，与失败 gate 的配置一致；准确 JVM 参数见 source.json。
- 没有新增生产阶段诊断，不能从这次绿灯定位旧运行卡在提交、排队、检索或终态中的哪一段。
- 尚未重验 PG，也不是 backend 全量或六范围 gate。不修改 OBS blocked 状态，不据此发布。

下一步：结合独立只读分析决定是否需要阶段诊断；补 PG 定向复验，之后才评估完整门禁。
不以单独复跑通过解释或覆盖原红灯。
