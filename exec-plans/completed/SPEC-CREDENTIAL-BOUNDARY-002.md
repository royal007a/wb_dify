# G 复核补充：配置保护和冻结调用

仅修改 ~/hify。先用 reviewer 的 env:HIFY_DB_PASSWORD 反例红灯，再保护 Hify DB/Redis 与 JVM TLS 基础配置命名空间（含大小写、点和连字符规范化）。不按任意 SECRET/PASSWORD 子串屏蔽正常应用凭据，不读实际配置值。

Provider 保存时去尾斜杠，运维授权配置按规范化后的地址填写；MCP路径仍保持精确匹配。补文档与反例，不宽化已授权路径。

冻结能力执行测试直接构造旧 revision 的未授权假 property 引用，通过 McpCapabilityService 的真实运行路径验证无网络外发；同一契约在 H2 与临时 PostgreSQL 执行。保留既有 TOKEN 发布快照正向测试。

记录红绿证据、源码提交、未部署边界；可回滚代码，不恢复不安全的自动授权，也不改变共享环境的真实凭据。
