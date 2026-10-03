# Hify 可执行验收规格

范围：原版 `~/hify`，不包含独立课程实现 `~/code/hify-cc`。源码基线 `973257c`；本轮审计任务 `SPEC-AUDIT-001`，后续行为验证/修复与部署由 `tasks.json` 中独立任务承接。本文是验收要求，不是完成声明。

## 入口与证据等级

- [HTTP_API.md](HTTP_API.md)：全部显式 `/api` 方法/路径、输入 DTO、成功响应、核心边界、候选测试。
- [http-api.json](http-api.json)：人工维护的接口契约；`ApiContractInventoryTest` 对 Spring 实际注册集合做双向相等校验，不能只检查文档里的接口存在。
- [FUNCTION_TESTS.md](FUNCTION_TESTS.md)：跨接口、页面、Runtime、安全、恢复、评测和部署验收矩阵。
- [AUDIT_FINDINGS.md](AUDIT_FINDINGS.md)：可定位的差距与风险。实际任务状态只看 `harness/tasks.json`。
- [SPEC_RUN_SHUTDOWN.md](SPEC_RUN_SHUTDOWN.md)：应用关闭、用户取消、Workflow 中断与单实例重启恢复契约。
- [SPEC_WORKFLOW_SETTLEMENT.md](SPEC_WORKFLOW_SETTLEMENT.md)：Workflow 执行事实与父 Run 交付结果分开记录的 v2 投影。
- [SPEC_HISTORY_RECOVERY.md](SPEC_HISTORY_RECOVERY.md)：已提交模型/工具操作重放、V23 迁移与旧工具历史的兼容边界。
- [SPEC_WORKFLOW_KNOWLEDGE.md](SPEC_WORKFLOW_KNOWLEDGE.md)：Workflow 发布语料、冻结清单完整性、归档历史引用与旧 DSL 兼容边界。
- [SPEC_KNOWLEDGE_FINISH.md](SPEC_KNOWLEDGE_FINISH.md)：知识空/失败的拒答、候选与最终引用来源校验、恢复映射；不宣称语义蕴含验证。
- 本轮新鲜结果单独记在 `docs/evidence/SPEC_AUDIT.md` 和 Harness run manifest；旧 evidence 仅历史参考。

“候选测试存在”不等于“该端点已完整测试”，“路由一致”不等于“67 个接口行为通过”，“H2/Mock 通过”不等于“真实 PostgreSQL/外部模型通过”。缺失、跳过、外部依赖不足必须单列，不能计入成功分母。

## 通用协议（当前实际契约）

1. 管理 API 使用 `Result<T>={code,message,data}`，成功 code=200。分页为 `PageResult<T>`，data 是列表，total/page/size 在顶层；请求为 page/pageSize，不是 cursor。
2. Run、Conversation、Intent、Memory 与旧 `/api` 读接口返回原始资源，不强制解包 data。SSE 返回 `text/event-stream`。客户端必须按接口选用相应处理器。
3. `Idempotency-Key` 仅创建 Run 时强制。其余 POST/PUT 不宣称幂等键支持。首次 Run 为 HTTP202；同 key 同语义重放200；不同语义409/40901。
4. 业务错误 HTTP 状态与 Result.code 对应：40000、40100、40300、40400、40500、40600、40900、40901、41500、50000。协议错误也返回 JSON Result；缺header/参数、类型错误不回显拒绝值。Provider/Tool 的分类错误是运行事件/检查结果语义，不是另一套 HTTP 数字枚举。
5. Instant 时间按 UTC ISO 格式；DemoItem 的 LocalDateTime 为无时区 ISO 本地日期时间。ID 大多为 UUID 字符串；DemoItem 是 Long，不承诺 UUIDv7/ULID。
6. 管理台无登录/RBAC/租户隔离。数据归属检查（例如 memory 同会话）不等于用户权限。只能在受信网络入口使用，不宣称公网安全产品。
7. Provider 只存 env/system 引用。MCP 另有 write-only TOKEN：AES-GCM 加密、服务端独立主密钥；页面不回填。Token 保留/替换/清除和历史 snapshot 的影响见 ADR-0020。
8. 框架 `/error`、Actuator、Nginx 路径前缀属于运维矩阵；隐式 HEAD/OPTIONS 不计入 67 个显式接口。新增显式映射必须增规格与测试，不靠生成器自动“接受”代码变化。

## 复现与安全

```sh
python3 harness/render-api-spec.py --check
cd backend
mvn -pl hify-app -am -Dtest=ApiContractInventoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

后续完整门禁：`./harness/verify.sh --scope all`。这会运行单测/集成、PostgreSQL、Runtime、Eval、类型检查/构建，**不自动运行浏览器，也不自动调用真实付费模型**。浏览器需单独启动隔离服务与 Playwright，并记录哪些用例 mock 路由、哪些真实服务。

部署验收仅在明确授权后运行；先固定代码/构建摘要、保存旧产物与数据库备份。不得将日志、测试记录或文档包含真实凭据；不得清理共享业务数据。引用测试的输出必须对应本次 commit，不能混用上次报告。
