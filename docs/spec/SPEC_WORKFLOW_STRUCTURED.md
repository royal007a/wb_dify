# WORKFLOW-STRUCTURED-001：有界结构化输出契约

LLM 节点可配置 `outputSchema`。这是一套显式子集，不是任意 JSON Schema、供应商约束解码或自动格式修复。无此字段时保留旧文本行为。

## 发布输入

顶层仅接受 `type:"object"`、`properties`、`required`、`additionalProperties:false` 四个字段。属性 1–16 个，名称符合工作流 identifier、最多 64 字符，区分大小写，不得与主输出变量（默认 `result`）冲突；全部属性必须恰好列在 required 中，不允许重复。schema UTF-8 最多 8192 字节。

属性只支持 string、number、boolean、array。string 的 maxLength 缺省 2000，范围 0–2000，单位 UTF-16；array 的 maxItems 缺省 20，范围 0–20，items 必须是 string，可设同样的 maxLength。没有可选属性、null、对象、嵌套数组、format、enum 等其他关键字。配置非法在写入前返回 400/PARAM_ERROR。

schema 随发布 DSL 和 checksum 冻结，后续改草稿不修改历史版本。旧未配置 schema 的 DSL 不增加 null 字段。固定旧 LLM 金样由 7ddff72 的 writer 生成，SHA 在测试中硬编码。草稿请求在进入校验前已经是 JsonNode，因此原请求中的重复配置键可能被入口折叠；本片不声称能检测这类重复键。

## 调用与解析

附加的 schema 提示与原 system/prompt 合计受 16000 字符限制：保存时检查模板本身，调用前检查展开结果。沿用 45 秒节点预算和父 Run 控制。maxOutputTokens 不保证能装下合法结果；默认 1024 或用户设定值过小时，截断会导致节点失败，前端有提示。

仅接受一个完整 JSON 对象，不剥围栏、不清洗 think/解释文字、不调用模型修复、不发送原生 response_format。底层原有网络/限流重试不因此消失，不能宣称一个节点恰好一次 HTTP 请求。

原响应与规范化 JSON 均不得超过 32768 UTF-8 字节。局部 reader 拒绝重复键、尾随数据、注释和非标准数字；最大深度 4、数字 token 长 128、字符串 8192。字段严格匹配 schema，无隐式字符串转数字/布尔；拒绝 NUL。number 用 BigDecimal，precision≤38、scale 在 -100 到 100，先检查再去尾零/展开；0、false、空字符串和空数组保留。

全部字段通过后才原子写入上下文：主输出是规范化 JSON 文本，各字段是 String/BigDecimal/Boolean/ArrayNode。主 JSON 的数字与模板中的数字使用普通十进制且去无意义尾零；不改变全局 mapper，执行快照/响应中的数值仍可能以等价科学计数法出现。

格式错误使节点和工作流 FAILED，固定错误文案不带原始模型正文；没有部分字段、没有 END 执行。Agent Chat 路径为 WORKFLOW_ERROR、不增加成功助手消息。模型返回先检查调用预算，解析前后检查父控制；取消/截止不能被格式错误覆盖。

## 下游消费

- 字段仍须来自必经上游；schema 不能绕过 dominator 约束。
- 直接数组字段不能用作 CONDITION 操作数或 AGGREGATOR 候选。新结构化单变量条件仅允许 boolean。
- contains/==/!= 保持文本语义，不是数组成员或数值运算。例如数字 1.0 规范化为 1，与文本 "1.0" 不相等。
- 数组经模板输出合法 JSON；字符串插进手写 JSON 模板不自动转义。显式选择主 JSON 文本或先 TEMPLATE 转文本再聚合，是文本操作，不是数组合并。

## 交付边界

提示约束不保证真实模型格式成功；固定的 6 次本地真实模型样本逐次保留失败，分别报告格式通过率与合成答案匹配数，不当成语义质量基准。受控 fixture 的成功不能代替真实模型证据。

每个节点仍保存整个上下文：主 JSON 与字段重复，后续节点再次重复，可能放大快照；本片没有存储优化或大图性能承诺。没有迁移、新依赖或 132 部署。旧二进制不识别 outputSchema，不能运行新 schema 版本；回退须保留旧发布版本并另行验证。
