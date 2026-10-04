# SPEC-INPUT-HYGIENE-003：读路径NUL准入

基线2a9ca62；延续001独立复核登记，不以写路径完成替代读路径验证。只改本地源码/规格和隔离测试，不连接132、真实Provider/MCP或共享数据库。

1. 清点retrieval-tests对应search/searchSnapshot、memory/search对应HistoryRecallService、intent-decisions对应application入口的文本参数。拒绝实际U+0000为400/40000且不回显，在trim/查库/模型工厂之前检查；保持现有空白/长度/时间窗校验，不做隐式替换。
2. H2和真实PG先跑反例；检索使用已索引合成文档并断言合法查询命中本次文档，memory使用实际Mock Run的可见历史并断言命中本次内容；意图正常请求能运行，非法agentId/input不能进入模型工厂。
3. HTTP与直接service同时验证。Frozen知识检索也必须经同一准入，不能只补调试接口。读请求不产生Run/消息/索引任务写入，不把空结果当成有效负例。
4. 只对JSON/body和公开service文本参数作本片承诺；真实Tomcat对%00路径拒绝没有网络实测，不用MockMvc推导。其他仅路径ID的管理方法、模型/MCP输出、null列表通用契约不混入本片。
5. 窄测、完整backend/harness零skip，更新期望类/方法和行为映射，记录源码身份及脱敏可移植报告，交独立复核。没有132部署证据则明确尚未上线。
