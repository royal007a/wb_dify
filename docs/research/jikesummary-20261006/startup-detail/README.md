# 定位慢启动：可选的测试内诊断，而不是修改时限

## 当前事实与待证假设

e5c244d保留的完整失败中，PG过期恢复首个上下文启动81.365秒，H2相应方法
46.718秒；分别越过原60/45秒方法时限。相邻上下文明显更快。现有日志只证明
慢发生在启动期间，不能定位到GC、数据库、Hibernate装配、CPU调度或其他进程。
宿主swap、一次线程采样与后续GC累计量不是根因证明，不能借此放宽断言。

课程C133/C169强调定位单请求的具体阶段、可追溯轨迹与反例。本次仅在关停测试的
SpringApplicationBuilder外加可选诊断，生产代码不动；默认不开启。

## 实现与安全边界

设置JVM系统属性`hify.test.startup-diagnostics=true`才记录BufferingApplicationStartup，
最多2048个完成事件，输出最慢12项，加一行墙钟耗时/当前测试线程CPU耗时/中断标志。
CPU时间不可用时为-1，不开启原本关闭的全局CPU计时。
这两个耗时仅围绕builder.run，不包含此前的Mockito factory/Builder构造；可与原
shutdown-diagnostic整段时间对照，不能单独冒充整个测试方法耗时。

- 原45/60秒、模型等待10秒、退出等待5秒以及所有恢复/终态断言保持原样。
- 不把启动移出@Test，不预热、不懒加载、不替换实际应用入口/数据库/生命周期。
- 只打印固定白名单的stage与beanName，其他名字记为other。任意tag、URL、
  密码和异常文本均不输出；最多13行。诊断RuntimeException不覆盖原结果/异常。
- 只覆盖RuntimeException隔离，不承诺在VM致命错误（例如OutOfMemoryError）时仍可靠。
- 完成事件有嵌套，耗时不可相加；未结束步骤不会进入快照，buffer满也不代表完整。
  CPU是当前线程，不是全进程，不可用CPU差值直接推断外部瓶颈。
- 输出发生在builder.run返回或抛错之后；永久卡住时不会有这份摘要。下一次受控
  运行另在40/55秒采集仍存活的本次测试JVM线程栈，覆盖未完成步骤；只定位本次
  Maven的子进程，不扫描或干预其他项目。线程栈保存在私有目录，审查后再决定
  哪些可提交，不采集命令行/系统属性/环境变量或堆内容。
- 开启记录有额外开销，本轮只做诊断准备，没有把它当优化效果或根因结论。

## 已执行与未执行

更新：真实 H2 接线已在 `../startup-recheck-716784d/README.md` 所述窄测中执行，
30 项有 1 个到期恢复超时 error，且与其他项目 e2e 负载重叠。下面为最初离线阶段记录，
不能再用其“未执行真实接线”描述最新状态；PG 和完整六 scope 仍未因此收口。

JDK17.0.19，Boot3.4.5/Core6.2.6、JUnit5.11.4。独立helper测试6/6成功（约0.4秒），
原日志helper-first.log保留：默认禁用无输出/不换recorder、白名单脱敏、输出限行、
sink失败保留原异常、sink失败保留成功结果、中断位不清除。
仅编译helper/这六项测试，创建builder但没有run/启动Spring上下文，没有Maven、
数据库、Docker或浏览器。因此未验证本轮新增接线的真实启动输出或关停恢复。
之后只改了注释及移除未使用import，行为代码未改。

复现helper：

```sh
HIFY_JAVA_HOME=/path/to/jdk17 HIFY_M2_REPOSITORY=/path/to/.m2/repository \
sh docs/research/jikesummary-20261006/startup-detail/run-offline.sh
```

下一次协调窗口后，先固定提交，给真实H2/PG正反例开启该属性，同时保存独立GC/
safepoint日志与宿主内存观察；先分析阶段证据再决定是否改产品或测试配置。
所有运行放新证据目录，保留原失败；若窄测绿但未复现慢启动，不宣称找到了根因。
完整六scope依然是交付门禁，本次没有合并main或部署。
