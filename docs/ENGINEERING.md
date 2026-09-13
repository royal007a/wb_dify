# Hify 工程规范

## 1. 代码组织

课程中的 Controller/Service/Mapper 是最小可用三层。Hify 增加 `application` 与 `domain` 的分离，不是为了套 DDD 名词，而是避免把事务、预算、取消传播等用例编排，与 Run 状态机、权限判定等稳定业务规则全部堆进一个 Service。若某模块没有独立领域规则，可以保持薄 domain，禁止为“分层完整”制造空壳类。

- Java package 根为 `com.hify.<module>`；公开 port/DTO 放 `api`，其余默认模块内部。
- Controller 只做鉴权、Bean Validation、HTTP/SSE 映射和错误翻译。
- Application Service 定义用例与事务；Domain 不依赖 Spring、数据库或供应商 SDK。
- Infrastructure 实现 Repository、模型、MCP、对象存储和 Redis adapter。
- Entity 不直接作为 API 响应；跨模块 DTO 不暴露 JPA/MyBatis 类型。
- `common` 禁止放业务 Entity、万能 Utils 或跨域 Service。

## 2. 接口与兼容

- REST `/api/v1`；资源复数；动作有明确语义，不用一个 `/execute` 承载所有行为。
- API、SSE event、Workflow DSL 和 Agent version 都有独立 schema version。
- consumer 必须容忍新增字段；删除/改义需要版本和迁移窗口。
- 错误码稳定、可机读；日志保留根因，客户端只见脱敏说明与 request id。

## 3. 外部调用

- 所有模型/MCP/HTTP 调用显式设置 connect、read-idle、overall timeout。
- provider 独立 bulkhead/circuit breaker；重试按错误类别和幂等性判断。
- cancellation token 从 HTTP Run 一直传到模型流和工具。
- 记录 provider/model、latency、status、token/usage，不记录凭证和完整敏感内容。
- 测试必须覆盖 partial stream、429、5xx、认证错误、慢响应、断连和 malformed tool call。

## 4. 数据库

- 使用 Flyway/Liquibase 新增迁移，不修改已发布迁移。
- 唯一性、幂等、终态约束由数据库兜底，不只在代码层预检查。
- 组合索引按等值、范围、排序和实际 query plan 设计；不机械把软删列放所有索引首位。
- 大列表使用游标分页；后台小表可 offset，但设最大窗口。
- SQL/Repository 测试使用真实 PostgreSQL Testcontainers；H2 不作为兼容性证明。

## 5. 测试金字塔

| 层级 | 必测内容 |
|---|---|
| Domain unit | 状态机、预算、权限、错误分类、版本规则 |
| Module integration | PostgreSQL 约束/迁移、Repository、事务、恢复扫描 |
| Adapter contract | Provider 流、tool call/result 配对、MCP schema/错误映射 |
| API | 鉴权、校验、幂等、错误码、SSE replay/cancel |
| E2E | mock provider 完整工具型对话、真实只读 MCP、RAG 引用 |
| Fault | provider timeout/429、Redis down、应用重启、工具卡死、客户端断线 |

## 6. 行为指令

- 先阅读目标模块设计和 ADR，再改代码。
- 只改当前任务声明路径；需要跨域时先写影响和兼容方案。
- 不引入新框架或基础设施，除非现有方案无法满足可测需求且 ADR 已批准。
- 不把 mock test 当真实供应商/MCP/数据库兼容证明。
- 改完运行最小相关测试，并留下命令、版本、退出码和结果。

## 7. 规范如何演进

新规则必须能追溯到架构决策、缺陷、事故或 Agent 反复跑偏。仅出现一次的操作先留在执行计划；相同流程稳定重复至少三次，再考虑提炼成 Skill。
