# DEPLOY-001 — 本地与远端部署验证

## Outcome

已验收的召回纵向切片在本地和远端可访问，并有迁移、健康和 smoke 证据。

## Scope / Non-goals

- In：全矩阵、镜像/服务更新、Flyway、健康检查、HTTP/浏览器 smoke、回滚点。
- Out：域名/TLS/高可用改造。

## Steps and checkpoints

1. 冻结提交并跑发布前全矩阵。
2. 本地部署与 smoke。
3. 远端上传/构建/迁移/切换与 smoke。
4. 记录版本、URL、证据和回滚边界。

## Acceptance and evidence

见 `harness/tasks.json`；部署授权引用已记录于任务。

## Rollback / recovery boundary

代码/镜像可回滚至部署前提交；数据库迁移为新增表/索引，不执行破坏性 down migration。
