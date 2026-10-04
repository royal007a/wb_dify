# 全接口与功能新鲜验证

任务SPEC-VERIFY-001，源码基线7f735fe。机器状态看harness/tasks.json，不以本文标题或条数判断任务完成。证据根目录：`harness/evidence/SPEC-VERIFY-001/SPEC-VERIFY-001-20261003T233040Z-36e1c946/`。

## 基线命令与范围

- `./harness/verify.sh --scope all --evidence-dir <证据根>/baseline`：隔离H2/Testcontainers PG，不复用共享业务库。已有基线通过：backend 520项、migration 114项、runtime 34项、eval 24项，各自0失败/错误/跳过；前端typecheck/build及Harness通过。多scope用例重叠，不合计。此时尚未添加下述HTTP负例，不等于所有行为通过。
- migration已完成114项、失败/错误/跳过0；11条额外PG零skip检查通过。数据库PostgreSQL 16.15，Flyway V1-V23；H2和PG用例及多scope重复执行不能相加充作独立覆盖数。
- 受控浏览器：`E2E_BASE_URL=http://127.0.0.1:15174/ PLAYWRIGHT_CHANNEL=chromium-headless-shell npx playwright test e2e/chat-lifecycle.spec.ts e2e/management.spec.ts e2e/mcp-edit.spec.ts --workers=1 --trace=off`，32项通过、0跳过，1.0分钟。真实Vue/Chromium，HTTP及Chat EventSource打桩，不是真实管理CRUD或端到端SSE。独立Vite已停止，15174释放；未触碰共享前端。

## 行为报告口径

68是显式方法/路径库存；路由被调用、测试类通过都不自动等于这一路全部行为断言通过。后续报告分别记录：

1. 路由库存匹配；本轮日志观察到的状态码（仅观察，不冒充断言）。
2. F01-F38内实际执行且检查了的子场景，以及未测/已知失败子场景；每项附命令、类/方法、fixture与范围。混合结果不得给整组写全通过。
3. 本地真实HTTP/浏览器与132发布后验收，独立于上述受控浏览器。

明确待补：全应用413/multipart/错误文案、管理读写路径的缺失断言、重复/终态取消和异resume同key；Chat四条最新P2由SPEC-CHAT-LIFECYCLE-004跟踪。本仓库无真实模型效果/费用基线，不能复用hify-cc的结果。当前尚未部署132。

## 新发现的红灯与暂停原因

新增真实Tomcat的HttpErrorSurfaceTest，`mvn -B -pl hify-app -am -Dtest=HttpErrorSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false test`退出1：5项中4失败、0错误/跳过。未知Run为400而非404，multipart格式错误与超限为500而非400/413，未知路由404正文反射请求路径。400/405/415及缺header的框架错误组合用例通过。fixture保存在本证据目录（不放入常规测试源集，待修复时原样恢复），完整失败日志为http-red.log。不能因旧520项绿而忽略新红灯。

同时审查发现门禁的Flakes漏计、预期类遗漏/旧XML和finish绕过。SPEC-VERIFY-001暂停验收，先独立执行SPEC-AUDIT-003修复验证可信度，再恢复本任务修复HTTP红灯。未将本次任务标成完成。

## 2026-10-04恢复：方法级验证与本地真实浏览器

上述为历史红灯，未删除或回写。后续独立切片已处理：门禁38afd9a..f64a690；HTTP fa5f48f..a9a7357；上传8827af4/f17ab2a；Chat83055fb/a2ee546；管理6ea09e5/34e1a03。mymacclaude分别静态复核并核对相应证据（门禁另有隔离假命令实测），阻塞项已关闭，遗留P2仍在AUDIT_FINDINGS/tasks，不归零。

恢复基线`2c99821`，新证据根`harness/evidence/SPEC-VERIFY-001/SPEC-VERIFY-001-20261004T002745Z-de13ece6/`。本次未改src/main，不为增加覆盖数改变线上语义。补了McpServerApiIntegrationTest.listsCurrentToolsAndArchiveRemovesOnlyTheActiveServer：目录先空后有2条、与发现响应精确一致；归档前列表有id/名字，归档后列表消失且详情和工具目录404。窄测8项零失败/错误/跳过（08:33:45）。不据此声称验证了已发布旧快照的归档后调用资格。

### 浏览器证据分层

独占Tomcat18081（新内存H2）、Vite15174、Chromium headless，模型使用Mock；MCP配置只用合成Token和显式允许的假引用，新建禁用Server，不连第三方。主密钥仅临时随机注入，不打印或保存。测试后两进程停止，未修改共享服务、真实凭据或132。

