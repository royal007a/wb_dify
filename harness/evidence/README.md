# Harness Evidence

每次 `run-task.sh` 在 `<task-id>/<run-id>/` 下创建 `run.json`、checkpoint、验证 manifest 和日志。JSON manifest 可提交；`*.log` 默认忽略，避免把大输出或潜在敏感信息写入 Git。

证据只证明命令在特定 commit、数据集和环境中执行过，不替代人工判断。任何凭证、Authorization、原始 Prompt 秘密和生产数据不得进入本目录。
