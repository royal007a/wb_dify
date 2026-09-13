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

## 下一阶段

1. 将 Provider 模块按 DemoItem 模板迁移为 DTO → application service → MyBatis-Plus Mapper，消除 Controller 直接返回 Entity。
2. 给 Agent 增加 draft/version/publish，写入时驱逐 `agent-cache`，读取时启用 Cache-Aside。
3. 把 Query Loop 的 remaining deadline 与 cancellation token 传入 LLM HTTP/SSE；补 partial stream、429、5xx、认证失败、断连契约测试。
4. 依据真实压测调整 llmExecutor、runExecutor、连接池、熔断窗口和重试预算。
5. 设计长期记忆最小切片：先做 tenant/user/project bank 隔离、Fact + source evidence、时间覆盖语义；普通静态知识仍走 RAG，不把 Recall/Reflect 默认塞入所有请求。

## 长期记忆的进入条件

- 只有跨会话偏好、历史决策、多跳实体关系或复盘经验成为高频需求时进入实现。
- P0 必须先有数据分级、脱敏、删除/过期、访问审计和 source evidence。
- Recall 首版采用语义 + 关键词 + 时间融合；Graph 和 Reflect 在业务评测证明多跳/冲突判断有收益后再加。
- 评测必须覆盖 Recall Hit/Precision、冲突消解、最终回答成功率、延迟/成本和敏感信息误召回。