- 首次4项命令中，独立代理用字符串target默认改写Host，导致浏览器Origin与应用地址不符，POST返回预期CORS403，第一项超时。保留日志并中断（exit130），是测试夹具错误，不计为产品修复。
- 改为程序化Vite proxy的changeOrigin:false，保留同源Host；不改应用CORS策略。4项重跑通过（15.8秒）。
- 再执行全部7个Playwright文件：39 passed、0 skipped（1.3分钟）。其中4项是实际HTTP+Tomcat+数据库+Vue链路：时间多轮、calculator与SSE、MCP编辑回读、Token保存/KEEP/替换/CLEAR且不回显。其余35项HTTP或EventSource打桩，只证明客户端时序/页面行为。
- 不宣称真实LLM质量、外部MCP调用、所有管理CRUD的浏览器操作或132 TLS/nginx通过。完整命令、SHA和范围在browser-summary.json。

### 接口与功能对照

`docs/spec/behavior-cases.json`逐项关联人工检查的断言、精确测试方法、fixture、68路由及F01–F38。报告器把当前verification的invocation和tests摘要SHA对齐，再读与摘要SHA一致的XML，只提取方法名/结果，不输出properties、日志或正文。缺方法、skip和flaky不会因整个类其他测试通过而变绿；5个报告器单测覆盖这些分类及XML篡改。历史538项报告的只读干跑准确给新MCP方法not-run，没有修改历史文件。

生成的behavior-report.json/Markdown只对具名子场景标pass/fail/not-run；整条路由不标“全行为通过”。对功能组逐条保留外部模型、真实图编辑、全部错误排列、恢复/配额等缺口。源码树、构建jar摘要与验证前空source diff见source-identity.json。全量门禁和最终分类见下文。

### 7173e55全量红灯（00:37:26Z）

此次verification明确为failed，不得用其他scope通过覆盖：backend在hify-chat提前中止，RunEventBrokerBackpressureTest的blockedSendCannotHoldCommitCallbackOrCollidingRunOrHeartbeat发生Mockito WrongTypeOfReturnValue（List被误用于返回Optional的方法）。测试先subscribe启动worker，随后才修改events mock及替换emitter，存在并发stubbing；不是产品背压断言失败。Maven未进入hify-app，因此摘要因缺类fail-closed，不把0条摘要解释为0失败。独立migration 114、runtime 34、eval 24均零失败/错误/跳过，Harness36与前端构建通过，仍不代表全量通过。原报告和日志SHA完整保留。下一轮将先配置mock，再用responseCommitted门闩放行worker，保留原来的慢发送/提交/碰撞Run/心跳断言，不通过重跑旧竞态来冒充修复。

### 9f40639重跑结果（00:43:34Z）

`harness/evidence/SPEC-VERIFY-001/SPEC-VERIFY-001-20261004T003903Z-a34dc9b5/`保存本次schema3验证、独立invocation及source identity。9f40639仅改测试：先完成桩配置、用AtomicBoolean门闩阻止sender查库/发送，替换测试emitter后才放行；额外断言放行前没有查询。没有改生产背压策略，也没有删除超时/碰撞/提交/心跳断言。

- `run-task.sh SPEC-VERIFY-001`退出0，harness/backend/frontend/migration/runtime/eval全部passed。
- backend：81类、539项全部执行，失败/错误/跳过/flaky均0；独立migration：14类114项，runtime：7类34项，eval：7类24项，均零失败/错误/skip/flaky。后3组与backend重叠，不相加计独立测试。
- Harness Python36项通过；TypeScript和Vite生产构建通过。浏览器39项证据见前一目录，测试修复未改变生产代码，未虚构再次跑浏览器。
- `python3 harness/behavior_report.py --evidence-dir <本目录>`生成68路由、38功能组、36个具名断言组pass、0fail/0not-run；这36组是所映射的已执行子场景，不是68路由或38功能全部组合通过。每组的remaining、各功能notRun保留真实外部模型、完整浏览器CRUD、复杂MCP协议、132部署及已登记P2等缺口。
- 原始XML因含JVM属性不提交；逐类摘要和XML SHA可检查。只读复核方可重算提交的摘要SHA，不据未提交的原始日志宣称已复跑。

因此，本地所选自动门禁及具名行为断言通过，规格和证据对齐；不是无缺陷声明。SPEC-RUN-INPUT-001和其他审计P2继续开放。132部署尚未执行，由SPEC-DEPLOY-001独立进行，不以本任务完成替代整个用户目标完成。
