# ADR-0014：由评测触发 pgvector 混合召回

- 状态：Accepted
- 日期：2026-09-21

## 背景

ADR-0013 要求先以版本化业务样本验证关键词召回，再决定是否增加向量通道。`recall-eval-v1.jsonl` 的 12 个中文样本覆盖精确词、语义改写、噪声、时序冲突和实体关系；literal lexical Top1 为 50%，主要漏掉“发布/部署、确认/审批、报错/故障”等改写。

## 决策

1. PostgreSQL 启用 pgvector；`history_detail_refs.embedding` 使用 64 维向量并建立 cosine HNSW 索引。新 DetailRef 同事务后写入派生向量，迁移为旧目录回填。
2. 候选并行来自 PostgreSQL FTS 与 pgvector，先统一到 DetailRef，再用加权 RRF 融合；精确词通道权重 1.25，语义通道权重 1.0，随后继续应用类型、时间、实体过滤和确定性排序。
3. 当前 64 维本地字符 n-gram/概念向量仅用于零外部依赖的纵向切片、迁移和评测管线验证。它是可替换 bootstrap adapter，不宣称达到生产 embedding 模型的语义质量。
4. 版本化基线记录 literal lexical Top1 50%、混合 Top1 100%、Precision@3 33.33%；后者低是因为每例只有一个 relevant 且固定取 3。契约指标由集成测试分别验证 Evidence Grounding、冲突处理、摘要忠实度、重复调查率和端到端成功。
5. canonical history 仍是唯一事实源。向量、FTS、RRF 排名和摘要都只能导航；`history.detail` 回读并校验原文 digest 后才能产生 VERIFIED evidence。

## 后果

- 本地和部署 PostgreSQL 镜像必须包含 pgvector 扩展；Flyway V13 在 PostgreSQL 建扩展、回填和 HNSW，在 H2 无操作。
- 召回评测进入 Harness `eval` 矩阵，指标变化必须显式更新版本化数据集与基线，不能只调整阈值。
- 后续接真实 embedding provider 时需记录模型版本、向量维度、回填策略、P95 和单次成本；这属于新的迁移/ADR，不隐式替换。
- Entity/Graph 与 reranker 仍按错误分布触发，不因 pgvector 已存在而提前铺设。
