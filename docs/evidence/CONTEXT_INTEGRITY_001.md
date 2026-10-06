# CONTEXT-INTEGRITY-001 验证记录

用户授权实现/验证/部署，第一批仅H1上下文压缩保真；H2-H5仍独立候选。发布另设任务，本文不证明已部署。

## 实际红绿对照

基线898caa7，计划fdd09a1，新增ContextManagerTest七项与QueryLoopTest一项。

命令（backend目录）：`mvn -B -o -pl hify-chat -am -Dtest=ContextManagerTest,QueryLoopTest -Dsurefire.failIfNoSpecifiedTests=false test`。

- 最终红灯fixture：2026-10-06，旧生产实现、同一组测试32项，8 failures / 0 errors / 0 skip，exit 1。外部工具文本用例特意在它之后放旧assistant消息，确保旧压缩器把工具内容放到system，而不是恰好留在末两条。
- 修复后相同命令：32项，0 failures/errors/skip，exit 0。ContextManagerTest 11项，QueryLoopTest 21项。
- 生产路径覆盖ContextManager真实归档/重测和QueryLoop模型零调用；合法短策略正对照调用模型一次并COMPLETED。假ModelClient不证明真实模型遵循策略。
- 原始日志本地`/tmp/hify-context-red-final-20261006.log`和`/tmp/hify-context-green-20261006.log`，无付费模型或远端业务请求。日志不等同可信签名。

覆盖无关键词、多system、短外部指令不升权、当前多工具闭包、无user边界保守处理、策略与当前轮过大拒绝、原历史不变、先归档再压缩。所有保留消息原样、原顺序；不新增system摘要。

## 边界

- 输入已有system消息一律保留，不重新判定其来源；长期记忆投影的来源治理、早期用户偏好持久化仍独立。
- 当前轮过长会被拒绝，不再通过丢掉用户问题/部分工具消息换取模型请求成功。
- 没有新schema/迁移、依赖、模型调用或WRITE权限。
- 完整门禁与既有关停/恢复诊断待补；首轮STRUCTURED失败证据不改写。窄测不是部署准入。
