# MCP 直接输入 Token

## 交付

- 后端代码 `8d2d957`；Console `0bd202b`；后续验证/运维提交附于 Git 历史。
- 用户批准直接输入而非手动 env 引用，并要求重新部署至 132。
- Token write-only、AES-256-GCM 独立密文表、serverId/credentialId AAD、不可变凭据版本、KEEP/TOKEN/REFERENCE/CLEAR 操作。
- API 和表单不回显 Token；env/system 高级模式兼容。清除不等于吊销历史版本，界面与契约均提示。

## 验证

- `harness/evidence/MCP-TOKEN-001/MCP-TOKEN-001-20261003T130723Z-bab6d426/verification.json`：harness/backend/frontend/migration 全通过。
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

待执行独立部署任务 MCP-TOKEN-DEPLOY-001；机器状态以 tasks.json 为准。
