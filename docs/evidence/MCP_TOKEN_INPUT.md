# MCP 直接输入 Token

## 交付

- 后端代码 `8d2d957`；Console `0bd202b`；后续验证/运维提交附于 Git 历史。
- 用户批准直接输入而非手动 env 引用，并要求重新部署至 132。
- Token write-only、AES-256-GCM 独立密文表、serverId/credentialId AAD、不可变凭据版本、KEEP/TOKEN/REFERENCE/CLEAR 操作。
- API 和表单不回显 Token；env/system 高级模式兼容。清除不等于吊销历史版本，界面与契约均提示。

## 验证

- `harness/evidence/MCP-TOKEN-001/MCP-TOKEN-001-20261003T130723Z-bab6d426/verification.json`：harness/backend/frontend/migration 全通过。
- 后端默认测试 149 项，142 通过、7 项 Docker 测试默认跳过；独立 migration 门禁 8/8（包括凭据 4 项及既有 PostgreSQL 3 项、迁移 1 项），没有把 skip 当通过。
- 新加密单测 5 项：随机 nonce、跨重建实例解密、篡改/错 key/错 owner 拒绝、无 key 拒绝、DTO write-only、旧引用兼容。
- 同一 API 契约在 H2 和真实 PostgreSQL 各 4 项：存储不含明文，列表不回显，保持/替换/清除，AgentVersion 仍用旧 Token，跨 Server 拒绝，非法操作不泄密，畸形 JSON 日志不泄密。
- 既有 MCP API 新增未配置主密钥的失败测试，验证事务回滚且没有半成品 Server。
- 浏览器 `mcp-edit.spec.ts` 3 项 + 管理路由 1 项，4/4 通过。包括直接粘贴、显式清除、取消、保存失败保留表单、重开不回填、切换模式不夹带旧 Token。
- 所有 Token 均为 synthetic test value，没有使用截图/生产 Token。

## 负面结果与修正

- 首轮浏览器定位到 Element Plus 内部 input，被 placeholder 遮挡；改为点击可见 select wrapper 后 4/4 通过。
- 首轮 PostgreSQL 门禁新增凭据 4/4 通过，但既有 Chat 测试在 Run 终态写入与事件投影之间竞争，漏看到 run.completed。按既有异步投影语义，同时等待终态与完成事件，未修改运行时；最终迁移门禁 8/8，0 skip。

## 边界

- 加密不是管理端认证；仍仅供受信管理入口，未新增登录系统。
- 只支持单主密钥，不支持直接覆盖主密钥轮换。数据库备份必须另行配套保管主密钥。
- V20 不改旧表。写入 stored 引用后，不可盲目回滚到不识别该引用的旧 jar。
- 本次不修 teacher MCP 的 TLS 证书，也不放宽只读工具风险判断。

## 部署

- 2026-10-03 发布构建基线 `d06034e`，入口 https://118.196.123.132/hify/mcp。
- jar SHA-256（本地/远端一致）：`299838bdd9b7fddcb4d7554e2f06099aec6d14e38c49181829a152f69801a667`。
- V20 Flyway `success=true`，服务 active，内外健康均 HTTP 200。
- 原数据库 dump/旧 jar/旧前端备份：`/opt/hify/releases/mcp-token-20261003-d06034e/`；dump 0600，目录 0700。
- 主密钥位于 `/etc/hify/mcp-credentials.env`，root:0600；systemd drop-in 只声明路径。值未回显、未导出、未存 Git。
- 部署浏览器 4/4：MCP 直接 Token 创建/保持/替换/清除、旧引用编辑、Demo 多轮时间、calculator SSE。
- 首轮 live Token smoke 因定位到 Element Plus 隐藏 switch input 而超时（尚未发创建请求），改点可见 wrapper 并断言关闭后，完整 4/4 通过。
- SQL 聚合确认 synthetic 测试凭据是 v1 密文且不含测试原文；服务日志 synthetic Token 匹配数 0。测试记录均归档，不修改现有 teacher_mcp。
- hify-cc 六个服务仍 healthy，无本次修改；本地临时 Vite 5197 已停止。
- 未使用真实 teacher Token 做发现调用；对该服务的远端鉴权/TLS/READ 工具标注不宣称本次已验通。
