# DEPLOY-003

先推送 split commits，再执行本地 compose 部署与 smoke；远端使用既有 `/opt/hify`、systemd `hify` 服务和 nginx `/hify/` 路由，拉取相同 commit、构建后端/前端、执行 Flyway V18、重启并验证。

回滚基线：`033dcec`。数据库 V18 只新增表，无破坏性 DDL；应用回滚时保留新增表，不做 destructive down migration。
