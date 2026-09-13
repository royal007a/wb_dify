# Hify 后端地基交付记录

日期：2026-09-12。执行顺序：地基 → 公共框架 → 业务模块边界 → 验收。

## 1. Maven 地基

父工程 `backend/pom.xml` 最初声明 9 个产品/装配子模块：`hify-common`、`hify-provider`、`hify-tool`、`hify-mcp`、`hify-agent`、`hify-chat`、`hify-knowledge`、`hify-workflow`、`hify-app`。后续增加非产品域的 `hify-demo` 作为 MyBatis-Plus CRUD 参考切片，当前共 10 个子模块。Spring Boot、MyBatis-Plus、Resilience4j、OkHttp 版本均由 parent/BOM 统一管理；子模块不重复声明版本。

依赖主链为：`app -> chat -> agent/mcp/tool/provider -> common`。`knowledge/workflow/mcp` 尚未进入对应产品 Phase，只保留 module marker 与标准目录，禁止把空壳视为已实现。

## 2. hify-common

- `Result<T>`：仅 `code/message/data`，提供 `ok/fail`。
- `PageResult<T>`：在 Result 上增加 `total/page/size`。
- `ErrorCode` + `BizException`：错误码、默认文案和 HTTP 状态统一定义，允许业务异常覆盖消息。
- `GlobalExceptionHandler`：业务、校验、冲突、404 和兜底异常全部通过 `Result.fail(ErrorCode)` 返回；禁止硬编码错误码。
- `MybatisPlusConfig`：PostgreSQL 分页插件；`AuditMetaObjectHandler` 自动填充 `createTime/updateTime`；逻辑删除值在配置中统一。
- `RedisConfig` + `RedisUtil`：String key、JSON value，提供 get/set/delete/expire；默认关闭，Redis 故障不影响核心链路。

现有 JPA Repository 不在本次脚手架任务中一次性重写。MyBatis-Plus 是新增持久化的目标地基，旧实现按模块和测试逐步迁移。

## 3. 业务模块

provider、agent、chat、tool、mcp、knowledge、workflow 均建立 `controller/service/service/impl/mapper/entity/dto/config` 标准目录与 module marker。已有可运行代码迁入对应模块，未到阶段的模块不生成推测性 CRUD。

## 4. 验收证据

```text
Maven reactor: parent + 9 modules，clean test/package 通过
当时的自动化 checkpoint：12 tests passed（common 2、结构/依赖 2、tool 2、query-loop 5、H2 API 1）。后续基础组件阶段的当前数字见 `FOUNDATION_COMPONENTS.md`，不要把本历史快照当作最新总数。
PostgreSQL Testcontainers：8 路同 key 创建收敛到 1 个 Run、仅 1 条 user message；8 路终态 CAS 仅 1 个赢家
fat jar: hify-app/target/hify-app-0.1.0-SNAPSHOT.jar
容器入口：java -jar /app/app.jar
GET /actuator/health: HTTP 200, UP
POST /api/chat: HTTP 404（旧长事务入口已移除）
Mock Run: COMPLETED，17 * 23 = 391，终态事件数 = 1
```

Testcontainers 在标准 Docker socket 环境直接执行；本机使用 Colima，需要把 `DOCKER_HOST` 指向 Colima socket，并为 Docker 29 设置兼容 API 版本。
