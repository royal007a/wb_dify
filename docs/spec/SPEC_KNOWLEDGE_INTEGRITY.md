# SPEC-KNOWLEDGE-INTEGRITY-003：索引方言与固定Workflow摘要

本片补两条既有完整性缺口，不新增迁移、公开HTTP路由、模型节点或数据库写权限安全承诺。

## 索引数据库识别

- DocumentIndexingService在索引事务中用JdbcTemplate ConnectionCallback识别数据库，不持有事务连接后再直接向DataSource借第二条连接。回调结束不关闭事务连接，由事务结束统一释放。
- 仅识别已支持的PostgreSQL/H2；元数据读取失败或未知产品为索引失败，绝不因为识别失败走H2分支并标记DONE。持久错误使用固定文案，不包含驱动元数据异常原文。
- 识别发生在删除原分块之前。元数据故障时，文档与索引任务为FAILED，原分块不变。这里的持久化承诺以该事务仍能访问/提交数据库为前提；数据库整体不可用时不承诺仍写入FAILED。
- 正常PG索引必须写embedding列及embedding_text；H2写embedding_text。该规则不冻结向量算法、排序或跨版本的召回结果。

## Agent固定Workflow摘要

- Chat经WorkflowCapabilityPort.executePinned传入不可变AgentVersion保存的workflowVersionId及checksum；缺少checksum或与实际版本不一致时，以WorkflowDefinitionException/CONFLICT拒绝。父Run为FAILED/WORKFLOW_ERROR，不持久化助手回复，不创建新的workflow_run或节点行。
- WorkflowEngine只加载一次版本，在同一对象上比较固定checksum，再验证原始DSL的SHA、图及知识快照，之后才开始执行。不能分成“预查摘要”和“重新加载执行”的两次独立读取。
- 直接`POST /workflow-versions/{id}/runs`没有Agent快照，仍只依据该版本自身DSL/checksum与既有图准入；不能从直接试跑成功推断旧Agent会话也能执行。
- 不回写旧快照、不自动改绑；提示重新发布Workflow、再发布Agent、并新建会话。原始DSL自身损坏、取消/关闭、工作流其他异常继续按各自既有契约处理。
- 本检测能发现仅Workflow行被改写（即使行内checksum同步重算）；不能防有数据库写权限的人同时篡改Agent快照、缓存、其他运行事实。checksum不是签名。workflow.started仍早于执行校验，这项由GRAPH-003跟踪，本片不虚构workflow.failed执行投影。

## 验收证据等级

- DocumentIndexingServiceTest：真实Spring事务管理器加模拟JDBC连接，检查借用/关闭恰好一次；模拟元数据异常/未知产品不得写分块或成功；正常H2/PG SQL分支有正向对照。它不是连接池压力测试。
- WorkflowKnowledgeReviewTest/WorkflowKnowledgeReviewPostgresTest：正常上传真实索引、PG embedding非空；在真实事务连接上注入元数据SQLException，确认FAILED持久化、原分块完整保留。故障由spy回调注入，不等于真实断网/连接池枯竭测试。
- 同组HTTP/异步Chat先成功运行固定版本，再改DSL并重算行内checksum；直接版本运行作为自洽正例，旧会话必须WORKFLOW_ERROR且无新增执行行/节点/助手；还原夹具后旧会话可重新运行。仅隔离合成数据，不改生产记录。
- WorkflowEngineTest检查固定摘要空/错拒绝、同一版本只加载一次，以及匹配固定摘要仍不能绕过原始DSL校验。既有取消/结算测试仅调整到新的固定执行入口，原断言保留。

完整门禁结果和未执行项见`../evidence/SPEC_KNOWLEDGE_INTEGRITY.md`。没有132部署证据时不称已上线。
