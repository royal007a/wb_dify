# CONTEXT-AUTHORITY-001：固定旧格式的消费回归（局部证据）

测试源码固定在 **9221b64**，新增 `ContextAuthorityGoldenTest` 与 backend 预期清单（2 项）。
产品 src/main 没有永久改动；不是来源权限门禁已实现，更不是完整任务验收。
expected 来自 4480220 已提交的 19 份 05ab7a0 原文，不运行采集器、不重写 expected。

## 实际验证的路径

- H2/Spring 注入真实服务、仓库、ObjectMapper。先直接插入旧 checkpoint 三份 JSON，
  调生产 restoreCheckpoint；以**新的 checkpoint_id** 调 persistCheckpoint，消息字节一致，
  plan/context 按 JSON 树一致（保留类型、数组顺序、旧 UUID/时间，不要求 Map 顺序一致）。
  原始行的三个字段仍逐字不变。反射只用于这两个生产私有方法和旧知识正文组装器。
- 直接插入旧 canonical model/tool JSON 和固定 digest/recovery，再由生产 port replay。
  之后再次 commit/commitTool，必须是幂等重放，原 JSON/摘要不得被改写。
- plain 的 4 个旧 det_ 从固定 bindings 插入，由真实 CanonicalDetailReader 回读原文；
  显式调用 indexer 验证仍返回这 4 个身份/原 revision。knowledge 必须返回 0 个。
  不只依赖 writer：它会吞 index 异常，所以这里额外直接断言 indexer。
- 正确旧前缀成功；错误前缀、recovery JSON 被改、canonical 被改三个对照均拒绝。
  plain 的旧明细在其源 revision 被破坏后也拒绝。没有实际模型/工具调用或生产数据。

## 执行记录（每次独立报告目录）

| 文件 | 退出码 / 结果 | 含义 |
|---|---|---|
| attempt1-duplicate-checkpoint.log | 1；2 errors，0 failures | 我写的夹具把同一个 checkpoint_id 再插一次，撞唯一约束；不是产品缺陷或有效突变红灯 |
| green.log / green-junit.txt | 0；2/2，零失败/错误/跳过 | 改成新 checkpoint 身份后完整两形态通过 |
| mutant-invalid-cwd.log | 1；没有执行测试 | 命令误在仓库根运行，未找到 reactor；无效尝试，不能计入突变 |
| mutant-canonical-role.log / mutant-junit.txt | 1；2 failures，0 errors | 精确补丁把 checkpoint writer 的 system 改存 context_data；两形态均在测试 :82 的旧字节断言变红 |
| restored.log / restored-junit.txt | 0；2/2，零失败/错误/跳过 | 原样撤回补丁后复跑通过；src/main 与 1572699 无差异 |

有效突变的确切内容保存在 `mutant-canonical-role.patch`。它只说明本次固定金样能发现
该种**误写 canonical 角色**，不是“资料提权防护”的突变；后者尚未实现/测试。
恢复轮 Maven Total time 为 2:23、JUnit 为 112.9s，首轮绿灯 Maven 为 18.082s；观察到
变慢但根因未确认，没有对照诊断、没有线程 dump。mymacclaude 报告的 swap/load 属于
对方同时观察，未在此独立测量，不用于归因。

## 命令与身份

以下为本次执行调用的转录（不是日志自身含有完整 argv 的声明）：在 backend 下执行，
每轮仅替换 reports 路径并分别保存 stdout/stderr；无效 cwd 那轮例外。

```sh
MAVEN_OPTS='-Xmx768m' \
JAVA_TOOL_OPTIONS='-Xmx512m -Dhttp.nonProxyHosts=localhost|127.*|[::1] -Dhttps.nonProxyHosts=localhost|127.*|[::1] -DsocksNonProxyHosts=localhost|127.*|[::1]' \
mvn -B -pl hify-app -am -Dtest=ContextAuthorityGoldenTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dhify.test.reportsDirectory=/tmp/hify-authority-reader-lgszQS/reports-restored test
```

- 其余独立目录依次为 `reports`、`reports2`、`reports-mutant2`。原始 XML 留在本机 scratch，
  不提交 JVM 属性；这里提交原始文本日志及 JUnit txt。
- 测试源码 SHA256：`f35ad6ff6dd071973cfd1b1ad0f66aa5d1651d1d98cca55aab81172776d401de`。
- 预期清单 SHA256：`9436e7eb5d938caad718d638e19f1a87370a947bcf10128751ea1d016a6b2afb`。
- 全部本目录日志/报告/补丁以 `SHA256SUMS` 绑定。原始错误和无效命令未删除，日志仅含
  合成测试文本；未修剪原始尾空格。SHA 不能独立证明构建环境或运行来源可信。

## 尚未覆盖

没有 PG、六范围、HTTP adapter 请求体、QueryLoop 来源门禁、事件重建、来源链、缓存、
关停交错、资料模型视图降权等验收。两例各包含多条断言，不算数十个独立测试。
新 writer/reader 的兼容回归已进入常规 backend 清单，但当前仍是既有产品代码；后续
产品改动必须继续跑它。本证据不代替设计 §4.1–4.8 的其余要求、不授权部署。
