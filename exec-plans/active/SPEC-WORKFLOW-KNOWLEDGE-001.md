# Workflow immutable knowledge capability

针对审计A03，不扩充节点或编排能力，不引入外部索引服务。

1. 写发布→更改/归档知识文档→旧图执行的回归反例，同时测旧图引用和新图结果。
2. 发布时通过公开KnowledgeRetrievalPort冻结语料，生成服务端快照字段，写入发布DSL/checksum；草稿资源选择与发布资源快照分离。
3. 执行只读取冻结版本并验证引用/manifest。已发布但未冻结的KNOWLEDGE图拒绝并要求重新发布，不原地重写历史；旧纯TEMPLATE/CONDITION图照常运行。
4. 已归档的canonical chunk只在历史发布语料引用存在且摘要相符时可读取；归档不是硬删除/数据擦除，契约明示。
5. 通过HTTP/H2和真实PostgreSQL回归后关闭原子任务；知识失败/空命中与FinishGate的A01仍单独交付。
