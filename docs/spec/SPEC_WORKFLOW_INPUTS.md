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

未知schema属性拒绝。数字必须有限且绝对值<=1e12；必填text不能空白。schema与实际输入拒绝NUL但不修改值，不解析输入中的模板表达式。

试跑`POST /api/v1/workflow-versions/{id}/runs`：

```json
{"input":"安排工作","inputs":{"owner":"小林","count":0,"urgent":false,"mode":"普通"}}
```

input继续作为实际START key下的userMessage，非空、最多20000 UTF-16字符；inputs省略为空对象，显式null/数组拒绝。字段不能超出声明、不能重复解释成表达式、不会把字符串false转布尔。缺必填/类型/超限/未知字段是400 PARAM_ERROR，不创建执行行和节点，也不发模型/HTTP请求。可选字段缺失使用发布默认值。

START具名变量沿用必经上游检查，`{{entry.owner}}`只能引用声明字段。类型保存在运行context里，文本模板将标量字符串化。只提供具名输入，不提供通用JSON对象/文件/列表。

配置存在inputs时，发布DSL额外带`publication.inputSchemaFormat=1`并纳入checksum；旧无inputs版本行为不变，旧有同名配置却没有服务端标记的版本拒绝并提示重新发布，不静默更换其语义。旧版本回读/执行不受新草稿修改影响。

Agent Chat目前只传userMessage：含必填具名字段的Workflow绑定/发布返回409；批量可用快照不展示这种版本。可选默认字段支持Agent固定版本路径。控制台列表和画布共用试跑表单，读取发布版本而非草稿；START schema仍在Config JSON编辑。表单关闭不取消服务端执行，同步试跑没有幂等重放能力，结果不明后重复试跑可能产生另一执行（既有边界）。

测试：WorkflowInputsTest有执行前拒绝+合法外部execute一次的mock对照；WorkflowInputsIntegrationTest/H2与Postgres子类验证HTTP契约、零写入与合法+1/+2、schema冻结、Agent绑定拒绝及可选默认真实异步Chat。MockMvc不是真实Tomcat网络。前端workflow-inputs.spec为路由打桩，证明客户端表单/请求，不替代上线验收。模型质量、文件、多模态、Chatflow不在范围。
