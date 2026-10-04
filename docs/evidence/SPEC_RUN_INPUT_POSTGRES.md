# Run输入PG本地化与NUL（SPEC-RUN-INPUT-002）

基线2559cc5，计划d94e42c，红灯9d9cfb8；工作区/隔离数据库，不访问132、hify-cc、真实密钥。

旧代码窄测13项2失败：合成PG23505协议字段含uq_run_idempotency，报文中文而Hibernate名称为null，应该返回已提交Run的200却500；NUL消息应该400却在H2被接受202。修复后首次候选仍有一个测试夹具错误：重放时对Spring Data抽象查询调用callRealMethod，MockitoException。改成显式“首次miss、随后读已提交winner”的返回桩，后续窄测14项全过；没有把夹具错误写成产品缺陷。

实现：沿cause链优先读取PSQLException的SQLState与ServerErrorMessage.constraint，存在PG诊断时其缺字段/非23505/其他约束都不被Hibernate名称覆盖；只有非PG链才走原H2/Hibernate兼容。PG驱动已是应用runtime依赖，本次让chat模块编译期引用同一BOM版本，没有增加新的运行时产品或服务。

Run创建message/conversationId/key及resume.runId/gapIds先拒绝U+0000，固定40000，不回显输入。ResumeRequest去重与50个上限未改变。合成本地化重放还断言没有额外Run/user消息/事件/executor；PG结构化字段和Hibernate名称矛盾有三个负向变体。H2同时检查HTTP和service。真实PG测试有驱动拒绝NUL的对照，再验证HTTP400/无Run消息，合法请求完成作正向对照。

本地化是合成ServerErrorMessage协议字段测试，**不是安装中文lc_messages后的真实PG实验**。全量门禁将另记真实PG当前locale并发/NUL测试，不以英文PG并发冒充中文locale证据。未改前端、schema或部署。

证据根：`harness/evidence/SPEC-RUN-INPUT-002/SPEC-RUN-INPUT-002-20261004T013652Z-a0e3089e/`；最终门禁待补录。
