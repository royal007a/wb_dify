# Canonical 旧 writer 金样（已生成，尚未接入产品回归）

2026-10-07。为 context-authority 的历史兼容验收准备。生成器在 1a82530 提交时仅做
静态核对；测试窗口明确交还后，在 08c467e 上独立生成两次，均 compile/generate exit 0，
11 个 JSON 及 provenance 逐字节相同。**这只是旧 writer 的合成金样，不算恢复兼容测试通过。**
本目录位于研究材料下，不参与 Maven 测试，不能代替未来产品回归夹具。

## 固定来源和覆盖

固定源码：`05ab7a06f485a5d800009dedbbe566508bd9e775`。生成器从 git 读原文，
单独编译并优先加载以下生产类型，不从后来修改的 writer 生成 expected：

- JacksonConfig：使用旧 customizer + Spring 的 Jackson2ObjectMapperBuilder。
- RuntimeMessage、KnowledgeCitation：旧消息与知识引用类型。
- RunApplicationService：只反射调用 knowledgeContext / writeMessages。
- CommittedHistoryWriter：只反射调用 write(List)。
- CanonicalDetailReader：只反射调用 write(RuntimeMessage)。

静态核对过三个服务的构造器只保存协作者；探针不会调用数据库/模型/事件或业务流程。
协作者以 null 提供，只允许上述纯序列化方法运行；若旧源码约定变化应失败，不扩展到
启动 Spring 应用。只读 Surefire XML 的 java.class.path 属性，其他属性/输出不抄入证据。
classpath 来自已有编译目录，先逐一比较该目录所有 src/main 文件与固定源码；这只能
证明来源文本相同，**不是复用的 class/jar 确由该源码构建的证明**。

已生成 11 个 JSON 文件：完整历史/消息 checkpoint 两份、模型调用前的前缀一份、
已提交含工具调用的模型响应前缀一份、七条消息各自的 detail JSON。所有输入为合成资料，
覆盖中文、emoji、转义、false/0、旧 RAG system、双工具调用及成功/失败结果。
这里的 checkpoint 指 messages_json 字段，不是完整 checkpoint/plan/contextState。
detail JSON 也不等于实际生成并通过 det_ 授权回读；这些须在后续测试中分别验证。

## 本次执行与重现

输出位于 `fixtures/`，固定 SHA 清单为 `fixtures/provenance.json`。两次执行日志分别为
`generate-first.log`、`generate-second.log`；`diff -qr first second` 返回 0 且无差异。
两次均先比较 356 份生产输入与 05ab7a0，再重新编译六个被调用的旧生产类型。
本次私有目录为 `/tmp/hify-canonical-baseline.5f4sRa/`；输出原字节复制入仓库，
没有经过新 reader 重写。JDK 17.0.19，两次均没有启动应用、数据库、VM 或真实模型。
这 11 份文件尚不在 Maven resources 中；当前没有产品测试自动消费它们。

先确认隔离编译目录及 Surefire XML 仍存在。示例路径是上次窄测的本机临时目录，
并非可移植依赖包；不存在时必须重新准备，不得只手填生成成功。

```sh
python3 docs/research/jikesummary-20261006/context-golden-05ab7a0/generate.py \
  --repo . \
  --dependency-tree /tmp/hify-run-executor-mutation.jFp6EH \
  --classpath-report /tmp/hify-run-executor-mutation.jFp6EH/backend/hify-app/target/surefire-reports/TEST-com.hify.config.AsyncConfigTest.xml \
  --jdk /Users/weberzhao/software/jdk-17.0.19.jdk/Contents/Home \
  --output /tmp/REPLACE_WITH_NEW_PRIVATE_PARENT/first
```

先用 mktemp -d 创建本次私有父目录，first 必须不存在。另用 second 重跑，比较全部
11 个输出和 provenance 的内容；生成器拒绝覆盖已存在目录，子 JVM 独立限堆并设超时。
运行失败的退出码与错误须保留，不能将部分文件当成金样。生成并核对后才提交 JSON、
固定 SHA 和生成结果，原文件不加尾部换行，不用新 reader 重写后再作为旧金样。

本生成器不执行 replay、模型调用、权限门禁、事务或数据库恢复。未来旧金样进入测试时
仍需独立检验 prefix replay、detail digest 与三家模型视图，不能靠 writer 前后一致
就宣称整个恢复协议兼容，也不能用生成器代替六范围正式门禁。
