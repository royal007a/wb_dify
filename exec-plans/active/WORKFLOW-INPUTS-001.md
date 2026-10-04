# Workflow结构化输入：地基、运行、控制台、验收

承接Dify官方课2/quick-start的差距项；在原有版本化确定性图上扩展输入，不引入文件、会话变量、动态代码或任意JSON Schema。无数据库迁移。

1. START.config.inputs最多16字段，name为ASCII标识符(<=64)、保留userMessage；type=text/number/boolean/enum。required必须是boolean。必填字段不带default，可选字段必须有同类型default，保证下游变量存在。文本maxLength默认2000、最多4000 UTF-16；枚举最多32个非空选项、每项<=128；数字有限且绝对值<=1e12。不接受隐式类型转换、未知字段、null、NUL。schema纳入DSL/checksum及服务端publication.inputSchemaFormat=1标记；旧无输入图保持原行为，旧有同名未标记配置需重新发布。
2. 试跑仍提交input作为userMessage(非空<=20000)，另加inputs对象。服务层按已发布schema校验/填默认值，先于执行行和网络调用。变量使用实际START key引用，值不递归插值，contextJson保留类型。Agent Chat暂不能提供具名输入，绑定含必填具名输入的版本返回409；可选默认值兼容Agent路径。
3. 列表和画布共用试跑对话框，打开后读取发布版本而非草稿schema，文本/数字/开关/选择控件产生真实JSON类型；显示版本和输入边界。START配置仍可用JSON编辑，不宣称完整可视化schema设计器。
4. 单测+H2/真实PG HTTP正反对照：合法类型/默认false和0/自定义START/快照冻结；错误输入零执行行/节点、未知引用发布拒绝、Agent限制；控制台受控浏览器验证快照与请求类型。完整harness/backend/frontend零skip。记录失败与独立review，不以Mock冒充实网。

回滚为应用代码回退，但含新输入schema的发布版本需停用或改回兼容版本，不能让旧引擎静默解释新配置。部署另走CAPABILITY-DEPLOY，需先修SPEC-DEPLOY-004的V24迁移目标。
