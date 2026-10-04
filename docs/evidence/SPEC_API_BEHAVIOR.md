# 管理回读与取消/幂等：方法级行为补测

任务`SPEC-API-BEHAVIOR-001`，基线`ab46ec5`（生产代码同`a9a7357`），证据根`harness/evidence/SPEC-API-BEHAVIOR-001/SPEC-API-BEHAVIOR-001-20261004T000019Z-62dd48a5/`。

本切片没有生产逻辑变更。使用完整Spring上下文、DispatcherServlet/Advice和隔离H2的MockMvc；不是standalone，也不是真实网络/浏览器。测试数据仅为合成文档、Mock模型及START→END确定性图；不访问共享数据库、132或真实凭据。

| 测试方法 | 实际断言 | 未证明的边界 |
|---|---|---|
| ManagementReadbackIntegrationTest.knowledgeCrudAndDocumentPaginationHavePositiveAndArchivedControls | 按唯一keyword列到刚创建的KB、分页字段、非法page400；文档列表先空后有该上传文档且索引DONE；更新配置逐字段回读；停用拒上传409；归档后KB和文档404且列表消失 | 并发索引与归档竞态；PG快照 |
| workflowReadbackAndArchiveRetainTheExactPublishedVersion | Workflow列表含创建对象；分页夹值1/100；版本列表与发布返回一致、详情checksum/节点数；运行SUCCEEDED及正文；run详情与执行返回一致；归档列表消失但原版本详情保持相等 | 网络断连、并发发布或崩溃后投影 |
| agentWorkflowUnbindChangesOnlyDraftAndNewPublication | 草稿绑定存在；DELETE解绑后草稿为null；旧AgentVersion仍持有原WorkflowVersion；新发布不再绑定，旧版本仍不变 | 未再次经Chat执行该旧版本；旧版本执行由已有Agent/Workflow集成用例另证 |
| legacyConversationAndToolCatalogReturnActualFields | 旧会话回读id/title/messages；旧工具列表恰为current_time/calculator且schema为对象 | 非演示工具、真实供应商 |
| RunSubmissionIdentityTest.sameKeyWithDifferentResumeIsConflictWithoutAdditionalWork | 已提交的相同key增加不同resume返回40901；原请求重放同Run；Run/message/event计数不增；executor只接一次 | 并发相同key竞争 |
| repeatedAndTerminalCancellationAreIdempotentAndPreCancelDoesNoModelWork | 捕获生产dispatch的Runnable，在真实取消事务提交后才执行；两次取消均202且时间戳相同、请求事件仅1；执行后CANCELLED/原因CANCELLED；终态再取消202且完整返回相等、无新增事件；仅1条user消息，无model/tool事件 | 正在阻塞的HTTP取消、跨实例取消、真实SSE重放；这些属于其他专项测试 |

窄测命令：`mvn -B -pl hify-app -am -Dtest=ManagementReadbackIntegrationTest,RunSubmissionIdentityTest -Dsurefire.failIfNoSpecifiedTests=false test`。2026-10-04 08:02（Asia/Shanghai）退出0，9项通过（本轮新增6项、原有3项），0失败/错误/跳过。完整门禁见本任务verification，不以窄测代替全量。

08:05完成本任务harness/backend门禁，代码`b16af24`、schema 3 / passed：后端80类532项执行，失败/错误/跳过/flake均0；Harness Python 31项通过。逐类XML SHA、期望集合及命令见`backend-tests.tests.json`和`verification.json`，窄测日志SHA见`focused-summary.json`。新增六项没有替换/降低旧测试分母。

首轮同命令9项中3失败，均为本次测试作者的错误假设，保留在`focused.log`：把真实`/cancellations`写成`/cancel`；把Workflow分页夹值误写成400；把草稿binding误当成已冻结的版本ID。对照Controller/草稿与发布契约后修正用例（`focused-corrected.log`），没有为迎合错误断言改生产代码，也不把这三个失败计成修复了三个产品缺陷。

13条管理路由补候选指针；取消路由补明确语义与直接断言；Workflow分页文档注明与Agent/KB验证边界不同。候选指针仍不代表全部路由故障排列已通过。后续全接口/F01–F38汇总和真实浏览器/132部署由SPEC-VERIFY-001与SPEC-DEPLOY-001另行记录。
