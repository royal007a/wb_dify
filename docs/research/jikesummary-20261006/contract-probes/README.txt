课程契约与并行边界探针（2026-10-07）

范围：C032/C051/C067/C096 四篇阅读稿已整合到 ../reading-notes.md。
来源 PDF 字节摘要重新对照 source-inventory.json 一致；未提交课程全文。
映射源码基线 11e7be5980afac5eb409e4a4711c16fbadc965af。
到本次验证的 7764bf2088058a52d9dab483381761ce8c876f0b，src/main 没有变化。
review 已请求，但本文件写入时尚未收到这四节的最终结论。

送审草稿身份（保留在私有 scratch，不把 hash 当作批准）：
C032 3870c84e647f9081d0048a219b9871628a4819cafef622bb248579f8c056497b
C051 36e1a90eb6f0870f1ce7cc3ecee91013e8ccbcf001bfcc3768a2133835698237
C067 a598329e6d9728d818a44e0fa90b725ca443283548420a4fc4c75c44f8ee551c
C096 6d39d348ee4d8dfe46ee4785f1afb891aff46aec740690e2d05d44e3bad2c663
整合时压缩了重复表述，并合入 C067 后续核对：WorkflowCanvas 已有外部内容与
结构不等于事实的提示，不再建议重复添加。新正文不是这些草稿的逐字副本，
不能把之后针对旧草稿的 review 自动外推成整合稿全部通过。

一、C067-gate-probe.py
转写课程第7–8页的表达式，内存 SQLite 合成台账，不调用作者仓库、Hify、模型
或支付。9 个观察包括错误输入被接受/异常逸出；不是 9 个安全功能验收通过。

运行（仓库根目录）：
python3 docs/research/jikesummary-20261006/contract-probes/C067-gate-probe.py

2026-10-07 04:33 在实际仓库副本再次执行，exit 0；输出与所存 JSON cmp 逐字一致。
源码 SHA256 e093382992e85b1a80e521070303bfb3458c88f4425062baed33d437db89b671
JSON SHA256 af2caea565024085a9dd6d679e81ef197dbb2cdaaf8b6cae1a161e8c49761872

二、ParallelLeaseProbe.java
实际使用 ToolExecutionLease 和 ExecutionControl，转写 RunApplicationService
单活跃 map 的 put/remove。没有启动 DB、Spring、模型或网络；四个观察证明
直接并行不能沿用单活跃 attempt 语义，不声称当前串行实现有并发缺陷。

运行（先有与固定源码匹配的生产类，仓库根目录；输出目录用新临时目录）：
javac -J-Xmx64m -cp backend/hify-common/target/classes:backend/hify-tool/target/classes \
  -d <scratch-classes> docs/research/jikesummary-20261006/contract-probes/ParallelLeaseProbe.java
java -Xmx64m -cp <scratch-classes>:backend/hify-common/target/classes:backend/hify-tool/target/classes ParallelLeaseProbe

JDK 17.0.19；2026-10-07 04:33 重放 exit 0，输出 cmp 逐字一致。
源码 SHA256 2067f13619c77bac1505eec4f571e0a3646ed783de2ca24de7d8af4bc3c873b1
JSON SHA256 77474f1c804f370f1cf2190dc858111da2fb7c7bad1060f94b0b717f7cfacc89
输入 ToolExecutionLease.class SHA256
aa039cc8b1380cf0027138bb0c5a46f44093fd45a8437862185a6c84b0676596
输入 ExecutionControl.class SHA256
ace00a1c7f2da2ceb94b910dc6fc87e38993ea2294f6ea10c599ff2911c2b321

类哈希只识别本次输入，不证明完整可复现构建，更不替代服务级故障/恢复验证。
两项探针不是新工具并行实现、事实校验服务或失败经验库的验收。

三、C066-ready-budget-probe.py（04:46补充）
C066已读全文与图示，新增映射见reading-notes，review待结论。
隔离转写展示的while/ready循环：预算0执行0项，预算1遇到三项ready执行3项。
不执行作者仓库，所有handler均用列表记录模拟，没有Hify或外部副作用。
python3 docs/research/jikesummary-20261006/contract-probes/C066-ready-budget-probe.py
执行exit 0，JSON为实际输出；两观察不是生产预算安全验收。
源码SHA256 a074019e7f3d01ed0583b13a3985c687a83906887b6846cf96e9a283b8ce9e12
JSON SHA256 a434f800d1dcd6a445b3faa53385c9ee1c6f043d35245509e18be2cfb0ae4903
私有完整草稿SHA256 f969e270111f961a2ab6782f0b5d360f38cffb8b12820ccc0bae37b315c0d31c
