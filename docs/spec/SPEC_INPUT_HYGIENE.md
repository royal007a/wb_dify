# SPEC-INPUT-HYGIENE-001：文本NUL准入

实际U+0000不能写入PostgreSQL文本列。以下入口明确返回HTTP400、Result.code=40000，不把数据库22021误报成500，不回显被拒绝的文本/凭据。检查发生在trim、实体修改、凭据处理和持久化之前；不删除、替换或重编码用户文本。

| 写入入口 | 检查范围 | 零改动要求 |
|---|---|---|
| POST/PUT knowledge-bases | name、description；update id | 坏创建无新行，坏更新回读完全不变 |
| POST knowledge-bases/{id}/documents | baseId、原filename、mediaType、UTF8解码正文 | 无文档/索引任务，不发索引事件 |
| POST agents / PUT agents/{id} 基本信息 | name、description、instructions、providerId、modelId；create enabledTools；update id | 不改变草稿及绑定 |
| POST/PUT workflows | name、description、节点key/type/name、边key/两端/condition、config递归键与字符串值 | 不删除或替换原图 |
| POST workflow-versions/{id}/runs | versionId、input；HTTP和engine公开入口 | 无workflow_run及节点行 |
| POST/PUT providers | name、baseUrl、auth的ref/header/prefix、每个model的displayName/modelId；update id | 不替换模型目录，不读真实凭据或请求远端 |
| POST/PUT mcp-servers | name、endpoint、credentialRef/action/token；update id | 不保存Server/凭据、不解析出站目的地 |
| POST conversations | agentId、title | 查Agent版本及写会话之前拒绝 |

服务层接受的DTO仍可直接构造；检查不依赖Bean Validation或Jackson，所以内部调用与HTTP同样受保护。Conversation目前是Controller直接写库，其检查在该入口；本片没有新增或伪称存在Conversation application service。Run message/key/resume的NUL策略由SPEC-RUN-INPUT-002先前实现，继续保留。

正常中文、emoji、换行/tab和字面量反斜杠加`u0000`六个字符不被本规则删除或误拒；其他既有字段长度/URL/引用规则仍适用。非法UTF8、空正文、文件类型、上传体积等错误保持各自契约。字段本来就不合法时可以先被MVC其他校验拒绝，但不得在service绕过NUL检查后写入。

## 验收

`InputHygieneIntegrationTest`与`InputHygienePostgresTest`执行同一套8个场景；真实PG每项先确认`select version()`并以绑定参数证明服务器确实拒绝含NUL文本。覆盖HTTP与直接application/engine调用、合法创建/更新/试跑正向对照、坏更新回读不变、坏创建行数不变、上传零文档/索引任务。`TextInputTest`覆盖边界位置、嵌套数组/对象键及不改变合法JSON。

该矩阵不覆盖所有JSON/query/header/path输入、Demo参考CRUD、上传后的模型/MCP返回值、历史数据清洗、未绑定工具业务参数。没有全局Jackson替换器或自动数据库清洗；没有前端浏览器/生产发布证据时，不得声称132已修复本项。

首轮未覆盖Agent的knowledge-bindings、mcp-bindings、workflow-binding和tools写入口，归SPEC-INPUT-HYGIENE-002；读路径retrieval-tests、memory/search及意图路由归SPEC-INPUT-HYGIENE-003。当前结果不能概称“全部Agent写入口已覆盖”。
