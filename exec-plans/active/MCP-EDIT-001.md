# MCP Server 编辑

来源：Lark om_x100b633aded650a4b3f816bdbaae21d。范围仅 ~/hify，hify-cc 保持只读。

1. 复用已有 PUT /api/v1/mcp-servers/{id}，补编辑按钮、回填和独立新增模式。
2. 凭证引用仅允许空值、env:NAME、system:property；不保存 token、不替用户修改现有配置。
3. endpoint/credentialRef 改动置 NEW，阻止尚未重新发现的配置用于新发布；不可变 revision 和旧发布版本保留。
4. 补 API 和浏览器回归：编辑、取消、服务端失败、新增、校验、冻结版本不变。
5. Harness 显式 backend/frontend/harness 验证，部署另记原子任务并保存备份与 smoke 证据。

不在本任务处理：目标 MCP 服务的证书、服务端环境变量、READ 标注或 Demo Agent 模型切换。
