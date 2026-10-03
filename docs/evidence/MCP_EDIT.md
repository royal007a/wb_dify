# MCP Server 编辑交付（2026-10-03）

范围：原 Hify（~/hify），不修改课程仓库 hify-cc。来源：Lark om_x100b633aded650a4b3f816bdbaae21d。

## 实现与验证

- Console 编辑按钮：名称、Endpoint、凭证引用、启用状态回填；PUT 更新原 id，取消不写入，失败保留输入，新增模式清空。
- 操作列固定在右侧，横向滚动时仍可编辑；凭证输入默认隐藏，前后端均拒绝直接保存 Token。
- endpoint/credentialRef 改动后草稿为 NEW，重新发现后才能调试/新发布；历史 revision 不变。API 测试将草稿地址改成不可用地址，旧发布版本仍通过冻结地址执行成功。
- `mvn -pl hify-app -am test`：121 个用例，118 执行通过、3 个 PostgreSQL 并发测试默认跳过。本次无数据库迁移。
- `McpServerApiIntegrationTest`：6/6 通过。
- `E2E_BASE_URL=http://127.0.0.1:5197/ npx playwright test e2e/mcp-edit.spec.ts e2e/management.spec.ts --workers=1`：3/3 通过。
- 首次浏览器测试失败是点击 Element Plus 隐藏的 switch input，改为点击可见 switch 容器后通过；未绕过断言。
- Harness backend/frontend/harness 显式门禁通过，证据：`harness/evidence/MCP-EDIT-001/MCP-EDIT-001-20261003T114237Z-41f1a26a/`。

## 边界

本次不改用户现有 teacher_mcp 配置，不迁移凭证，不绕过 Java TLS 校验，不修改远端工具 READ 声明。编辑能力就绪不等于该第三方 MCP 的认证、证书和工具授权已全部接通。
凭证引用保存只验证格式；`env:MCP_TOKEN` 的实际值必须由运维配置到服务进程环境中。
