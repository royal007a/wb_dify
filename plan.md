# Hify 执行计划

本文件记录当前可执行路线；稳定产品范围、架构和硬规则仍以 `docs/` 与 `AGENTS.md` 为准。

## 已完成

- [x] 制度/规格/架构/ADR/执行文档体系
- [x] Maven 多模块后端、统一响应/异常、PostgreSQL + Flyway、Query Loop 纵向闭环
- [x] Vue 3 + TypeScript + Vite + Element Plus、统一 axios、三页路由、健康联通
- [x] `start.sh` / `stop.sh` / Makefile 一键开发闭环
- [x] 业务基础组件：BaseEntity、PageHelper、校验、时间、缓存、线程池
- [x] DemoItem MyBatis-Plus CRUD 参考切片
- [x] LLM HTTP/SSE 客户端、错误分类、provider 级熔断与分类重试、请求日志
- [x] 前端设计系统、响应式管理台布局、五个公共组件和 Provider mock 验收页
- [x] IntentDecision 四出口契约、两层 Intent Router、预览 API 和 120 条中文 rule-only 评测基线
- [x] Replan P0-P2：Plan/Step/Attempt/Checkpoint/ReplanDecision、确定性 read-only 修复、事件、持久化取消、阻塞 HTTP 取消传播和启动恢复
- [x] 受控 TAO P0-P2：六出口 ContinuationDecision、Claim/Evidence/Gap、FinishGate、有限 Retry、no-progress、版本化 checkpoint 快照、失败恢复事件和 NEEDS_INPUT 子 Run 恢复协议
- [x] Provider 纵向切片：四类协议、MyBatis-Plus 聚合、credentialRef 鉴权、模型目录、独立健康状态、分页 CRUD/连接测试和真实 Console 对接
- [x] Agent 纵向切片：草稿 CRUD、模型校验、不可变版本发布、Conversation/Run 版本钉住、digest 与真实 Console
- [x] Agent 管理闭环：归档语义、独立工具绑定 API、草稿/版本绑定表、发布工具快照、名称唯一约束、批量列表与精确缓存边界
- [x] Agent Console 验收闭环：动态 Tool Catalog、归档确认、工具/参数/时间列、精确草稿发布状态、异常/缓存/PostgreSQL 并发测试与端到端场景
- [x] 原生模型流：OpenAI/compatible、Anthropic、Gemini SSE delta、工具参数重组、Run 事件投影和取消/deadline
- [x] 模块交付 Skill：四问理解、决策、分层执行、验证、SDD 回写与 split commit

## 下一阶段

1. 补原生流式 429、5xx、认证失败、半途断流和客户端断开故障注入，并记录首 token/P95/usage/cost。
2. 在允许 Provider 访问内网前补 DNS 解析后校验、redirect 再校验和 allow-list；接入 Vault/Secret Manager 时只扩展 CredentialResolver，不改表结构。
3. 依据真实压测调整 llmExecutor、runExecutor、连接池、熔断窗口和重试预算。
4. 设计长期记忆最小切片：先做 tenant/user/project bank 隔离、Fact + source evidence、时间覆盖语义；普通静态知识仍走 RAG，不把 Recall/Reflect 默认塞入所有请求。
5. 用当前配置模型跑 Intent Router 离线基线；补结构化输出失败率、P95、token/成本，并以 shadow event 验证后再决定是否接管 Run。
6. 引入第一个真实 write 工具前，先完成 planDigest 确认 token、side-effect ledger、幂等执行和显式 compensation；禁止通用数据库回滚。
7. 只有多步骤业务流进入主链路后再做 Workflow 级显式分支；统一候选排序、双层 TAO、子 Agent 与阶段/全局回滚必须由独立评测和 ADR 触发。

## 长期记忆的进入条件

- 只有跨会话偏好、历史决策、多跳实体关系或复盘经验成为高频需求时进入实现。
- P0 必须先有数据分级、脱敏、删除/过期、访问审计和 source evidence。
- Recall 首版采用语义 + 关键词 + 时间融合；Graph 和 Reflect 在业务评测证明多跳/冲突判断有收益后再加。
- 评测必须覆盖 Recall Hit/Precision、冲突消解、最终回答成功率、延迟/成本和敏感信息误召回。
