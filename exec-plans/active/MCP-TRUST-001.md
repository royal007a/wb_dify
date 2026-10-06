# MCP-TRUST-001

用户授权 om_x100b637e5c2f24a8c4f563a9c3596cd：引用 om_x100b637db50be934b3ebff75068c514 明确选择方案②并要求执行。确认消息 om_x100b637e5a73b894b480b1f5cd1dd17 记录范围。任务状态以 tasks.json 为准。

## 范围与前提

- 只改原版 Hify 的独立信任库与新增 systemd drop-in，并重启一次；不发布新 jar/dist，不改 nginx/CC/工作台/凭据/工具标注。
- 信任对整个 Hify JVM 生效，不是单 endpoint pin。默认 Java 公共 CA 复制保留；今后 JDK CA 更新须重新合并，工作台换证书须重新审核。
- `/etc/hify` 是 root 0700，不能让 hifyapp 读取那里新建的信任库；使用单独 `/etc/hify-trust` 0755 root 目录，文件只含公开证书、0644 root。
- 固定批准证书 SHA256：e1bb50bd7d35af61eb0bd9f921f8fab8ad61c7b521c67034e875b22d0e491ab5。先从本机证书文件及服务端握手比对，再信任。不读取私钥。

## 执行

1. 检查原服务正常、运行中的 jar SHA、Java 路径、三个 Java 选项环境变量不存在、命令行未显式覆盖 trustStore、专用目标不存在、磁盘足够、三类任务为0。
2. 新建独立恢复/审计目录，复制系统 cacerts 成专用信任库，keytool 仅导入固定公开证书。逐个比较证书指纹集合，要求旧集合全部在、新增恰为指定叶证书且无私钥。
3. 在不重启服务前执行 Java JSSE 探针：默认库拒绝该自签证书；专用库以 HTTPS endpoint identification 校验主机名并发匿名 GET /api/v1/mcp 得401；错误主机名仍失败。hifyapp 身份复测可读。探针不携带令牌，不输出响应正文。
4. 停服务后再次确认无在途任务，再新增独立 drop-in 注入非秘密 JAVA_TOOL_OPTIONS 的 trustStore/type。daemon-reload + start，等待health并核对实际进程的配置、启动PID改变、原jar与主密钥元数据未变。
5. 保存去敏结果、回退说明和 harness 收尾结果。用户自行重试发现工具；此任务不调用认证 MCP、不改只读权限、不宣称 Agent 已可发布。

## 失败与回退

配置写入后若启动/健康/身份检查失败，把本任务唯一 drop-in 移回恢复目录，daemon-reload 并尝试启动原配置。信任库文件和公开证书留作诊断，不改系统 cacerts，不删其他配置；若旧服务也失败，明确停服状态并请求人工处理。重启存在短暂中断，不是零停机。

手工回退必须先核对独立 drop-in 未被后续运维修改、确认三表静默，再将该文件移到本任务恢复目录，daemon-reload、restart hify、health验证。回退后工作台 TLS 将再次不受信任；不恢复或改动数据库。
