# e7d43b1 六范围完整执行：失败，不是验收通过

本次从2026-10-07 13:26:19到14:20:01（北京时间），runner自然退出1；未中止、
未修改源码或等待时限。任务机器状态由runner改为blocked，研究目标仍继续推进。

证据目录：`harness/evidence/CHAT-RETRIEVAL-CONTROL-001/CHAT-RETRIEVAL-CONTROL-001-20261007T052619Z-88abd54f/`。
schema3 invocation `dbbaed46-bcf8-49ac-b7ac-f75b02ae9513`，六scope均实际执行。
verification SHA-256：`b0b829d51eccd9c6cf84d3313b2f4b225ddfbf098ff0b6b0fd3b47f29e3f28fe`。

| 范围 | 实际结果 | 边界 |
| --- | --- | --- |
| harness | Python 84项通过，其他检查exit0 | 原始Python日志保留 |
| migration | 126项，0 failure、1 error | 15类齐全，无skip/flaky/underfilled |
| backend | 770项，1 failure、1 error | 107类齐全，无skip/flaky/underfilled |
| runtime | 35项通过 | 7类，零fail/error/skip/flaky |
| eval | 24项通过 | 7类，零fail/error/skip/flaky |
| frontend | typecheck与build退出0 | 有大包体警告；不是浏览器E2E |

这些范围含重复测试，不把数字相加宣称独立用例数。逐类重新求和与四份摘要一致，
所有step log的SHA与verification一致。提交13份日志、7份JSON和136份JUnit文本
报告；136份原始XML留在本次本地reports目录，避免提交大量JVM属性/路径。
摘要中的XML SHA保留，不宣称读者仅靠文本就能重建XML。初步凭据模式扫描没有
发现长MCP token、provider key、JWT、私钥头；这不是完整保密认证。

## 两种失败，分别处理

1. backend的`MavenStructureTest.businessModulePackageSkeletonsAreConsistent`断言
   provider/controller目录存在。测试从9174027未改，要求七模块各有七种旧目录。
   固定Git树中47/49个目录无任何跟踪文件，干净worktree不存在；主工作树恰好
   留着这些空目录。此项是确定的干净检出可复现问题，不归因于负载。
   ENGINEERING §1已使用api/application/domain/infrastructure，并禁止为空分层造类。
   已与mymacclaude对齐替代契约：真实marker、既有port、包声明/路径，冻结旧包根
   和三种split package的owner集合；保留原POM/必需依赖断言，加有限反向依赖负例。
   不通过mkdir补空目录，不声称保留了原有空目录断言；尚未应用该修复。
2. migration的PG过期恢复方法触发原60秒限制：首个上下文启动81.365秒，最终
   业务恢复断言虽到达，JUnit仍判error。backend内H2过期恢复方法触发原45秒：
   首个上下文启动46.718秒，返回时interrupted=true；随后entered.await被中断，
   不是独立等待满10秒后的模型无响应。
   同一backend范围的PG恢复类3/3、H2及时模型恢复对照均通过。这些不能覆盖先前
   两条error，也不能倒推出失败根因。没有把超时移出方法或放宽时限。

当时host存在大量swap，后续jstat显示无Full GC，单次线程快照位于Hibernate装配；
这些只能说明观察到的资源/执行状态，不证明内存泄漏或某其他项目造成失败。
下一步保留失败原文，先修确定性的结构契约，再在协调后的窗口验证；不盲目重跑。

## 身份与资源

受测HEAD：`e7d43b17882dbf164915d73b271411c439a995da`。
终止后重算源码tree：backend `ca0bd54331f30d2b3f8161526e56801fa9cad0cf`，
frontend `931a8823bf0513777fc88f0d3447daef94e0e71d`，
deploy `d3b11e660e479c745177f9696e30e5b134802f89`；
三目录工作树diff的SHA为`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。

Java17.0.19；Maven进程-Xmx256m，测试JAVA_TOOL_OPTIONS为-Xmx768m、CodeCache128m、
Spring test context cache最大2。专属colima为1CPU/1GiB/12GiB，显式Docker socket；
未切换全局context。确认测试容器退出后，于14:20:38只停hify-verify-20261004，
default/dify未动，重测试窗口交还mymacclaude。不合并main、不部署、不宣称任务完成。
