# MCP 直接 Token 输入与加密版本

状态：2026-10-03 用户确认直接输入方案，并要求适配后重新部署。

## 决策

1. 保留 env/system 引用，MCP 增加 write-only Token；后续 ADR-0021 收紧 MCP/Provider 引用为管理员批准的引用/目标绑定，不再允许任意进程变量读取。
2. AES-256-GCM 使用独立 32-byte 主密钥、每次随机 12-byte nonce、128-bit tag；AAD 绑定格式版本/serverId/credentialId。错误主密钥、篡改、跨服务解密均失败。
3. 新增 V20 `mcp_credentials`；只保存密文，配置与历史 revision 只持 `stored:UUID`。不接受用户传入 stored 引用，运行时按 serverId 再校验归属。
4. 替换是追加，不覆盖。KEEP 不产生新版本；CLEAR 不删除历史凭据。防止新配置悄悄改变旧 AgentVersion。真正撤销需向上游吊销 Token。
5. 缺少主密钥时拒绝 TOKEN；不使用开发默认主密钥，不自动生成丢失后无法解密的临时密钥。
6. 不提供读明文 API，DTO toString/JSON 排除 Token，JSON 解析失败不写原始错误到日志。远端原样回显 Token 被脱敏。

## 运维边界

- 主密钥通过仅服务用户可读的独立环境文件注入；不写镜像/仓库/备份 SQL。备份数据库同时须另行保护并备份主密钥。
- 当前只支持单主密钥；不能直接替换主密钥，否则旧密文不可解密。未来轮换需 key-id/keyring + 迁移方案。
- V20 为新增表，旧 env/system 数据不变。回滚旧应用不能解析新 stored 引用，因此写入首个 Token 后不支持盲目回滚旧 jar；应前滚修复或经确认恢复部署前数据，不能隐式丢弃后续业务数据。
- 管理端当前仍无用户体系；加密解决静态存储泄露，不解决管理员滥用/未授权 API。仅受信入口使用。
- 不放宽 TLS/SSRF，也不自动使用截图里暴露的 Token。
