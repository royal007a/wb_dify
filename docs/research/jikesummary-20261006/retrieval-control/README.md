# 检索控制：服务级红灯

2026-10-07，本地独立JDK17编译当前RunApplicationService及RunKnowledgeControlTest；JUnit Platform 1.11.4执行5个用例，2成功、3断言失败、0跳过/中止。进程退出1，不是编译错误。source HEAD a7dc3d1，产品源码与c45c19a一致。

- 首库用latch挂起，调用公开cancel，再释放成功/失败两种结果：两条均实际进入第二库，违反后续库零调用要求。
- createdAt已过期的Run恢复：实际进入两个库，违反过期零检索要求。
- 正常优先级顺序、事先取消两项成功，说明夹具不是所有入口都被挡住。

原始输出service-red.log，SHA256 `0e03a0c18bdda58caa515e86efad4ae285a4a05e3935c6588f82db2415d04453`。Runner与命令在本机scratch（LightweightTest.java、run-service-probe.py）；编译输出是独立service-probe目录，依赖来自主工作树已有target/classes与Maven缓存，不修改主门禁产物。日志包含实际源码哈希。

这是小范围服务准入证据：模型、QueryLoop、知识port和持久层均为桩，不能证明真实HTTP被取消、SQL事务终态或完整门禁通过。两条取消测试先在第二库计数处失败，后续模型/事件断言尚未被执行，不把未到达的断言算作已验证。修复后须继续跑同一测试，并另补控制身份、剩余预算、真实HTTP、停机恢复与完整门禁。

## 第一轮修复后的窄测

产品补丁82aad0e，control-narrow.log SHA256 `d8137e8622d64b738b498e9db341b9c4be98aed1182271b4652301673a7441db`。5个服务测试、5个ExecutionControl、3个真实QueryLoop父控制、7个SemanticEmbeddings、2个KnowledgeRetrievalService控制边界用例，共22/22，0跳过/中止/失败，退出0。全部在隔离javac/JUnit运行，不是Maven六scope。日志HEAD指向提交前基线3c4daf2，逐文件sourceSha256记录的是未提交的被测补丁，随后作为82aad0e提交；不能把日志HEAD当成无差异测试证据。

run-service-probe.py及LightweightTest.java保存了窄测命令和计数/非零退出检查；前者含当时的本机绝对路径，需具备同一依赖环境才能重放，不是通用构建脚本。首次红灯使用的是单类5项版本，后续runner扩成22项；原始红灯不改写。依赖来自主工作树c45c19a已编译类，当前被改的生产类重新编译到独立输出目录并优先加载；未构建全模块，不能据此宣称完整产物身份相同。

旧Spring集成测试的spy入口已从三参改为四参，并补了恢复后四参检索零调用断言，原断言保留；这些集成用例尚未重跑。当时真实HTTP、迟到正文、独立突变、完整门禁与mymacclaude复核仍待完成。

后续HTTP与七项突变、当前27项窄测的原始证据见[round2/README.md](round2/README.md)。完整门禁和最终review仍未完成，任务保持pending，不部署。
