# 结构契约替代：不再依赖 Git 不保存的空目录

来源：C171/C168 测试设计原则，以及 e7d43b1 完整门禁的实际红灯；原文机制已在
同目录上级阅读映射记录。这里是项目实施，不把课程建议冒充原有实现。

旧红灯原文已提交 e5c244d：`MavenStructureTest` 在干净 worktree 因
provider/controller 不存在失败。七模块的49个旧目录中47个没有 Git 跟踪文件。
主工作树碰巧存在空目录不是有效结构契约；修复不通过 mkdir 伪造它们。

## 覆盖变化

- 保留：父模块集合、实际模块目录集合、既有必需依赖。
- 替换：删除七乘七的空目录存在断言；要求7个精确路径的 public final marker，
  6个精确路径的 public interface port，Chat 的现存 public 入口。
- 增加：实际 package 与路径一致；旧包根白名单；三个拆分包 owner 冻结；
  跨模块顶层类型不能重名；Java 语法错误不能作为有效清单。
- 增加有限依赖规则：common 不依赖业务模块，provider/tool 不依赖 chat。
- 不覆盖：跨模块 import、完整依赖环/effective model、port 方法签名兼容性、
  生成源码/嵌套类型的二进制冲突；不得据此宣称模块化架构全部验收。

复核补充：这里冻结的是包根**前缀**，不是遗留根下的精确包集合。Agent 新增
`com.hify.domain.foo`、Chat 新增 `com.hify.memory.bar`，只要没有形成新的跨模块
同名包，仍会通过；因此不能称为“历史架构债只减不增”的自动保证。解析扫描只含
七个业务模块，不含 common/demo/app；这些模块中的拆分包或重复类型不在本轮
检查范围内。有限 POM 禁止项也不覆盖 knowledge/workflow/agent 到 chat 的依赖。
这些是当前覆盖缺口，不是工程规范允许继续增加技术债；精确遗留包集合/全模块
依赖审计留待独立切片，本轮不临时扩张结构契约以掩盖关停恢复的红灯。

测试 helper 只存在 src/test，使用 JDK17 JavacTask.parse，不启动应用，不解析
依赖符号，不运行注解处理器。`ModuleStructureContractTest` 的20项使用隔离合成
文件；实际仓库由 `MavenStructureTest` 的2项检查。错误反例断言具体原因。

## 已执行的窄验证

JDK17.0.19，本机已有 JUnit5.11.4/platform1.11.4、AssertJ3.26.3；无联网、
Maven、Spring、Docker、浏览器。第一次独立运行22/22成功、零skip/abort，约5.4秒。
第一次日志 `first-run.log` 保留；之后只将两个测试方法名里的“Tracked”改成
“Source”，因为解析器本身不查询 Git；跟踪文件身份须通过干净archive证明。

复现（路径由调用者明确指定）：

```sh
HIFY_JAVA_HOME=/path/to/jdk17 \
HIFY_M2_REPOSITORY=/path/to/.m2/repository \
sh docs/research/jikesummary-20261006/structure-contract/run-offline.sh
```

脚本只编译这三个测试类及独立launcher，编译输出放mktemp目录并保留供排查。
它要求恰好22项成功，发现数、skip、abort、容器失败均作门禁。不会触碰原Maven
target或服务。后续干净archive复验和突变证据另记，不用第一次绿灯替代。

完整门禁尚未通过：H2/PG启动越过45/60秒的两条error仍需定位，本补丁没有修改
它们，不合并main、不部署。

## 固定16b1959的干净archive及突变

`archive-16b1959/summary.json`固定完整源码SHA，记录原始archive和每份日志SHA。
先通过git archive解包，未依赖本机未跟踪空目录或源文件；两个实际测试类22/22
通过。其后每次仅修改隔离副本中的一处检查，完整编译后由同一JUnit launcher执行：

| 突变 | 精确失败用例/数量 | 红灯类型 |
| --- | --- | --- |
| M1 不校验包根 | newlyIntroducedRootFails，1项 | 断言失败 |
| M2 不校验拆分包owner | newSplitSubpackageFailsEvenInsideAllowedRoots，1项 | 断言失败 |
| M3 common允许业务依赖 | commonCannotAcquireBusinessDependency，1项 | 断言失败 |
| M4 provider/tool允许chat | providerCannotDependOnChat、toolCannotDependOnChat，2项 | 断言失败 |
| M5 去掉port声明检查 | 缺失、类型、文件位置、注释伪造，4项 | 断言失败 |
| M6 去掉marker声明检查 | 缺失、声明名、非public、非final，4项 | 断言失败 |

6个突变全部发现22项测试，均执行到预期断言失败，而不是编译失败/NPE。
每次恢复原helper后再做下一项；开发工作树从未写入这些突变。
`mutate-offline.py --commit 16b1959 --output <尚不存在的证据目录>`可复现；需要
前述两个环境变量、Python>=3.12。它保留archive副本和编译目录，不触碰服务。
本次只证明结构契约替代及其防护有有效反例，不能关闭两条关停恢复超时红灯。
