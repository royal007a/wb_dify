# Workflow具名标量输入 v1

来源：Dify Workflow101课2/quick-start的结构化输入差距，采用Hify不可变版本DSL，不复制其全部表单/文件/会话变量能力。计划WORKFLOW-INPUTS-001；状态与证据看Harness。

START.config.inputs为最多16项数组，省略即旧userMessage输入。字段属性：

| 属性 | 契约 |
|---|---|
| name | `[A-Za-z_][A-Za-z0-9_]{0,63}`，唯一，不得为userMessage |
| label | 可选1–128字符显示名 |
| type | text、number、boolean、enum（区分大小写） |
| required | 必须为JSON boolean；true禁止default，false必须有合法default |
| maxLength | 仅text，整数1–4000；默认2000，按UTF-16计数 |
| options | 仅enum，1–32个唯一非空文本选项，每项最多128字符 |
| default | 和type精确匹配，不允许null、不隐式转换；false、0、可选空文本均合法 |

未知schema属性拒绝。数字必须有限且绝对值<=1e12，十进制precision<=1000且scale绝对值<=1000，防止极端指数放大模板；HTTP原始JSON和发布默认值使用局部BigDecimal解析，不经过double。模板数字去掉无意义尾零后用普通十进制表示（1e12→1000000000000、1e-7→0.0000001、-0.0→0），不是财务计算引擎。内部调用方若预先构造DoubleNode，精度已经丢失，服务不能还原原始文本。前端Element Plus数字控件仍使用JS Number，不能承诺超过其浮点精度的手输/默认值无损；高精度调用应使用原始JSON API。

必填text的空白按ECMAScript trim字符集判断，包括NBSP/U+00A0和BOM/U+FEFF；不对非空内容做trim。长度按UTF-16，一个emoji通常计2。schema与实际输入拒绝NUL但不修改值；其他控制字符、方向符和U+200B零宽空格不做清洗（U+200B不是此契约的空白），输入不作为模板递归解释。required boolean仅表示该键必须存在，false为合法值，界面默认false不要求用户勾选确认。

试跑`POST /api/v1/workflow-versions/{id}/runs`：

```json
{"input":"安排工作","inputs":{"owner":"小林","count":0,"urgent":false,"mode":"普通"}}
```

input继续作为实际START key下的userMessage，非空、最多20000 UTF-16字符；inputs省略为空对象，显式null/数组拒绝。字段不能超出声明、不能重复解释成表达式、不会把字符串false转布尔。缺必填/类型/超限/未知字段是400 PARAM_ERROR，不创建执行行和节点，也不发模型/HTTP请求。可选字段缺失使用发布默认值。

START具名变量沿用必经上游检查，`{{entry.owner}}`只能引用声明字段。类型保存在运行context里，文本模板将标量字符串化。只提供具名输入，不提供通用JSON对象/文件/列表。

配置存在inputs时，发布DSL额外带`publication.inputSchemaFormat=1`并纳入checksum；旧无inputs版本行为不变，旧有同名配置却没有服务端标记的版本拒绝并提示重新发布，不静默更换其语义。旧版本回读/执行不受新草稿修改影响。

Agent Chat目前只传userMessage：含必填具名字段的Workflow绑定/发布返回409；先绑定可选版本、Workflow后发布为必填时，Agent再次发布仍重查并拒绝。批量可用快照不展示这种版本。可选默认字段支持Agent固定版本路径。控制台列表和画布共用试跑表单，读取发布版本而非草稿；START schema仍在Config JSON编辑。可选数字控件清空产生null时前端省略该键，由服务端使用发布默认值；必填数字清空拒绝，HTTP直接传null仍拒绝。表单关闭不取消服务端执行，同步试跑没有幂等重放能力，结果不明后重复试跑可能产生另一执行（既有边界）。

上线前只读统计旧发布版本START.config含inputs但无inputSchemaFormat的记录；非零必须先解决兼容方案，不能直接切换或修改不可变旧版本。2026-10-04约23:35的132检查为0，只是当次状态，部署前必须再查。

INPUTS-002补强：可选数字未编辑时完全省略该键，即使前端显示的默认值为JS Number近似，服务端仍使用精确发布默认值。只在用户input/change之后提交数字，主动修改成0也发送；清空依然省略，重开/切换版本重置编辑标记。用户主动输入数字仍受JS Number精度限制，不宣称任意精度编辑器。

测试：WorkflowInputsTest有执行前拒绝+合法外部execute一次的mock对照；WorkflowInputsIntegrationTest/H2与Postgres子类验证HTTP契约、零写入与合法+1/+2、schema冻结、Agent绑定拒绝及可选默认真实异步Chat。MockMvc不是真实Tomcat网络。前端workflow-inputs.spec为路由打桩，证明客户端表单/请求，不替代上线验收。模型质量、文件、多模态、Chatflow不在范围。
