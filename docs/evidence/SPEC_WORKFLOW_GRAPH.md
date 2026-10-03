# Workflow 图和变量门禁

任务 `SPEC-WORKFLOW-GRAPH-001`，基线 `0b425b8`；针对审计A04，不修改数据库结构、旧发布版本或部署。

## 红灯

命令：`mvn -B -f backend/pom.xml -pl hify-workflow -am -Dtest=WorkflowGraphValidatorTest,WorkflowExecutionContextTest,WorkflowEngineTest -Dsurefire.failIfNoSpecifiedTests=false test`。

新增24项，旧实现19项失败、0错误、0跳过，日志 `/tmp/hify-workflow-graph-red.log`。包括分支非必经变量、死路、多出边、END出边、未知变量、未闭合模板、旧版本未经校验即执行、自定义START变量无法解析，以及把用户文本再次展开的错误。首轮修复相同24项全部通过，日志 `/tmp/hify-workflow-graph-green.log`；这一步还不能算HTTP验收。

追加条件表达式反例：配置 `{{start.userMessage}} contains refund`，用户输入 `refund contains refund`；旧实现先替换再找运算符，返回false，实际应为true。定向命令 `-Dtest=WorkflowEngineTest#conditionTreatsUserSuppliedOperatorsAsData` 1项失败，日志 `/tmp/hify-workflow-condition-red.log`。修复为先识别DSL运算符，再替换两个操作数；不再把用户原文拼进解析错误。

## 契约与验证范围

- 使用拓扑顺序和前驱dominator交集确定严格必经上游。同一对节点多条边按独立边保留，但入度和dominator按前驱节点去重。
- 单次模板插值，运行缺值fail closed，不对替换结果再次求值。自定义START key与已声明outputVariable在编译校验和运行时一致。
- 非条件节点只能有一条出边；END无出边，所有节点可达且无环；因此不会静默丢弃非条件分支或接受图中的死路。
- 执行前对存储DSL重新校验，旧非法版本不执行、不改写；不是自动修复旧配置。
- HTTP回归新增：创建/更新拒绝分支汇合错误变量、发布有效汇合图可运行、旧非法版本运行拒绝且快照不变、自定义START和字面用户占位符通过完整发布执行链。

最终命令和实际结果以Harness本任务runner为准；完成前不把HTTP集成测试写成通过。这里不覆盖A02取消、A03知识语料冻结、A07事件事务，仍需独立回归和部署验收。
