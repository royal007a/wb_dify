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

## 132 部署验收

- 已部署代码：后端 `8c04e9c`、前端 `5965fbf`，发布基线 `82467f2`。
- URL：`https://118.196.123.132/hify/mcp`。健康接口 `/hify/api/v1/health` 返回 HTTP 200、业务 code 200。
- 仅替换原 Hify jar 与 `/hify/` 静态资源，未更改环境文件、TLS 设置、数据库结构或 hify-cc；后者 6 个容器仍 healthy。
- 回滚备份：`/opt/hify/releases/mcp-edit-20261003-82467f2/previous.jar` 与 `previous-dist/`；旧 hashed assets 保留。
- 本地/远端 jar SHA256 一致：`716ab4c7224d7b7e049c3b1c7c6c2509a8623eefde9cfd52d7b7d5c1211f15eb`。
- `MCP_EDIT_LIVE=1 E2E_BASE_URL=https://118.196.123.132/hify/ E2E_IGNORE_HTTPS_ERRORS=true npx playwright test e2e/mcp-edit-live.spec.ts --workers=1`：1/1 通过（5.8s）。
- 用新建且 disabled 的独立 smoke 记录实测 UI 编辑、PUT 保存、刷新、API 回读一致，以及明文凭证被 HTTP 400 拒绝；结束后只归档本次 smoke 记录。teacher_mcp 未修改，未调用任何真实业务工具。
- HTTPS smoke 忽略自签名证书仅用于测试浏览器，不改应用的出站 TLS 安全策略。
