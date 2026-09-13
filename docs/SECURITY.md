# Hify 安全与数据边界

## 1. 威胁模型

即使是内部单工作区，以下输入仍不可信：用户 prompt、上传文件、模型生成的 tool arguments、MCP Server 响应、Provider/MCP URL、重定向目标和第三方错误文本。

## 2. 凭证

- API 只接收 `credentialRef`；生产凭证来自环境变量、Docker secret 或 Vault-compatible provider。
- 数据库、Agent version、日志、Run event、tool result、前端缓存均不得包含原始 key/token。
- 前端只显示 `configured=true` 和末尾指纹；更新凭证使用 replace 语义。
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
- 普通运行事件默认保留 30 天；会话/文档由管理员策略决定；调试内容最短保留且默认关闭。
- 导出和删除均记录 actor、scope、request id 和结果，不把删除对象的秘密复制进审计。

