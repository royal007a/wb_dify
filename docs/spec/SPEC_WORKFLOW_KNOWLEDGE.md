# Workflow 发布语料与历史引用契约

范围：SPEC-WORKFLOW-KNOWLEDGE-001，审计 A03 与归档引用回读矛盾。只支持已有确定性 KNOWLEDGE 节点，不增加 LLM/Tool 节点或新的检索服务。

## 发布

- 草稿仍选择 knowledgeBaseId/query/topK/outputVariable。客户端创建或更新草稿不能指定 `knowledgeSnapshot`，提交该保留字段返回 HTTP 400。
- 发布事务对每个知识库冻结一次 corpus，按知识库 ID 排序取得行锁，避免同一批冻结采用不一致的加锁顺序。冻结版本分配在该知识库行锁内串行化；只包含本次读取已提交的活跃分块，不等待仍在索引中的文档。
- 发布 DSL 的每个 KNOWLEDGE config 增加 `knowledgeSnapshot={corpusVersionId,manifestDigest,revisionNo,chunkCount}`，并连同 knowledgeBaseId 一起进入 Workflow checksum。草稿行不写入这个字段；同库多个节点复用同一快照。
- SPEC-WORKFLOW-KNOWLEDGE-002 补充：顶层服务端发布 envelope 增加 `publication.knowledgeSnapshotFormat=1`。它不在 WorkflowDraftRequest DTO 中，旧 writer 也不会序列化客户端传来的顶层字段。因此旧 config 即使曾由客户端填入一个真实 corpus ID，也不能冒充新 writer 的发布产物。标记与整个 DSL 一起进入 checksum；它不是签名，不能防有数据库写权限者同时改 DSL、标记和 checksum。
- 语料头、成员、WorkflowVersion 和 publishedVersionId 属于同一个发布事务。后续节点冻结失败时，前面新创建的语料及发布记录一起回滚。
- 新发布读取当时的新语料；旧 WorkflowVersion 以及引用它的旧 AgentVersion/Conversation 不改变。Agent 重新发布之后的新会话才跟随新的 WorkflowVersion。
- Agent 的直接知识绑定同样按 base ID 冻结，不能按检索 priority 获取行锁；最终绑定仍保留原 priority 与顺序。Workflow 绑定只读取已经发布的能力，不在 Agent 发布时重新冻结其知识。

## 执行与兼容

- 执行前重新计算整个 DSL 的 checksum，并检查 KNOWLEDGE 发布 envelope 与快照形状；Agent 绑定与发布获取单个 Workflow 能力时也执行同一校验，旧图不再能新发布进 Agent。草稿列表的批量摘要只是展示当前版本信息，不是执行授权。
- 旧知识版本未冻结、缺少服务端 envelope（包括 60e74c8 的过渡格式）或字段非法时，直接 Workflow HTTP 409，不创建执行记录、不就地修补 DSL/checksum。必须先重新发布 Workflow，再重新发布 Agent，并创建新会话；只发布 Workflow 不会改变旧 AgentVersion 或旧会话。旧会话保留原版本并拒绝执行，不自动换绑。纯 TEMPLATE/CONDITION 等旧图只要 checksum/图合法则照常运行。Chat 的执行前异常归类仍属 SPEC-WORKFLOW-RECOVERY-001，不能把直接接口 409 外推为父 Run 错误码。
- KNOWLEDGE 节点通过公开 KnowledgeRetrievalPort.searchSnapshot 读取冻结版本，不调用可变库 search。比较 corpus ID、knowledgeBaseId、revision、manifest、count，然后校验成员总数、成员摘要清单和 canonical content 的 SHA-256。
- PostgreSQL 读取校验和检索在同一个 REPEATABLE_READ 事务里；缺成员、错摘要、错知识库、原文被替换都失败，不静默丢掉异常分块后继续 END。rank 仍使用现有 pgvector/关键词策略，没有改变相关性阈值。
- 普通 searchRevision 也验证冻结清单和原文。摘要是完整性检测，不是防御有数据库写权限者的签名；向量仍是派生索引，本片没有加入向量内容签名。跨次查询之间重建 embedding/升级算法可能改变排序和 topK，不能把“冻结语料原文/成员”称为“永久固定检索结果”。
- 数据库产品判断通过 JdbcTemplate ConnectionCallback 复用事务连接，不在持有检索连接时额外借连接；元数据访问失败直接失败，不静默退回 H2 排序。
- 取消、期限、关闭控制仍包围冻结检索；本片不宣称 PostgreSQL JDBC 能可靠响应线程中断。

## 归档与引用

- 归档只从活跃检索/管理视图隐藏，不是硬删除或敏感数据擦除。旧冻结版本仍可检索已归档原文，停用/归档知识库不撤销历史版本的读取能力。
- requireCanonicalChunk 对活跃分块保持既有读取语义，但会重新计算原文 SHA-256；对归档分块必须提供正确 expectedDigest，且该 chunk/digest 已存在于冻结 corpus 成员中。没有历史冻结引用、缺摘要或摘要不符均拒绝。
- 若需要撤销历史访问/数据擦除，应另设显式策略，不得将“归档”误报成撤权。本片未增加 HTTP 引用回读接口或前端引用弹窗；canonical 回读验收调用现有 application port。

## 验收与边界

WorkflowKnowledgeIntegrationTest 和同组 PostgreSQL 用例覆盖：发布→归档→新增文档→新发布；旧/新会话分别保留七天/十五天政策；历史引用回读；草稿快照注入拒绝；旧 DSL 拒绝；发布半失败回滚；缺成员/错成员摘要/错 manifest/错 base/原文损坏；空库冻结。

WorkflowKnowledgeReviewTest/WorkflowKnowledgeReviewPostgresTest 另覆盖：旧有效 checksum 加真实 corpus 但无服务端 envelope 仍拒绝；Agent 发布前拒绝；DSL/checksum 不一致拒绝；Agent/Workflow 相反业务优先级下并发发布，PG 观测真实锁等待；第二连接在清单读取后提交原文修改，本轮仍返回已校验旧原文，下一轮拒绝损坏内容；真实连接隔离级别为 REPEATABLE_READ；类型判断复用事务连接。H2 排序用内存候选，只证明隔离级别被设置；PG 排序再次查库，才证明了交错时的快照隔离效果。测试不证明向量列被永久冻结。

空库合法冻结并返回零候选，这不代表可充分回答。无关命中、空命中、Claim/FinishGate 仍由 SPEC-KNOWLEDGE-FINISH-001 处理，不借本片宣称完成。

完整性验证当前逐次扫描冻结语料，时间与语料大小线性相关，尚无大库性能证据。无新增迁移；修复了入库、归档与冻结 JDBC 参数直接传 Instant 在 PG 驱动上失败的问题，改为 Timestamp。不改现有表/旧发布快照；回滚代码会重新失去本契约保护，应暂停知识 Workflow 执行后再决定回退。
