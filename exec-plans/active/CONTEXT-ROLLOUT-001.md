# CONTEXT-ROLLOUT-001

授权：lark:om_x100b6372c9d534b0b177a6da50a9acc。仅原版 Hify，目标 /opt/hify 和既有 /hify/ 入口；不改 CC、Dify、工作台或共享 Docker 缓存。任务状态以 tasks.json 为准。

2026-10-07 切换风险补充授权：用户消息 `lark:om_x100b63505002e8a0c367a18a505de0e`
原文为“a+ b”。已直接读取会话历史，核对同话题前文 `om_x100b635055806134b3bd189df71c88b`
的 B 项明确列出 nginx reload、换 jar 后失败可能停服并需人工恢复；按该 B 项同意部署执行，
不是仅依据其他机器人的转述。A 项由 CC 处理，本任务不清理本机 default 或远端共享资源。
本次准入沿用 bc4ab0e 的六范围通过及独立复核，发布前仍重新检查源码树与实际 preflight；
本条记录授权，不代表部署已经发生或通过。

## 发布前提

- 保留 CONTEXT-INTEGRITY-001 准入（由 run-task/harness 的 dependsOn 检查），controller 直接检查当前 OBS-CORRELATION-001 六 scope 完整门禁 passed，测试零 failures/errors/skips/flaky；固定 head 的 backend/frontend/deploy 源码与打包源码一致，无未跟踪源码。不得拿旧 H1 gate 发布后续变更。
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

## 2026-10-07 发布前补强（尚未执行部署）

用户再次明确要求部署到 132。旧失败 gate、H2/PG 定向复验都保留，窄测不替代完整准入。
本轮仅准备发布脚本：移除 Python assert 关键门禁；预检先 nginx -t；上传未提交安装前失败时
仅清理四个精确上传目标并 rmdir（不递归）；备份写入 .partial，经 pg_restore --list 后改名，
失败先清理本次 partial 再按原阶段恢复旧服务。完整备份、旧 jar 和旧前端不删。

运行身份补充：要求 PID 切换，通过 /proc/PID/cmdline 与打开的 jar 文件描述符核对 jar SHA；
无法取得唯一身份就不宣称成功。浏览器核对导航 HTML、观察到的 assets 响应正文 SHA 及页面
script/stylesheet 引用，不再把 AgentVersion 当作构建版本。这不证明 JVM 内每个已加载类的字节。
远端观察在每步 finally 保存，即使后续密钥元数据或身份断言失败也保留。

安装后失败仍可能停止新 Hify，不自动恢复数据库或旧应用；此行为未改变，切换通知须明确提示。
上述历史阶段当时尚待演练；f71d587 的离线安装/冒烟 fixture 后续 20/20、准入/进程探针 4/4。
这不是部署态浏览器验证或六范围门禁；每轮补强后的证据另记，不扩大旧结果。

离线准入反例另覆盖 python -O 下的未完成 gate、失败/旧 schema/缺 scope/哈希不符 gate，
以及从生产脚本抽出的 /proc 探针（无 jar 描述符、正确描述符、错误 cmdline）。这些使用合成目录，
不连接远端，不代表实际进程探针已验证。历史 smoke 文件保留原样，调用时使用 python3 -E，
使 PYTHONOPTIMIZE 环境变量不能关闭其中的断言。

## 发布脚本二轮 review 修订

- 同一份只读身份脚本在 preflight 和 final 复用；先要求正 PID，读取启动 tick，核对 cmdline、打开的 jar FD 和磁盘 jar SHA。正式切换后要求 PID 改变且启动 tick 增加。不输出完整 cmdline 或环境变量。
- controller 按 staging + old copies + database size + 2 GiB 计算空间预算；installer 入场底线同步提高至 2 GiB。旧 release/备份不会自动清理，空间不足必须停下，不能降低阈值凑过。
- testedHead 必须是 40 位小写十六进制；finally 写观察副本时以 replacement 解码非 UTF-8 字节，原始日志字节不变，不能覆盖原异常。
- 远端核对本次前端清单中的每个文件 SHA，含未访问的懒加载资源；浏览器仍只证明实际加载的路由资源，不宣称已访问所有页面。保留旧 assets 的既有安装机制不变。
- 对 132 旧进程的一次只读探针已执行成功；这是发布方观察，不是独立审计，不代表新版本已部署。实际切换前仍要重跑全部 preflight。
- Git 跟踪树校验不检查被忽略的 Vite .env*；构建参数明确覆盖 base/API，但不能将其泛化成完全可复现构建。
- 正式切换前需明确告知并确认 nginx reload 和失败停服边界。获得确认并通过当前源码的六范围门禁之前，不执行安装。
