# DEPLOY-004

部署已通过 Harness 门禁的 Workflow/MCP Runtime 与 Workflow Canvas 版本。

1. 推送 origin/main，记录部署前远端提交作为回滚基线。
2. 本地重新构建 app/web 容器，保留 PostgreSQL 数据卷并执行 Flyway V19。
3. 本地检查 health、Flyway 版本与完整 Chat Playwright。
4. 远端拉取同一提交，构建后端和前端，重启 hify 服务并执行 V19。
5. 检查远端 health、Workflow/MCP API 与浏览器页面，记录 URL 和证据。
