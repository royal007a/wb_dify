# ADR-0004：凭证引用与受控出口

- 状态：Accepted
- 日期：2026-09-08

## 背景

Provider、MCP 和 HTTP 工具需要访问外部地址并携带凭证。内部部署不等于可信输入；模型生成参数和用户配置 URL 仍可能造成秘密泄露、SSRF 和危险副作用。

## 决策

数据库只保存 `credentialRef`。生产凭证由环境变量、Docker secret 或 Vault-compatible provider 解析。所有可配置出口统一经过 URL/DNS/redirect 校验和 allow policy；工具在 schema/语义校验及权限通过后才执行。

## 后果

API 不能读取原始凭证，备份不包含 key。开发环境需要明确的本地 HTTP allowlist。MVP 不提供 shell、任意代码执行和浏览器自动化工具。

