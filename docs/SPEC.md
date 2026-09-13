# Hify 产品与系统规格

## 1. 产品定义

Hify 是一个可在单机或内网服务器部署的轻量 AI Agent 平台，服务 20-50 人内部团队。核心价值不是“再做一个聊天框”，而是让模型、Agent、工具、知识、运行和审计成为清晰、可治理的产品对象。

### 核心用户流程

1. 管理员添加 OpenAI-compatible 模型提供商，通过环境变量或密钥引用保存凭证，并测试连通性。
2. 创建 Agent，选择模型，配置 system prompt、参数、工具和运行预算。
3. 发布不可变 Agent 版本。
4. 用户在 Playground 新建会话并发送消息。
5. 系统持久化消息和 Run，流式返回模型内容；需要工具时执行结构化 Query Loop。
6. 用户能查看 Run 的状态、模型/工具步骤、耗时、token、错误与最终终止原因。

### 三问裁剪

| 问题 | Hify 的回答 |
|---|---|
| 没有它产品还成立吗？ | Provider、Agent、Chat、Tool/MCP、Console、Run 观测缺一不可 |
| 做到什么程度够用？ | 单工作区、少量 provider、串行工具、简版 RAG、JSON Workflow、单实例部署 |
| 能否一句话说清？ | 配模型、建 Agent、绑工具、流式对话并可追踪运行全过程 |

### MVP 范围

**P0 核心闭环**

- Provider/Model：CRUD、启停、连通性测试；原始 API Key 不入库。
- Agent：草稿编辑、校验、不可变版本、发布、停用。
- Chat：多轮会话、持久消息、SSE、取消、重连 replay。
- Query Loop：结构化 tool call、预算、超时、明确终态、完整审计事件。
- Tool：`current_time`、`calculator`，加一个只读 MCP Server 作为真实集成。
- Console：Provider、Agent、工具、Playground、Run 详情五个页面。

**P1 价值增强（产品优先级，不等同于实施 Phase 编号）**

- Knowledge（实施 Phase 4）：TXT/Markdown、固定/递归分块、embedding、HNSW 检索、引用溯源。
- Workflow（实施 Phase 5）：版本化 JSON DSL，只支持 Start、LLM、Tool、Knowledge、Condition、End。

**非目标**

- 多租户、复杂 RBAC、SSO、计费、配额商业化。
- 插件打包/市场、模型微调、运营大盘。
- 可视化 Workflow 画布、任意 DAG、分布式调度。
- 子 Agent、并行工具、浏览器自动化、任意 shell/代码执行。
- 公网 WebApp 和开放注册。

## 2. 成功指标与门禁

| 类型 | 指标 |
|---|---|
| 首次价值 | 新环境 15 分钟内可用 mock provider 完成一次工具型对话 |
| 正确性 | 每个 Run 恰有一个终态；tool call/result id 完整配对 |
| 可靠性 | 取消后 2 秒内停止继续调用；任何 Run 在 overall timeout 后收敛 |
| 流式体验 | 首 token P50 < 3s（不含供应商异常）；heartbeat 防止空闲断链 |
| 安全 | 凭证不入 DB/日志/事件；危险 URL 和内网元数据地址被拒绝 |
| 可恢复 | 浏览器断线可按 event id 重放；应用重启后运行中 Run 被明确收敛为失败/中断 |

## 3. 设计约束

- 一人优先：单仓、一个后端进程、一个前端、一个主数据库；不引入消息队列和服务网格。
- 生产形态优先 Docker Compose；Kubernetes 只在多副本/统一平台运维成为真实需求后评估。
- PostgreSQL 16 + pgvector 合并业务与向量事实源，避免 MySQL + PostgreSQL 双数据库运维和一致性成本。
- Redis 可用于限流、短缓存和 SSE 事件 replay，但不是消息、Run 或任务的唯一事实源。
- 当前后端已完成 Maven 多模块、MVC/SSE、PostgreSQL/Flyway、短事务 Run API、并发幂等和终态 CAS；直接 Entity API、JPA→MyBatis-Plus 过渡、真实流式和完整权限仍未达到本规格，继续按 `CURRENT_STATE.md` 与 ADR 收敛。

## 4. 文档与事实规则

源码和运行证据 > 自动化测试 > ADR > 本规格 > README。规划能力必须标为“待实现”，不能出现在 README 的“已包含”列表中。
