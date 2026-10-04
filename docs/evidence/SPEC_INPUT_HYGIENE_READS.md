# SPEC-INPUT-HYGIENE-003：读查询准入证据

基线2a9ca62，计划065fc87。仅本地H2及隔离真实PG；不用共享数据库/外部模型，不部署132。

## 反例

- d687592的首轮反例：H2/PG各4失败、0skip。活动知识query和意图agentId在PG返回500；memory的前缀NUL被原normalized().trim()去掉，返回200；冻结检索未抛预期PARAM_ERROR。不同旧行为不能都笼统称成500。
- 5eccf3c将中间NUL放在同组最前面再跑，H2/PG仍各4失败、0skip；PG三个HTTP入口实际500，冻结检索抛出数据库异常而非PARAM_ERROR。前缀/后缀负例仍保留，后续绿灯必须全部通过。

## 测试范围

- 四场景：活动知识、冻结知识的两种公开service、记忆search及entity、意图agentId/input；每个都有合法命中的正向对照。检索上传并等待DONE后确认文档ID和原文；记忆使用实际Mock Run的持久历史，非空匹配当前marker；意图SpyFactory在合法模糊输入确实被调用后清计数，再验证坏请求零调用。
- 适用的HTTP与直接service检查400/PARAM_ERROR、固定文案、前后五种表计数不变；冻结检索没有独立HTTP路由，仅测试两个公开service。冻结fixture在计数之前创建，不能把fixture写入混作读请求写入。
- 不证明Tomcat对%00 URI的拒绝，内部service ID保护和HTTP正文准入单独说明。没有真实供应商、浏览器或132效果验收。

修复后窄测8项全部通过（H2/真实PG各4），失败/错误/skip均0。原始日志/XML不提交，仅保留SHA及脱敏计数。

## 完整门禁与可复算报告

2026-10-04T03:11:56Z，代码104af66，schema3、strictEvidence=true，harness/backend六步骤全部exit0、result/commandResult/testCoverage均passed。后端86类585项全部执行，failure/error/skip/flaky均0；ReadInputHygieneIntegrationTest与ReadInputHygienePostgresTest各4项，无skip。Harness Python54项通过。未运行浏览器、真实外部模型或132发布。

证据目录：`harness/evidence/SPEC-INPUT-HYGIENE-003/SPEC-INPUT-HYGIENE-003-20261004T025954Z-48c4e6fd/`。

- `backend-tests.tests.json` SHA256：4d8af68d96af9628ac55457207e9f11469bf535867c864102d7e89d7f935e53d，已复算且与verification记录一致，invocationId相同。
- source-identity：backend=cf34fdb92c2a13ab46c17f8885497f81d28acf19，frontend=8f83bc4774e6049892447286e0da9057c5c3f397，均与104af66源码树相同；受测backend/frontend无未提交diff，SHA为e3b0c442…。
- 从本轮新鲜XML导出脱敏`method-evidence.json`（SHA256=087a27313b094c971a638b29911d9b6a150ee5ec090e13ed407a88a822b364c3），再显式用该输入离线重算，JSON/MD逐字节相同。报告为54个具名子场景pass、0fail、0not-run；68路由/38功能组只做映射，不是整体通过。
- `reproduction.json`保存两条命令和输入/输出SHA。离线重算不是再次跑测试，SHA不是签名，verification未自动校验observed内容；不提交XML属性、stdout或失败正文。

## 独立静态复核与边界

mymacclaude对2a9ca62..104af66的复核消息om_x100b63287862e0a0c37fc6fca9f73bd：只读源码和调用方，无P0/P1/P2，未运行测试；正式证据复算尚待另行记录。确认HTTP/内部调用没有先trim绕过，null快照保留409，模型工厂正向对照成立。

P3已写入规格：内部模型生成的参数遇NUL也按PARAM_ERROR失败、不做清洗；不能把错误码解释成必然由用户输入造成。已核对history.search与Workflow KNOWLEDGE的调用传播，但本轮没有对应模型输出端到端测试；当前Workflow无LLM节点，不沿用“上游LLM节点输出”的可达性说法。仅路径ID和其他只读管理入口仍未纳入承诺。生产132尚未部署输入卫生001–003。

后续正式收口：mymacclaude回复om_x100b632810db20a8c3fe87b55dc2ee9，报告在6e13407的archive隔离副本实算摘要/portable SHA、源码树及报告JSON/MD逐字节比对，均与本节记录一致；没有重跑Maven或连接132。其撤回Workflow上游LLM节点的不可达例子，保留history.search模型参数提醒。输入卫生001–003独立证据复核均已闭环。
