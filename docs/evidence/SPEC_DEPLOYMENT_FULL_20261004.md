# 132 完整重新发布（SPEC-DEPLOY-007）

授权：lark:om_x100b6314d40470a4b2e14a8024c780f。2026-10-04 08:29:39Z 开始，08:33:39Z 完成线上检查及本地收尾门禁（16:33:39 CST）；瞬态安装单元未保留精确起止时间，不把此门禁时间冒充切换瞬间。

入口：https://118.196.123.132/hify/ 。应用代码42db727，发布基线043c18f；backend 85586d8c、frontend 1e257aae、deploy fd72f546，三目录源码diff为空。前置完整验收见SPEC_RELEASE_VERIFY_20261004.md及043c18f：backend602、migration118、runtime34、eval24均零失败/错误/skip/flaky，各scope重叠不相加。复核方已离线复算摘要、源码身份和57个具名子场景报告，没有重跑Maven或远端。

## 实际执行与结果

- 本地重新构建jar及/hify前缀前端；package使用skipTests仅为打包，不代替前置完整验收。jar所含迁移V1–V23，与已审安装器93352ed7一致，没有新增schema。未完成的DEPLOY-004测试改动留在stash，没有混入发布。
- 新目录/opt/hify/releases/spec-verify-20261004-42db727，完整上传后校验SHA；systemd持久作业hify-upgrade-20261004-0830执行安装，Result=success、ExecMainStatus=0。nginx配置/备份/停旧启动顺序沿用已审安装器，不修改其他站点、systemd unit或真实凭据内容。
- 本地构建记录与远端最终SHA逐项一致：jar 3aa5eb39、index 81242978、snippet 6c43b0a0、installer 93352ed7。完整SHA在deployment-summary.json。前后主密钥文件inode/长度/mtime/权限/属主相同；只读元数据，不声称验证了密钥值。
- pg_dump 390349字节、0600，pg_restore --list通过；没有执行恢复，不证明恢复演练。备份与数据同盘。
- 真实132/PG的当前版本API冒烟：23条记录，涵盖管理更新/NUL上传/四类绑定/会话/Run输入/知识检索/memory/意图拒绝及合法对照。坏更新完整回读不变；坏Run后by-key为404且no-store；合法计算Run完成42、同key重放200。模型为Mock，不调用外部供应商。
- HTTPS /hify代理和远端HTTP直连分别：2MiB、恰好10MiB为202；10MiB+1、13MiB为413/41300 JSON；检索要求本次文档和精确合成短句。只归档自己创建的KB/文档/Agent；无删除接口的合成会话和Run保留。
- 真实线上浏览器3/3：计算Chat、问候后及重复时间工具调用、MCP合成Token写入/保留/替换/清除。MCP测试禁用合成Server、不请求外部MCP；不证明外部模型或MCP服务效果。仅测试客户端显式忽略自签TLS证书；直连HTTP报告的tlsCertificateValidation=true只是开关默认值，HTTP无证书校验。
- 最终服务active、health200、V23全部success，RUNNING Run/Workflow及PENDING或RUNNING索引均0。本地收尾harness/frontend七步exit0，Python74通过、typecheck/build通过，verification在08:33:39Z为passed；不将这两个scope替代前置六scope。

## 证据与未保证

证据目录：harness/evidence/SPEC-DEPLOY-007/SPEC-DEPLOY-007-20261004T082939Z-1f8dfa88/。部署摘要、三份smoke JSON、实际调用脚本、runner和verification已提交；日志SHA已本地逐一重算。raw logs继续gitignore，读者不能仅凭摘要重演远端执行，也不能复算未提交日志。

预检可用1578028KiB，最终1347156KiB（约1.28GiB）、占用97%。未删除旧release、备份或Docker资源。下次发布应先获明确范围的清理授权或扩容；本次预检空间阈值不是持续容量保障。仍自签TLS、无登录鉴权，开放P2未归零。主流程输出断管道、未来V24安装器目标和其他DEPLOY-004边界仍在，本次通过systemd/journal和V23产物限制规避，不算通用修复。

旧Chat005/知识完整性003的满盘blocked记录保留；当前代码由新的完整验收和本次发布覆盖，不回写旧失败为通过。默认Colima满盘未被清理或重启，另建专用验证profile跑真实PG。
