# Hify

Hify 是一个面向内部 20-50 人、小规模本地部署的 AI Agent 平台。它借鉴 Dify 的产品对象和运行边界，但不复制 Dify 源码、前端资产或大规模平台复杂度。

## 当前状态

当前已有可部署的初版纵向闭环：Maven 多模块后端、Vue 3 + TypeScript + Vite + Element Plus 管理台、Spring MVC API/SSE、持久化 Conversation/Message/Run/Event、Mock/OpenAI-compatible client、受预算约束的 Query Loop、时间/计算器工具、Flyway 迁移、PostgreSQL 16 和 Nginx。2026-09-12 已在本机通过开发态一键启动、真实 HTTP/Vite 代理、浏览器渲染、SSE replay、数据库迁移和 PostgreSQL 并发测试。

这仍不是完整 MVP：尚未完成真实 token streaming、对阻塞模型调用的强制取消、精确 token/cost 计量、Agent version/publish、MCP、RAG、Workflow、认证和生产密钥管理。精确边界与证据见 `docs/CURRENT_STATE.md`、`docs/BACKEND_FOUNDATION.md` 和 `docs/INITIAL_RELEASE.md`。

开发态一键启动：运行 `./start.sh`，脚本会准备本地 PostgreSQL、构建并启动后端，等待 `/api/v1/health` 返回 200 后启动前端，入口为 `http://localhost:5173`；停止运行 `./stop.sh`。也可使用 `make start|stop|restart|build|clean|package`。Redis 默认关闭且不是启动强依赖；设置 `HIFY_REDIS_ENABLED=true` 时才会检查它。

容器部署：运行 `./deploy/up.sh`，然后访问 `http://localhost:8088`；停止服务运行 `./deploy/down.sh`。默认数据库口令只用于本机开发环境，不得用于生产。

## MVP 一句话

用户可以配置模型、创建 Agent、绑定安全工具，在浏览器中发起流式多轮对话，并能追踪每次模型调用、工具调用和终止原因。

## 一期范围

- 模型提供商与模型配置
- Agent 草稿、版本与发布
- 持久化会话、消息和 Agent Run
- 有上限、可取消、可观测的 Query Loop
- 内置工具和 MCP 客户端工具
- Vue 3 管理台与对话 Playground
- TXT/Markdown 简版 RAG（产品 P1，实施 Phase 4）
- JSON 线性/条件工作流（产品 P1，实施 Phase 5）

明确不做：多租户、复杂 RBAC、计费、插件市场、模型微调、可视化工作流、分布式任务、子 Agent 和公开 WebApp 发布。

## 文档入口

- 总规格：`docs/SPEC.md`
- 当前实现边界：`docs/CURRENT_STATE.md`
- 初版交付与验证证据：`docs/INITIAL_RELEASE.md`
- 后端地基交付与验证：`docs/BACKEND_FOUNDATION.md`
- 前端与一键启动交付：`docs/FRONTEND_FOUNDATION.md`
- 前端设计系统与公共组件：`docs/FRONTEND_DESIGN_SYSTEM.md`
- 业务基础组件交付：`docs/FOUNDATION_COMPONENTS.md`
- 当前执行路线：`plan.md`
- 四份课程材料总结：`docs/PDF_READING_SUMMARY.md`
- 架构与运行时：`docs/ARCHITECTURE.md`
- API：`docs/API.md`
- 数据模型：`docs/DATA_MODEL.md`
- 工程规范：`docs/ENGINEERING.md`
- 安全：`docs/SECURITY.md`
- 部署与演进：`docs/OPERATIONS.md`
- 实施计划：`docs/IMPLEMENTATION_PLAN.md`
- Phase 0 原型对齐：`docs/PHASE_0_ALIGNMENT.md`
- 技术决策：`docs/decisions/`

## 设计原则

1. 先交付完整纵向闭环，再扩功能宽度。
2. 智能交给模型；schema、权限、预算、超时和终态由代码强制。
3. 单体部署、模块隔离；触发条件出现前不拆微服务。
4. PostgreSQL 是事实源，Redis 只是可丢失的加速与事件层。
5. 任何“已实现”结论必须由入口、测试和运行证据支持。
