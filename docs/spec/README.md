# Hify 可执行验收规格

范围：原版 `~/hify`，不包含独立课程实现 `~/code/hify-cc`。初始源码基线 `973257c`；专项修复、方法级验证与部署进度以本次manifest和tasks.json为准。本文是验收要求，不是完成声明。

## 入口与证据等级

- [HTTP_API.md](HTTP_API.md)：全部显式 `/api` 方法/路径、输入 DTO、成功响应、核心边界、候选测试。
- [http-api.json](http-api.json)：人工维护的接口契约；`ApiContractInventoryTest` 对 Spring 实际注册集合做双向相等校验，不能只检查文档里的接口存在。
- [FUNCTION_TESTS.md](FUNCTION_TESTS.md)：跨接口、页面、Runtime、安全、恢复、评测和部署验收矩阵。
- [behavior-cases.json](behavior-cases.json)：人工核对的精确测试方法/断言、68条路由和F01–F38映射。`harness/behavior_report.py --evidence-dir <本次目录>`从与本次摘要SHA匹配的Surefire XML提取方法结果；缺失/跳过为not-run，参数化任一失败为fail。映射子场景有fail/not-run时路由为partial，全pass也仅为mapped-subcases-only，不将整条路由或功能组统称通过。原始XML不提交（含运行环境属性）；SPEC-VERIFY-002起可用`--export-methods <文件>`导出仅含方法/状态和绑定元数据的输入，并显式以`--method-evidence <文件>`离线重算同样的报告。旧报告没有这种输入，仍不能仅凭提交重算。脱敏输入与摘要的绑定不是签名，不防同时伪造仓库证据。
- [AUDIT_FINDINGS.md](AUDIT_FINDINGS.md)：可定位的差距与风险。实际任务状态只看 `harness/tasks.json`。
- [SPEC_RUN_SHUTDOWN.md](SPEC_RUN_SHUTDOWN.md)：应用关闭、用户取消、Workflow 中断与单实例重启恢复契约。
- [SPEC_WORKFLOW_SETTLEMENT.md](SPEC_WORKFLOW_SETTLEMENT.md)：Workflow 执行事实与父 Run 交付结果分开记录的 v2 投影。
- [SPEC_WORKFLOW_GRAPH.md](SPEC_WORKFLOW_GRAPH.md)：最长路径 50 步、引号感知的条件语法及旧非法图拒绝。
- [SPEC_CHAT_LIFECYCLE.md](SPEC_CHAT_LIFECYCLE.md)：创建结果不明的同key重试、创建中取消、恢复输入与终态补读。
- [SPEC_HISTORY_RECOVERY.md](SPEC_HISTORY_RECOVERY.md)：已提交模型/工具操作重放、V23 迁移与旧工具历史的兼容边界。
- [SPEC_WORKFLOW_KNOWLEDGE.md](SPEC_WORKFLOW_KNOWLEDGE.md)：Workflow 发布语料、冻结清单完整性、归档历史引用与旧 DSL 兼容边界。
- [SPEC_KNOWLEDGE_FINISH.md](SPEC_KNOWLEDGE_FINISH.md)：知识空/失败的拒答、候选与最终引用来源校验、恢复映射；不宣称语义蕴含验证。
- 本轮新鲜结果单独记在 `docs/evidence/SPEC_AUDIT.md` 和 Harness run manifest；旧 evidence 仅历史参考。

“候选测试存在”不等于“该端点已完整测试”，“路由一致”不等于“68 个接口行为通过”（003身份查询增加1项，审计历史基线为67），“H2/Mock 通过”不等于“真实 PostgreSQL/外部模型通过”。缺失、跳过、外部依赖不足必须单列，不能计入成功分母。

## 通用协议（当前实际契约）

1. 管理 API 使用 `Result<T>={code,message,data}`，成功 code=200。分页为 `PageResult<T>`，data 是列表，total/page/size 在顶层；请求为 page/pageSize，不是 cursor。
2. Run、Conversation、Intent、Memory 与旧 `/api` 读接口返回原始资源，不强制解包 data。SSE 返回 `text/event-stream`。客户端必须按接口选用相应处理器。
3. `Idempotency-Key` 仅创建 Run 时强制。其余 POST/PUT 不宣称幂等键支持。首次 Run 为 HTTP202；同 key 同语义重放200；不同语义409/40901。
4. 业务错误 HTTP 状态与 Result.code 对应：40000、40100、40300、40400、40500、40600、40900、40901、41300、41500、50000、50300。已覆盖的框架错误返回 JSON Result；缺header/参数、类型错误不回显拒绝值，但不能泛化到所有IllegalArgumentException或容器进入DispatcherServlet之前的错误。Provider/Tool 的分类错误是运行事件/检查结果语义，不是另一套 HTTP 数字枚举。
5. Instant 时间按 UTC ISO 格式；DemoItem 的 LocalDateTime 为无时区 ISO 本地日期时间。ID 大多为 UUID 字符串；DemoItem 是 Long，不承诺 UUIDv7/ULID。
6. 管理台无登录/RBAC/租户隔离。数据归属检查（例如 memory 同会话）不等于用户权限。只能在受信网络入口使用，不宣称公网安全产品。
7. Provider 只存 env/system 引用。MCP 另有 write-only TOKEN：AES-GCM 加密、服务端独立主密钥；页面不回填。Token 保留/替换/清除和历史 snapshot 的影响见 ADR-0020。
8. 框架 `/error`、Actuator、Nginx 路径前缀属于运维矩阵；隐式 HEAD/OPTIONS 不计入68个显式接口。新增显式映射必须增规格与测试，不靠生成器自动“接受”代码变化。

## 复现与安全

```sh
python3 harness/render-api-spec.py --check
cd backend
mvn -pl hify-app -am -Dtest=ApiContractInventoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

后续完整门禁：`./harness/verify.sh --scope all`。这会运行单测/集成、PostgreSQL、Runtime、Eval、类型检查/构建，**不自动运行浏览器，也不自动调用真实付费模型**。浏览器需单独启动隔离服务与 Playwright，并记录哪些用例 mock 路由、哪些真实服务。

部署验收仅在明确授权后运行；先固定代码/构建摘要、保存旧产物与数据库备份。不得将日志、测试记录或文档包含真实凭据；不得清理共享业务数据。引用测试的输出必须对应本次 commit，不能混用上次报告。
