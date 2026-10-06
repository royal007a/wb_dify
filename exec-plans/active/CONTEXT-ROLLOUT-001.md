# CONTEXT-ROLLOUT-001

授权：lark:om_x100b6372c9d534b0b177a6da50a9acc。仅原版 Hify，目标 /opt/hify 和既有 /hify/ 入口；不改 CC、Dify、工作台或共享 Docker 缓存。任务状态以 tasks.json 为准。

## 发布前提

- CONTEXT-INTEGRITY-001 六 scope 完整门禁 passed，测试零 failures/errors/skips/flaky；固定 head 的 backend/frontend/deploy 源码与打包源码一致，无未跟踪源码。
- 本次会携带同一源码树上此前尚未部署的 Workflow 聚合、结构化输出。不是只替换 compactor 的单类热补丁。旧 STRUCTURED 的失败记录保留；新完整门禁不得冒称消除了旧关停偶发失败的根因。
- Claude 对 H1 固定范围的独立 review 已收到结论，阻塞问题已处理。
- 与 CC 错峰，切换前通知。安装器只改自己的 nginx snippet，但会 nginx -t 和 reload nginx；不承诺零停机。
- 重新检查 Python>=3.11、磁盘预算、V24 全部成功、Chat/Workflow/索引三表静默、旧 inputs 兼容行数。空间不足不清共享缓存，停止发布并请用户决定。

## 实施与验证

1. 从已验收源码重新构建 jar 和 /hify/ 前端，记录 SHA、源码树、构建参数；不宣称与测试时同一二进制。
2. 只读预检全部通过后，新建唯一 release 目录，上传并校验文件。执行现有已验收 installer，保留 previous.jar、previous-dist、previous-nginx.conf 和 0600 的 database-before.dump。
3. 检查 systemd Result、health、V24、三处产物 SHA、主密钥 stat 元数据不变。备份只做 pg_restore --list，不冒称恢复演练。
4. 运行既有 current smoke、prefix/direct 上传边界、部署态浏览器 Mock 对话。每轮绑定会话版本、SSE 路径、Run 回读和本轮答案；显式选 Demo，不冒称默认首项检查或真实模型验收。
5. 只归档 smoke 自建的 Agent/KB/document。浏览器自建两会话五 Run 及 current 自建会话/Run 因无删除接口保留并记录；不枚举或删除原有用户会话。
6. 发布后 harness/frontend 收尾门禁，提交去敏 JSON 证据及只含选定命令输出的 remote-observations；主后端准入仍引用六 scope 结果，收尾不顶替它。

## 失败处理和边界

- 安装器失败：按自身阶段恢复旧服务或停新服务等待检查，不重建密钥，不自动恢复数据库。
- oneshot 等待超时：记录 unit 状态；后台可能仍在执行，不重复创建发布。
- 安装成功后 smoke 失败：收集独立检查与当前服务状态，保留红灯；不会自动回滚或复跑付费模型。新版本可能仍在线，必须明确报告并诊断。
- 回退旧二进制前检查新类型 Workflow 数据兼容性；旧二进制可能不认识新节点/字段。不得以同为 V24 推定业务兼容。
- H1 压缩触发、消息完整性、模型零调用由本地生产路径测试证明；线上 Mock smoke 只证明基础交互，不证明真实模型遵循策略、语义质量或新结构化输出的线上质量。
- 自签证书的 smoke/browser 显式关闭测试客户端校验；不修改应用出站 TLS 信任策略。
