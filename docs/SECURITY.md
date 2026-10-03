# Hify 安全与数据边界

## 1. 威胁模型

即使是内部单工作区，以下输入仍不可信：用户 prompt、上传文件、模型生成的 tool arguments、MCP Server 响应、Provider/MCP URL、重定向目标和第三方错误文本。

## 2. 凭证

- Provider API 只接收 `credentialRef`；MCP 额外接受 write-only `credentialToken`（用户批准的直接输入模式，见 ADR-0020）。
- MCP Token 以 AES-256-GCM 密文保存到独立 `mcp_credentials` 表，server/revision 只持不透明引用；主密钥来自 `HIFY_MCP_MASTER_KEY`，不入数据库/镜像/仓库。缺密钥拒绝写入，错误密钥拒绝使用，不回退明文。
- 数据库、Agent version、日志、Run event、tool result、前端缓存均不得包含原始 key/token。
- MCP 前端只显示 `credentialConfigured/credentialMode`，不返回密钥、密文或内部引用；保持/替换/清除分开。env/system 引用可以回显变量名，引用存在不等于实际值可解析。
- Token 更换追加新密文记录；清除或归档仅影响草稿，历史发布继续持有旧引用。撤销泄露 Token 必须在 MCP 服务端执行，不能把“清除草稿”误报为全局撤销。
- 加密不替代认证：当前管理 API 仍限受信管理网/入口访问控制，不应直接开放给不受信用户。
- Authorization/header/query 中的秘密统一脱敏；异常对象不得直接序列化。

## 3. 工具风险与授权

| 风险 | 示例 | 默认策略 |
|---|---|---|
| read | 时间、只读检索 | Agent 显式绑定后允许 |
| external | 外部搜索/MCP 读取 | allowlist + 出口校验 |
| write | 写外部系统、发消息 | 默认拒绝；需显式 policy/交互批准 |
| execute | shell、代码、文件修改 | MVP 不提供 |

执行顺序固定为 schema 校验 -> 语义校验 -> 权限 -> 副作用。任何“先调用再判断”都是 P0 缺陷。

## 4. SSRF 与 MCP

- 默认只允许 HTTPS；本地开发的 HTTP 需显式 allowlist。
- 解析 DNS 后拒绝 loopback、link-local、私网、保留地址和云 metadata；每次重定向重新校验。
- 限制端口、重定向次数、响应体大小、内容类型、连接和总时长。
- 防 DNS rebinding；代理实际连接 IP 必须与通过校验的解析结果一致。
- MCP tool schema 作为版本快照；刷新后不静默改变已发布 Agent 的能力。
- 第三方返回内容按不可信数据处理，不能覆盖 system policy 或授权策略。

## 5. 文件与 RAG

- 只接受声明的 TXT/Markdown MIME 与扩展名组合；限制文件、知识库和分块总量。
- object key 由系统生成，禁止路径穿越；解析在隔离目录中，不执行宏、脚本或嵌入对象。
- 文档删除应清理 object/chunk/vector 并留下可审计结果。
- 检索内容是上下文数据，不是系统指令；Prompt 需区分可信指令与不可信引用。

## 6. 审计与保留

- 审计记录：登录、Provider/MCP 配置、Agent 发布、工具授权、Run 取消、文档删除。
- `run_checkpoints` 与会话消息使用同一数据访问边界；checkpoint 只保存恢复必需的消息，不保存 Provider 凭证或原始 Authorization/header。未来工具可能返回敏感数据时，写入 checkpoint 前必须按工具 schema 做字段级脱敏。
- 普通运行事件默认保留 30 天；会话/文档由管理员策略决定；调试内容最短保留且默认关闭。
- 导出和删除均记录 actor、scope、request id 和结果，不把删除对象的秘密复制进审计。
