# runExecutor 独立突变与线程复用补强

2026-10-07，本机离线窄验证。源码基线 `164abe4d955aa964a227b6f1571620c1d7602ec7`，
backend 内容与 `d68bf22` 相同。没有修改产品工作树，没有运行数据库、浏览器、模型或部署。
测试窗口已在结束后交还 hify-cc。

## 方法与结果

在隔离 archive 副本中，仅删除 AsyncConfig 的
`executor.setTaskDecorator(RequestLogContext::wrap);`。删除后和工作树比较，
488 个受跟踪 backend 文件中仅此文件不同；恢复后 488 个文件全部逐字相同。

| 阶段 | 目标 | 结果 |
| --- | --- | --- |
| 突变，16:44:01 +08:00 开始 | AsyncConfigTest#productionRunExecutorPropagatesCorrelationAndRemainsBounded | exit 1；1 项、1 failure、0 error/skip；耗时 15.614 秒 |
| 恢复，16:44:47 +08:00 开始 | AsyncConfigTest,RequestCorrelationTest | exit 0；分别 1/1、8/8，0 failure/error/skip；耗时约 13.4 秒 |

红灯是 `AsyncConfigTest.java:16` 的 AssertJ `AssertionError`：实际 MDC map 为 null，
无法满足 requestId 的键和值断言；**不是空指针异常、编译失败或环境启动错误**。
恢复后 Maven 于 16:45:00 +08:00 完成。

RequestCorrelationTest 的既有 reusedWorker 方法已在 d68bf22 补入两个不同非空 ID
连续复用同一 Thread 的断言；同时保留无 ID、白名单和取消后 body 零调用。
此次 8/8 包含新断言，但没有对这一新增断言做专属突变。

## 重现

JDK `/Users/weberzhao/software/jdk-17.0.19.jdk/Contents/Home`；
`MAVEN_OPTS=-Xmx256m -XX:ReservedCodeCacheSize=96m`，
`JAVA_TOOL_OPTIONS=-Xmx384m -XX:ReservedCodeCacheSize=96m`，PATH 优先该 JDK。
在隔离源码根目录执行：

```sh
mvn -o -B -f backend/pom.xml -pl hify-app -am \
  -Dtest=AsyncConfigTest#productionRunExecutorPropagatesCorrelationAndRemainsBounded \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 恢复 AsyncConfig.java 后：
mvn -o -B -f backend/pom.xml -pl hify-app -am \
  -Dtest=AsyncConfigTest,RequestCorrelationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

原始日志和文本报告原样保留，哈希见 summary.json。恢复前保存红灯报告，避免重跑覆盖。
AsyncConfig 原文 SHA-256 为 `74a80b042d1f8fd6546c89976be27e9522fc1447f552088acf7f42a3d62ceb00`；
RequestCorrelationTest 为 `835633e2f7b103668d51ebab88b0b569ae4eea36a48269cf4d738ab5999bb06e`。

## 边界

- 这是生产配置工厂的直接实例化测试，不是 HTTP→Run→LLM 端到端关联验收。
- 本次只有上述 9 项；没有重跑完整六范围。05ab7a0 的完整门禁仍是历史基线，
  d68bf22 新增的测试断言不能追溯算进该门禁；产品源码未变。
- 取消和 AbortPolicy 专项仍只覆盖 llm 池，未逐池突变。
- 证据等待独立复核，不修改两个 OBS 的 pending 状态，不复制 CHAT verification 冒充新 run。
- 日志经过有限敏感模式检查；不是脱敏完整性认证，仍包含本机临时路径。
