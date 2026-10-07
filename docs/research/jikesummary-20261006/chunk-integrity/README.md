# C178 分块完整性：生产函数的隔离特征探针

读课后发现两处确定性边界缺陷。这里记录未修复的现状，不宣称产品通过验收。
来源章节及PDF指纹见reading-notes的C178与source-inventory；课程全文不提交。

受测源码固定e7d43b17882dbf164915d73b271411c439a995da，生产类
`RecursiveTextChunker.java` SHA-256：
`b7b34d715ce6ba9e84561435fd191641e13223523ace8e8a2bbfffb1af5737c0`。
探针不修改该类，直接用JDK17编译。`run-probes.sh`从固定Git对象读取源码并核对
完整SHA后再编译，避免无意测到后续修复版本。无Maven、Spring、数据库或模型。

## 实测

- ASCII 512字符、maxTokens=64、overlap=16：两块估算分别64和80，后者超过64。
  原因是主切割按256字符完成后，直接追加64字符carry；没有给overlap预留空间。
- 255个a、一个emoji、255个b：合法UTF-16输入产生孤立高代理项，首块不合法。
- 191个a、一个emoji、63个b、256个c：主块本身合法，但carry从低代理项开始，
  第二块不合法。因此不能只修主切割位置而漏掉重叠区起点。
- 10组矩阵包含空白、恰好上限、超一位、零重叠、近上限重叠、中文与emoji；
  全部是“观测与已知缺陷一致”的断言。退出0不是“缺陷已修复”。

复现：

```sh
HIFY_JAVA_HOME=/path/to/jdk17 sh docs/research/jikesummary-20261006/chunk-integrity/run-probes.sh
```

`probe.log`保存本轮重新运行输出；指纹在manifest.json。
生产DocumentIndexingService确实调用该类，但本次没有验证DB往返、线上损坏或
真实tokenizer；tokenCount只是UTF-16长度除4向上取整，不是中英文真实模型token。

## 独立优化切片的验收要求

1. 以当前函数反例建立先红后绿的回归；overlap纳入原近似上限，不能只改tokenCount掩盖越界。
2. 两种Unicode切割边界都保持代理对完整，空文本/无标点/多段与最大合法overlap可终止。
3. 扣除明确约定的trim/换行归一化后无正文遗漏；避免修上限后丢字或循环不前进。
4. 新索引的ordinal/digest可变化，但不原地重写旧发布语料和会话引用；补旧快照回读。
5. 生产索引入口的H2/PG往返另测，不用函数探针代替；近似预算与真正token限制分开声明。

先收尾CHAT-RETRIEVAL-CONTROL-001，再登记实现切片；父子索引/HyDE/迭代检索仍是
研究候选，未在本次引入。此次提交只加研究与探针，不改src/main、不部署。
