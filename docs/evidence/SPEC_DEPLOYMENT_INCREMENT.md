# 132增量发布：Run输入与Chat恢复（SPEC-DEPLOY-006）

授权goal:01a07e90-58c6-7f00-8ce1-fc659dd6c39c；2026-10-04。只升级原版Hify，未改hify-cc、其他服务配置、TLS证书或凭据。独立复核已关闭Run输入001/002及安装器P1/SIGPIPE两条P2。

## 产物与发布

- 构建HEAD 5f49629，backend c0200f2c、frontend 8f83bc47，与f0dd199的547项后端零skip门禁源码完全相同，diff SHA为空串值。Run输入001的32项受控浏览器证据仍属于原时间；本轮另有真实132浏览器验证。
- `mvn -B -DskipTests -f backend/pom.xml package`与/hify前缀Vite build通过。skipTests仅打包，不算测试。Vite大bundle警告保留。
- 新release：`/opt/hify/releases/spec-verify-20261004-5f49629`；备份previous.jar/dist/snippet与停旧实例后生成的dump，344173字节、0600、pg_restore --list通过；没有实际恢复数据库。
- systemd持久作业`hify-upgrade-20261004-0155`执行安装器，输出进journal而非SSH管道；Result=success、ExecMainStatus=0，09:56:12 CST结束。无旧新实例重叠，V23无新增迁移，Hify active。主密钥inode/size/mtime/权限/所有者不变，未读密钥值。
- 产物本地/远端SHA逐项断言一致：

| 产物 | SHA256 |
|---|---|
| jar | 028b03602918cd6ca3be3e368a61f5bec1b12421a32a6947bfcad28c9ef5fb46 |
| /hify index | e59967190b33b4235393c9da38cdf9aa1e2be2b891bb4fa8925a1e35fa37e44b |
| nginx snippet | 6c43b0a0f431ac4fa27ff0f2d48e71c02e4ce15a5aa607dbbc2714d0cf132281 |
| 已复核安装器 | 93352ed7c3d9eae0b31e9b5a1dc7daed5c3c784d8d4ad4a82e0d481bb74cbf1a |

## 运行实测

入口 `https://118.196.123.132/hify/`。证据根 `harness/evidence/SPEC-DEPLOY-006/SPEC-DEPLOY-006-20261004T015317Z-986538b0/`：

1. Run输入直连真实PG：20001 UTF-16字符、消息NUL、resume.runId NUL、gapId NUL均400/40000；每个key的只读lookup为404且no-store。合法计算6*7得到COMPLETED和42，同key重放200同Run。保留自建合成会话/Run（无会话删除API），不删既有数据。
2. Playwright真实132 `chat.spec.ts`与`chat-time.spec.ts`：2 passed，6.7秒；calculator、问候后三轮时间/SSE展示。使用Mock模型，不是真实外部模型验收；trace关闭。与输入smoke合计6个新Run，COMPLETED从37到43。
3. 上传脚本在直连28080及/hify HTTPS两路均通过：2MiB/恰好10MiB为202；10MiB+1/13MiB为413且JSON41300。成功后回读字节数、单个合成chunk，检索同时断言自身documentId和短句（不是只看非空）。仅归档自己创建的文档/KB，随后聚合查询确认activeSyntheticKb/Docs=0。
4. 收尾无RUNNING Run/Workflow，无PENDING/RUNNING索引任务；健康200、V23全success。可用空间1855704→1626236KiB（约1.55GiB），仍96%使用；没有删除任何历史备份或其他服务数据。

HTTPS仍自签，浏览器与该次smoke显式关闭证书验证，非证书指纹固定，非公有CA证明；生产客户端TLS策略没改。HTTP直连不涉及TLS，smoke输出中的tlsCertificateValidation=true只是未启用忽略开关，不能解读为HTTP已TLS。

## 负面结果与边界

首个远端预检在scp尚未完成时发出，release.sha256不存在而退出1，发生在systemd作业启动之前，未停服务。保留remote-start.log。等待scp退出0后，三项SHA全部匹配才启动持久作业；没有重写首轮记录。

本次成功不能关闭SPEC-DEPLOY-004的异常响应ID清理、未来V24目标自动识别、索引并发准入等边界。索引本轮发起前显式检查无在途任务，不承诺检查与停服间不存在新任务。安装器主流程断管道仍fail-closed，systemd/journal降低该风险，不等于脚本无失败路径。备份同盘且未恢复演练；上传/管理字段NUL仍归INPUT-HYGIENE。未验证真实供应商、复杂MCP、鉴权、多实例、所有接口错误排列。

最终本地harness/frontend门禁另记；既有547项后端证据按源树等价复用，没有声称在本轮重跑Maven测试。

最终本地门禁b231424：schema3、harness/frontend passed，50项Python测试及前端typecheck/build通过。此门禁默认根路径构建，与发布前单独/hify构建分开；远端匹配的是source-identity中发布产物SHA，不是后来根路径构建覆盖的本地dist。应用与前端源码未再改变；上述命令日志SHA见log-summary.json，原始日志不提交。
