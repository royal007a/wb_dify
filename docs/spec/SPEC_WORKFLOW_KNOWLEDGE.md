# Workflow 发布语料与历史引用契约

范围：SPEC-WORKFLOW-KNOWLEDGE-001，审计 A03 与归档引用回读矛盾。只支持已有确定性 KNOWLEDGE 节点，不增加 LLM/Tool 节点或新的检索服务。

## 发布

- 草稿仍选择 knowledgeBaseId/query/topK/outputVariable。客户端创建或更新草稿不能指定 `knowledgeSnapshot`，提交该保留字段返回 HTTP 400。
- 发布事务对每个知识库冻结一次 corpus，按知识库 ID 排序取得行锁，避免同一批冻结采用不一致的加锁顺序。冻结版本分配在该知识库行锁内串行化；只包含本次读取已提交的活跃分块，不等待仍在索引中的文档。
- 发布 DSL 的每个 KNOWLEDGE config 增加 `knowledgeSnapshot={corpusVersionId,manifestDigest,revisionNo,chunkCount}`，并连同 knowledgeBaseId 一起进入 Workflow checksum。草稿行不写入这个字段；同库多个节点复用同一快照。
- 语料头、成员、WorkflowVersion 和 publishedVersionId 属于同一个发布事务。后续节点冻结失败时，前面新创建的语料及发布记录一起回滚。
- 新发布读取当时的新语料；旧 WorkflowVersion 以及引用它的旧 AgentVersion/Conversation 不改变。Agent 重新发布之后的新会话才跟随新的 WorkflowVersion。

## 执行与兼容

- 执行前检查所有 KNOWLEDGE 节点的快照形状。旧版本未冻结或字段非法时，直接 HTTP 409，提示重新发布 Workflow；不创建执行记录、不就地修补 DSL/checksum。纯 TEMPLATE/CONDITION 等旧图按原逻辑执行。
- KNOWLEDGE 节点通过公开 KnowledgeRetrievalPort.searchSnapshot 读取冻结版本，不调用可变库 search。比较 corpus ID、knowledgeBaseId、revision、manifest、count，然后校验成员总数、成员摘要清单和 canonical content 的 SHA-256。
- PostgreSQL 读取校验和检索在同一个 REPEATABLE_READ 事务里；缺成员、错摘要、错知识库、原文被替换都失败，不静默丢掉异常分块后继续 END。rank 仍使用现有 pgvector/关键词策略，没有改变相关性阈值。
- 普通 searchRevision 也验证冻结清单和原文。摘要是完整性检测，不是防御有数据库写权限者的签名；向量仍是派生索引，本片没有加入向量内容签名。
- 取消、期限、关闭控制仍包围冻结检索；本片不宣称 PostgreSQL JDBC 能可靠响应线程中断。

## 归档与引用

- 归档只从活跃检索/管理视图隐藏，不是硬删除或敏感数据擦除。旧冻结版本仍可检索已归档原文，停用/归档知识库不撤销历史版本的读取能力。
- requireCanonicalChunk 对活跃分块保持既有读取语义，但会重新计算原文 SHA-256；对归档分块必须提供正确 expectedDigest，且该 chunk/digest 已存在于冻结 corpus 成员中。没有历史冻结引用、缺摘要或摘要不符均拒绝。
- 若需要撤销历史访问/数据擦除，应另设显式策略，不得将“归档”误报成撤权。本片未增加 HTTP 引用回读接口或前端引用弹窗；canonical 回读验收调用现有 application port。

## 验收与边界

WorkflowKnowledgeIntegrationTest 和同组 PostgreSQL 用例覆盖：发布→归档→新增文档→新发布；旧/新会话分别保留七天/十五天政策；历史引用回读；草稿快照注入拒绝；旧 DSL 拒绝；发布半失败回滚；缺成员/错成员摘要/错 manifest/错 base/原文损坏；空库冻结。

空库合法冻结并返回零候选，这不代表可充分回答。无关命中、空命中、Claim/FinishGate 仍由 SPEC-KNOWLEDGE-FINISH-001 处理，不借本片宣称完成。

完整性验证当前逐次扫描冻结语料，时间与语料大小线性相关，尚无大库性能证据。无新增迁移；修复了入库、归档与冻结 JDBC 参数直接传 Instant 在 PG 驱动上失败的问题，改为 Timestamp。不改现有表/旧发布快照；回滚代码会重新失去本契约保护，应暂停知识 Workflow 执行后再决定回退。
