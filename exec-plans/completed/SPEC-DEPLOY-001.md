# 132单实例发布与入口验收

授权：goal:01a07e90-58c6-7f00-8ce1-fc659dd6c39c；本地门禁eeb385e/代码9f40639已通过，独立复核并行进行。目标仅原版Hify，绝不修改hify-cc或共享TLS的其他location。

1. 只读检查132服务、当前jar SHA、Flyway版本、磁盘/nginx临时目录、Hify include及运行中任务数量；盘点引用名和目标但不读取密钥值。不满足空间、授权或身份边界就停止发布。
2. 构建前后记录HEAD/源码树/空diff；jar及/hify前缀dist形成可校验归档。部署脚本只升级既有服务，主密钥文件缺失即失败，禁止新生成/导出/覆盖密钥。只将Hify snippet与仓库模板对齐。
3. 使用独立/opt/hify/releases/spec-verify-*目录保存旧jar/dist/nginx片段与0600数据库dump；验证dump可列目录。停止旧实例后备份/迁移/启动，禁止双实例重叠。V21-V23为兼容扩展，但旧jar不保证识别新状态；不自动恢复数据库或宣称全自动数据回滚。
4. 健康和Flyway V23通过才发布前端index；nginx -t后reload，保留旧hashed assets。失败恢复旧nginx片段；静态目录仅备份，不自动恢复（独立复核纠正原计划的过度承诺）。数据库若已有新语义写入则停止、人工判断应用回退，不盲目降版本运行。
5. 仅用自身合成数据：HTTP健康/404/lookup，Demo时间/calculator/SSE浏览器；上传2MiB、恰好10MiB、10MiB+1、13MiB及JSON41300。只归档自身知识库/文档/MCP记录，不碰既有用户数据；MCP只测Token配置，不用真实第三方凭据。
6. 最终本地门禁、远端artifact SHA、前缀静态资源、磁盘、备份/迁移、浏览器结果分开保存；外部LLM/MCP、鉴权/DNS重绑定等保留未验边界。提交后请mymacclaude只读复核。
