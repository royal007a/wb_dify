# SPEC-INPUT-HYGIENE-002：Agent绑定准入及集成补强

基线为输入卫生001代码add75ed及其完整门禁。响应独立静态复核P2-1/P2-3，不改132、数据库schema或共享服务。

1. 核对Agent四类绑定写入口及DTO，先加H2/真实PG HTTP与直接service反例；合法绑定作为正向对照，坏请求后完整Agent回读与绑定不变。
2. 在requireDraft、trim、freeze/知识读取、删除绑定之前检查实际NUL（id、目标ID、工具名），不静默改写。
3. 补enabledTools负例、config深层数组/对象键负例和合法嵌套JSON正例；不用单纯“无输出”作为证明。
4. 更新明确入口规格/行为选择器，跑窄测及完整backend/harness零skip，提交独立证据后交复核。

读路径仍归003；新测试只证明列出的入口，不声称所有文本/工具返回值均已清洗。服务层坏参数不得触发凭据或出站调用。
