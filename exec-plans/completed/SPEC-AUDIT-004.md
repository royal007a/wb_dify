# SPEC-AUDIT-004：完成证据的实文件绑定与历史准入

基线ebee870。只改Harness、测试和规格，不启动Docker/Maven、连接数据库或部署；知识完整性003仍独立blocked，不降低它的验收范围。

1. 先以隔离目录测试复现：缺失/修改日志、摘要为partial或其他invocation、伪造schema降级/追加假历史/清空证据仍可完成或validate。保留红灯。
2. finish completed必须读取本run目录内的真实日志和tests.json并复算SHA，摘要须passed、对应invocation/step且计数和manifest完全一致；缺失、越界路径及符号链接逃逸拒绝。不读取任意外部文件，不新增绕过开关。
3. 把基线已有86条证据记录按taskId/runId/顺序及规范JSON摘要冻结成显式兼容清单；不重写旧verification，不把旧partial/skip变为通过。validate保护历史前缀并检查每一条新completed记录，不能以新末尾或schema缺失隐藏旧失败。
4. finish需要完整日志；validate用于可提交元数据的回读，要求新completed的verification/摘要内容与已记录SHA一致，存在的原始日志也要匹配，但git archive中缺失的未提交日志不冒充已复算。明确这种离线边界，不以portable检查放行finish。
5. 保留现有Python用例，新增正反对照及runner假命令测试；新鲜harness scope通过后交独立review。未新增产品功能、未重跑应用或PG，不代替部署验收。
