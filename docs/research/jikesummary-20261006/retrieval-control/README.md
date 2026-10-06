# 检索控制：服务级红灯

2026-10-07，本地独立JDK17编译当前RunApplicationService及RunKnowledgeControlTest；JUnit Platform 1.11.4执行5个用例，2成功、3断言失败、0跳过/中止。进程退出1，不是编译错误。source HEAD a7dc3d1，产品源码与c45c19a一致。

- 首库用latch挂起，调用公开cancel，再释放成功/失败两种结果：两条均实际进入第二库，违反后续库零调用要求。
- createdAt已过期的Run恢复：实际进入两个库，违反过期零检索要求。
- 正常优先级顺序、事先取消两项成功，说明夹具不是所有入口都被挡住。

原始输出service-red.log，SHA256 `0e03a0c18bdda58caa515e86efad4ae285a4a05e3935c6588f82db2415d04453`。Runner与命令在本机scratch（LightweightTest.java、run-service-probe.py）；编译输出是独立service-probe目录，依赖来自主工作树已有target/classes与Maven缓存，不修改主门禁产物。日志包含实际源码哈希。

这是小范围服务准入证据：模型、QueryLoop、知识port和持久层均为桩，不能证明真实HTTP被取消、SQL事务终态或完整门禁通过。两条取消测试先在第二库计数处失败，后续模型/事件断言尚未被执行，不把未到达的断言算作已验证。修复后须继续跑同一测试，并另补控制身份、剩余预算、真实HTTP、停机恢复与完整门禁。
