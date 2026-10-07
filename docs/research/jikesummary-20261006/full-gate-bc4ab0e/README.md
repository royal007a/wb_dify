# 当前源码六范围门禁：bc4ab0e

2026-10-07 19:49:29–20:10:43 +08:00。通过，但尚未部署。

- 任务：OBS-CORRELATION-001，正常 run-task 执行 `-- true` 后按任务配置跑六范围。
- 固定 HEAD：`bc4ab0e4afe39939f9bfe5c6bb9a3797d6ebbb50`，起跑工作树干净。
- Run：`OBS-CORRELATION-001-20261007T114929Z-298dada4`。
- 证据目录：`harness/evidence/OBS-CORRELATION-001/OBS-CORRELATION-001-20261007T114929Z-298dada4/`。
- Runner exit 0；verification schema 3，result passed，六 scope 均通过。
- verification.json SHA256：`45fdd23440f086300194601960c8d5c69054fe852fc4e9d740c4dd8df93e3704`。

| 范围 | 类数/记录 | failures / errors / skipped / flakyAttempts |
|---|---|---|
| backend | 109 类 / 796 | 0 / 0 / 0 / 0 |
| migration | 15 类 / 126 | 0 / 0 / 0 / 0 |
| runtime | 7 类 / 35 | 0 / 0 / 0 / 0 |
| eval | 7 类 / 24 | 0 / 0 / 0 / 0 |
| harness | Python 93（原始日志记录）及状态/进度/API/语法检查 | 全部 exit 0 |
| frontend | TypeScript 检查、生产构建 | 全部 exit 0 |

Maven 各范围有重复类/方法，不把四列相加当作独立测试总数。按逐类记录重算四份 totals，
与 verification 嵌入的 mavenTests 一致。12 份步骤日志 SHA 和四份 tests.json SHA 已逐个复算。
138 份逐类文本报告一并保留；原始 XML 留在本机，不提交 JVM 环境属性和请求正文。
command.log 是 runner 的外层任务日志（本次 true 因而为空），不在 verification 的 12 份步骤日志中。

## 身份与环境

backend/frontend/deploy 三棵树在跑完后与 bc4ab0e 比对一致，tracked diff 为空；
source-identity.json 记录树哈希。任务运行期间没有改源码/测试。旧失败 run 与定向复验均保留，
未改旧测试时限、断言，也没有用本次通过解释旧失败的根因。

JDK 17.0.19，Maven heap 256 MiB、测试 JVM heap 768 MiB、Spring context cache 2，
启用既有 startup 诊断和私有目录 GC/safepoint 日志。verify.sh 的 Docker API 1.44、
http/https/socks 三组 loopback nonProxyHosts 均使用正式路径配置。
Testcontainers 只用独立 hify-verify-20261004 socket，不用 default；开跑前 VM 可用
17934496 KiB，未发生 default 的满盘清理。验证 VM 于 20:11 停止，default/Dify 未动。
这些是配置/运行观察，不是跨轮性能对照实验。

## 发布边界

任务 completed 是 runner 根据本次证据正常迁移；独立只读复核已完成，见下节。
这证明当前六范围通过，不证明真实模型、浏览器部署态、132 新包、生产 TLS/MCP/embedding 接通。
OBS-READINESS-001 的独立任务状态未顺带修改。
发布控制器的两个 P2 已通过只读复核；其 P3 窄补强另存，不混进本次 gate。
实际切换还需重新预检空间/在途工作、备份，以及用户明确确认 nginx reload 与失败停服边界。

## 独立复核记录（2026-10-07）

mymacclaude 对固定范围 `bc4ab0e..6ae5b3d` 与 `6ae5b3d..55b6591`
只读复算后报告无 P1/P2。来源为飞书本话题消息 `om_x100b6357de75d8a0b18722523360983`
所串联的复核回复；这里记录对方结论，不宣称对方复跑过门禁或远端探针。
对方按提交 blob 重算 138 份报告、四份摘要与 12 份步骤日志哈希，核对 verification
与机器状态一致、三棵受测源码树未变、旧失败证据未删改。

保留三个限定：

- completed 任务的 planPath 仍在 blocked/；runner 的 move_plan 只搬 active/ 下的计划。
  这是路径与状态展示不一致，不据路径否定本次证据，也不在本记录里手改任务状态。
- frontend-typecheck.log 只有 npm 命令头，成功依据是 runner 记录的 exit 0，
  不是日志里存在逐文件检查清单。harness 日志中的三行 `Maven evidence: failed`
  来自反向用例，不能脱离对应测试结果理解为本轮门禁失败。
- 此次复核放行仅针对代码准入证据与窄检查；不代表已切换 132、已获发布风险确认，
  也不解释此前首次 Run 超时的根因。
