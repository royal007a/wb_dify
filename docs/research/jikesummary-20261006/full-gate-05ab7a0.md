# 05ab7a0 六范围门禁：本次通过，未部署

2026-10-07 15:51:13 至 16:05:17（北京时间），同一个 runner 自然退出 0；
没有重启本轮验证，没有在运行途中改 HEAD、产品代码、测试或时限。
受测 HEAD：`05ab7a06f485a5d800009dedbbe566508bd9e775`。

证据目录：`harness/evidence/CHAT-RETRIEVAL-CONTROL-001/CHAT-RETRIEVAL-CONTROL-001-20261007T075113Z-9261da6c/`。
schema 3 invocation：`d632c535-5559-4683-976b-7944ac359a99`。
verification SHA-256：`2fe9688ae24707637059abc9dff607223fd9e6bf1e13fcddcafb97d9df90266a`。

| 范围 | 实际结果 | 证据边界 |
| --- | --- | --- |
| harness | Python 84 项，其他检查 exit 0 | 原日志记载 Ran 84 tests / OK，不是静态数方法 |
| migration | 15 类、126/126 | 包括 RunShutdownPostgresTest 3/3 |
| backend | 109 类、796/796 | 包括 H2 关停恢复 7/7、PG 关停恢复 3/3、结构契约 22 项、启动诊断 6 项 |
| runtime | 7 类、35/35 | 显式 runtime scope |
| eval | 7 类、24/24 | 固定离线评测，不是新真实模型质量评测 |
| frontend | typecheck / build exit 0 | 保留大包体警告；没有浏览器 E2E |

四份 Maven 摘要均零 failure/error/skipped/flakyAttempts，underfilledSuites 为空。
逐类重新求和与 totals 完全一致；12 个步骤日志 SHA 与 verification 一致，四份
tests.json 的 SHA 与 testSummarySha256 一致。各 scope 含重复测试，不把这些数字
相加称为独立用例数。四份摘要 SHA 分别为：

- migration：`a2f61969ac3e476abbad23c75d9345d71c9927d93574dedbd2470f60a3fa2388`
- backend：`ac214d69c0256e5b01d4e79c124b08d56118e9e842ed00bc3b1219c573dfd9cf`
- runtime：`9ec3466fe69fe187bb61ebce8cbf19ba42a48888ec663b099f88b0d4e4e84390`
- eval：`fefaf0edf00e0eb7a945df03839b2d543625d4bddb6101cbd189b28ca181b300`

为保留此前关停/启动失败的对照，本次提交 13 份原始日志、138 份 JUnit 文本报告
及 JSON manifest。138 份 XML 留在本地本轮 reports 中，不提交包含 JVM 属性的全文；
摘要保留 XML SHA，不宣称仅靠文本可重建 XML。已扫描待提交文本中的长 MCP token、
provider key、JWT 和私钥头，未发现匹配；这只是模式检查，不是完整保密认证。

## 源码、命令和环境

三棵源码树与 `git ls-tree 05ab7a0 backend frontend deploy` 对齐，见
本轮 `source-identity.json`。三目录 tracked diff 为空、untracked source 为空；
这证明源码身份，不证明确定性二进制构建。runner 入口为：

```sh
./harness/run-task.sh CHAT-RETRIEVAL-CONTROL-001 -- true
```

运行时环境：JDK 17.0.19；MAVEN_OPTS 为 `-Xmx256m -XX:ReservedCodeCacheSize=96m`；
JAVA_TOOL_OPTIONS 为 `-Xmx768m -XX:ReservedCodeCacheSize=128m`、Spring test context
cache 最大 2、`hify.test.startup-diagnostics=true`、独立 GC/safepoint 日志。
Docker 显式使用 `hify-verify-20261004` 的 socket，Testcontainers host 为 127.0.0.1，
socket override 为 /var/run/docker.sock；verify.sh 追加本机 nonProxyHosts。
每步实际命令在 verification 中，测试清单及其 SHA 在 tests.json 中。
完整外层日志与 GC 原文暂留本机 `/tmp/hify-six-scopes-05ab7a0.47QSzQ/`，
它们不是已提交的额外验收证据。

只启动自己的 Colima profile（1 CPU / 1 GiB / 12 GiB），全局 Docker context 仍为
colima。确认专属 socket 下测试容器已经退出后，16:05:38 只停止该 profile，
default / dify 未改；随后明确交还重测试窗口给 mymacclaude。

## 这轮绿灯能说明什么、不能说明什么

本次包括此前失败的 H2/PG 真实关闭—重启正反例，原 45/60 秒方法/类时限、
10/5 秒业务等待及断言未放宽。旧 e7d43b1 完整红灯、716784d H2 红灯、common
早期中断红灯均保留，不覆盖历史。结构空目录旧契约有意被替换，其覆盖差异和
6 项突变另见 structure-contract/README.md；不能称旧断言全部保留。

启动诊断、结构契约和时钟预算改动已有分段只读复核；本次完整证据仍待 reviewer
核对。绿灯只证明这一固定源码在本轮配置下通过，不证明此前慢启动根因已经
定位或根治。没有关闭 AOP，没有放宽超时，没有以这次运行替代失败原因分析。

runner 自动将 CHAT-RETRIEVAL-CONTROL-001 记为 completed，计划相应归档；
不因此自动完成两个 OBS 任务或整个课程研究目标。尚未合并 main、没有部署 132、
没有运行浏览器或新真实模型调用；预算取消仍是协作式，不保证外部副作用可撤回。
