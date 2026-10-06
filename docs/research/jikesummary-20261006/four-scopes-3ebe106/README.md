# 3ebe106：四个选定范围通过，不是完整验收

2026-10-07 06:10:31 CST，原始 `verify.sh` 进程 exit 0。被测 HEAD 为
`3ebe106faf9dec9975ae0bf1115bf777974dcc8f`，验证开始和结束时产品/测试源码工作树干净，
期间未修改或提交源码。schema 3 invocation 为 `fe6bb869-1069-426d-b4aa-f75aec62788a`。

```sh
JAVA_HOME=/Users/weberzhao/software/jdk-17.0.19.jdk/Contents/Home \
MAVEN_OPTS='-Xmx256m -XX:ReservedCodeCacheSize=96m' \
JAVA_TOOL_OPTIONS='-Xmx768m -XX:ReservedCodeCacheSize=128m -Dspring.test.context.cache.maxSize=2' \
./harness/verify.sh --scope harness,runtime,eval,frontend --base 3ebe106 \
  --evidence-dir /tmp/hify-four-scopes-20261007.kv2zEE
```

| 范围 | 此次可核对的结果 | 边界 |
|---|---|---|
| harness | 状态、进度、API清单、shell语法通过；Python原日志明确84项、OK | 测试夹具里的 `Maven evidence: failed` 是反例预期，不是本轮Maven范围失败 |
| runtime | 7类35项，0 failure/error/skip/flaky，underfilled为空 | 包含RunFlow、ChildAgentTask、ContextMemory三类H2集成；不包括RunShutdown重启测试 |
| eval | 7类24项，同样零失败/错误/跳过/flaky | 合成意图、上下文和召回评测，不是真实模型质量基准 |
| frontend | npm ci、typecheck、build全部exit 0 | 仍有>500kB chunk警告；不是浏览器e2e、线上资源或部署验证 |

`*.log`、`verification.json`、两份 `*.tests.json` 为原运行目录逐字复制。
14份文本JUnit报告也保留。原manifest中的绝对路径未改写；离线复核时按文件basename
在本目录找对应文件。原始XML留在本机隔离目录，仓库只有其hash、结构化摘要和文本报告，
因此仅凭此目录不能独立重算XML hash；这条边界不应隐去。

核对的SHA-256：

- verification.json：`5475b67899a78f78aa568993964517e85e1ac115af00c5526ad1c8ac90886144`
- runtime-tests.tests.json：`333bd10c1d5d3b820f4ca2f7f1c63d0ed57b43b42303d0bbe39af3e28b19fd66`
- intent-context-recall-eval.tests.json：`d22b2c82e9732da6738518470a36574167e8d22b532526958871fa9a5f09cfd1`
- 其余日志hash在原manifest每步的logSha256字段中。

被测Git树：backend `ca0bd54331f30d2b3f8161526e56801fa9cad0cf`，
frontend `931a8823bf0513777fc88f0d3447daef94e0e71d`，
deploy `d3b11e660e479c745177f9696e30e5b134802f89`。
结束后上述三目录的tracked diff为空，SHA-256为
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。

本轮没有运行Docker/PG、完整backend、浏览器或任何132操作，没有停止共享服务。
以前的common中断红灯、H2重启红灯、两次完整门禁失败继续保留；本次结果不能替代它们。
CHAT-RETRIEVAL-CONTROL-001仍pending，六scope与独立review要求不变。
