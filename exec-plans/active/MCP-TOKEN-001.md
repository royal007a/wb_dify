# MCP Token 输入

用户批准：lark:om_x100b633b9b70d4a8b3fdb7eddf7b887。范围为原 Hify，不改 hify-cc。

## 契约

- 新增 credentialAction KEEP/TOKEN/REFERENCE/CLEAR；TOKEN 使用 write-only credentialToken。
- 兼容旧请求：无 action 时 credentialRef 缺省保持，空串清除，非空更新引用。
- 加密凭据单独存 mcp_credentials；AES-256-GCM，随机 nonce，AAD 绑定 serverId/credentialId。
- 服务配置只持 stored:UUID，不接受外部提交 stored 引用。更换追加记录，历史 revision 不变。
- 清除只清草稿，不撤销历史凭据；安全撤销需在 MCP 服务端撤销 Token。
- HIFY_MCP_MASTER_KEY 为独立 Base64 32-byte key，无默认值；不配置时 env/system 仍可用、TOKEN fail closed。
- DTO 不回传密文/内部引用/原文，日志不得打印请求 Token 或解析错误原文。

## 步骤与验证

1. 存储、迁移与凭据动作：单测验证加解密、篡改、密钥缺失、跨 server 隔离。
2. HTTP/API：测试真实模拟 MCP 鉴权、列表只读投影、换密钥与历史 revision、非法请求不泄密。
3. UI：保持/直接输入/引用/清除；浏览器验证保存后无明文、编辑重开空白、失败重试。
4. 显式 harness/backend/frontend/migration 验收。部署单独登记，留备份和审批引用。

## 边界

不使用截图中的密钥、不改 teacher_mcp 的实际 Token、不放宽 TLS/SSRF/READ 权限，不扩展认证系统。
加密存储不代替管理端访问控制；仅受信管理网访问。
