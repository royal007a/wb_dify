# WORKFLOW-STRUCTURED-001：有界结构化LLM输出

授权：2026-10-05三小时完善目标。先设计、独立review，再实现。前置WORKFLOW-AGGREGATION-002通过；本任务不包含132部署。

## 用户价值与官方依据

[Dify第6课](https://docs.dify.ai/en/learn/tutorials/workflow-101/lesson-06) 把提取后的类型化数据传给后续步骤；[Claude SDK结构化输出](https://code.claude.com/docs/en/agent-sdk/structured-outputs) 由代码校验schema，超过修复限额时失败。我们要补“模型返回必须可机器消费”的闭环，不宣称复制SDK的完整JSON Schema、供应商约束解码或Dify子图迭代。

## 发布契约

LLM.config可选outputSchema，采用下列明确的JSON Schema子集；无schema时走现有文本路径，旧DSL不补默认字段、不重序列化、原文SHA不变。

- 顶层恰为type:"object"、properties、required、additionalProperties:false；不支持其他关键字。
- 1–16个属性；属性名为既有identifier且至多64字符，不得与节点原outputVariable（默认result）重名。
- required必须恰好列出全部属性且无重复。首片不支持可选字段，保证成功节点的每个已声明输出都存在。
- 属性type为string、number、boolean、array。array只允许items:{type:"string",maxLength?:...}，maxItems最多20；不得嵌套对象或数组。
- string默认/最大长度2000（UTF-16单位）；array最多20项，每项同一长度约束。允许empty字符串/数组、false/0，不把它们当缺失。
- 属性允许type，以及与类型匹配的maxLength或maxItems/items；非法配置返回PARAM_ERROR/400，发布前拒绝。
- schema序列化UTF-8最多8192字节。schema无模板插值，其定义随DSL/checksum/Agent快照冻结。它是受限schema，不是完整JSON Schema引擎。

## 运行语义与预算

1. 解析已发布schema，生成只返回JSON的提示附加到system prompt。模板展开后的system+prompt仍受现有16000字符限制；不临时扩大输入预算。
2. 沿用TextGenerationPort、现有45秒节点预算及父Run控制，不修改供应商协议、不新增模型自动修复调用。底层既有传输重试仍受原预算，不能承诺HTTP请求恰好一次。
3. 输出首先检查UTF-8不超过32768字节；专用reader设最大嵌套深度4、数字文本长度128、字符串长度8192，拒绝重复键、尾随第二个JSON/非JSON、注释、代码围栏、非标准数值。
4. 必须恰好一个对象；字段集合与schema完全一致，null或类型不匹配拒绝，不做字符串到数字/布尔的转换。number用BigDecimal，precision<=38且abs(scale)<=100，防止超长纯十进制展开；不修改全局ObjectMapper。
5. 全部字段校验完成后才绑定：原outputVariable保存规范化JSON文本，各命名字段保存String/BigDecimal/Boolean，数组保存ArrayNode（模板以合法JSON呈现，而不是Java列表格式）。不保留未经验证的原回答作为成功输出。
6. 模型违反schema：节点和Workflow为FAILED，错误文案固定且不带模型返回值；不进入END；Chat映射沿用WORKFLOW_ERROR，不写成功助手消息。父取消/超时/关闭优先，不能被schema错误覆盖。

## 与图和聚合的集成

GraphValidator在有schema的LLM上声明主输出变量和全部字段；下游仍必须引用必经上游。直接数组字段不能作为AGGREGATOR候选，静态拒绝，运行防御仍在。主输出是JSON文本，显式选择它就是文本聚合，不宣称数组合并。旧普通LLM输出集不变。

前端通过现有Config JSON编辑schema，提供一键示例/说明，保留非法中间态保护；不做通用schema拖拽设计器。

## 验证与非目标

- 配置合法/非法、字段冲突、额外字段、缺字段、null、错误类型、重复键、尾随数据、深度/字节/数字/数组边界，零/false/空值正向对照。
- 生产Engine+假模型/HTTP端到端：结构化字段进入TEMPLATE/END；失败时无字段写入、END无执行、逻辑模型调用次数不增加；取消和截止不被改成普通FAILED。
- 固定旧发布DSL/SHA及旧LLM无schema回归；发布后改草稿不改变旧版本schema；非法schema写入前拒绝。
- 受控浏览器精确提交schema；harness/backend/frontend门禁。真实本机模型例子单独运行并记录“不是质量基准”；不把受控响应称为真实模型能力证明。
- 不实现自动修复重试、嵌套对象、可选字段、任意JSON Schema、native response_format、子图迭代、知识事实核验或精确成本计费。格式正确不代表内容真实/无注入。

## 回退

无迁移/依赖。旧二进制不认识新LLM配置字段，会拒绝新schema版本，不能宣称无条件回滚兼容；保留旧版本/会话不改写。部署另设任务并验证资源与回退策略。
