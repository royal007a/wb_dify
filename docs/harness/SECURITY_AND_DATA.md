# Harness Security and Data

## 权限模型

- `read_only`：检查、构建、测试、指标与证据读取，可自动运行。
- `reversible_write`：工作区代码和生成状态，可由 Git/checkpoint 恢复，可自动运行。
- `high_risk`：部署、数据库迁移、外部写、凭证使用，必须在命令中提供可追溯 `approvalRef`。
- `prohibited`：导出凭证、破坏性 reset、无边界递归删除，Harness 永不执行。

权限以任务声明为前置门，不能证明命令没有隐藏副作用；reviewer 仍需核验任务 operation 与实际命令一致。审批引用只证明有人授权，不得包含密码、token 或私钥。

## 数据规则

- evidence 禁止保存 API Key、Authorization、Cookie、Vault 响应、生产原文和未脱敏 Prompt。
- `command.log` 默认 Git ignore；需要持久化时抽取字段并人工脱敏。
- 数据库迁移必须备份、前向兼容并具有恢复说明；Harness checkpoint 不是数据库回滚。
- 部署必须记录目标、commit、备份位置、健康检查和 smoke test。
- `permissions.yaml` 的变更本身按高风险规则 review，不能和放宽权限后的业务操作混在同一原子任务。
