# SPEC-RUN-INPUT-002：PG结构化错误与NUL预检

基线2559cc5。仅原版hify工作区与隔离H2/PG测试，不连接132或hify-cc。

1. 合成本地化PSQLException（23505、结构化constraint字段、中文message、Hibernate constraintName=null）经create失败事务回滚再重放。先证明旧代码500，新代码返回原Run且无额外写入/调度。
2. 使用已存在的PG驱动结构化ServerErrorMessage.getConstraint，不新增第三方版本；PG字段优先于Hibernate报文提取。H2指定索引兼容和无关23505安全500不变。
3. Run message、会话/幂等标识、resume runId/gapIds的NUL在进入数据库前拒绝400。重复gapId仍沿用去重/最多50的既有语义，不宣称该项此前有列宽bug。
4. H2 HTTP/直接service及真实PG HTTP均断言拒绝且无新增Run/消息/事件；合法消息作正向对照。真实PG并发回归继续跑；合成中文报文不等于实际安装中文locale的PG。
5. 代码/契约、期望类计数同步，红绿证据与harness/backend全量门禁后交review；不部署未复核代码。
