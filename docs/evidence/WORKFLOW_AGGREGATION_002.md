# 聚合复核补强（本地）

基线f99c51d，计划1056f5b，代码5010e05；本证据不覆盖132部署。完整本地门禁于2026-10-05 15:02:01 CST通过。

## 旧发布金样

实际在 `git archive 7ddff72 backend` 的临时隔离副本里加入本目录 `fixtures/LegacyWriterProbeTest.java`，放到对应workflow测试包，运行：

`mvn -B -o -pl hify-workflow -am -Dtest=LegacyWriterProbeTest -Dsurefire.failIfNoSpecifiedTests=false test`

2026-10-05 14:46:19 CST退出0，1项通过。旧源代码重新编译，只运行生成探针，不连接数据库/网络服务，也不执行install覆盖本地Maven产物。旧WorkflowPublishedGraph.write生成的原文保存在backend/hify-workflow/src/test/resources/workflow/legacy-7ddff72.json；去掉资源文件末尾LF后的SHA为566f4b6297a4c259beb1c790f73228e60b5a89eb7073ae5f1ac7e4e4bf18e968。新测试加载固定资源和常量摘要，运行read/validate以及与金样比较writer结果；不再从新writer现场构造期望值。覆盖条件图、旧额外配置和原始发布格式，不能外推成所有历史数据已穷举。

## 独立祖先负例与突变

新增另一条END分支的sib作为第三候选：通往merge的路径仍各含left或right之一，所以计数规则不会误伤；合法不含sib的图先通过。加入sib后必须得到“严格上游输出”错误。

2026-10-05 14:48:56 CST，临时仅移除 `!ancestors.contains(parts[0]) ||`，运行 `WorkflowAggregationTest#rejectsSiblingOnAnotherEndEvenThoughCandidateCountsAreExactlyOne`：1项失败，原因是预期异常却没有抛出。随即用apply_patch恢复，git diff确认生产WorkflowAggregation.java与已提交版本一致。失败是预期突变验证，不是产品未修故障。原始终端日志未提交，不称可离线重放。

## 编辑器

每个节点保存独立JSON文本，合法时更新DSL对象但不重排输入文本；非法JSON/null/数组均保留输入并显示错误，任何节点存在错误时禁止保存/发布（函数入口和按钮同时检查）。切换节点保留未完成草稿；删除节点删除其错误。拖动非法节点不改变坐标；合法拖动同步其文本，以免随后编辑写回旧坐标。

逐字输入、非法中间态、切换节点、修复后精确请求体已加入受控浏览器；只证明模拟HTTP的交互，不证明真实后端CRUD。草稿重载/关闭后的持久恢复不是本片承诺。

实际反向验证：临时将WorkflowCanvas.vue替换为379d393版本，保持新增测试不变，运行 `E2E_BASE_URL=http://127.0.0.1:5187/ npm run test:e2e -- e2e/management.spec.ts -g 'preserves sequential invalid'`：1项失败，期望文本为未完成的 `{"candidates": [`，实际被重置为原来完整的默认配置。之后恢复本片文件并重跑。未修改旧提交、未放宽断言；失败trace不提交，记录只代表本机执行观察。

祖先检查恢复后，14:49:17 CST单元Aggregation/GraphValidator/Engine合计58项（14/28/16）通过，失败/错误/skip均0。恢复最终前端后重新运行typecheck退出0，管理浏览器3/3通过（9.6秒），包含null/数组非法配置检查；不把旧文件反向测试的失败当成最终代码失败。

## 完整门禁与离线复算

执行 `DOCKER_CONTEXT=colima-hify-verify-20261004 ./harness/run-task.sh WORKFLOW-AGGREGATION-002 -- true`，退出0。证据目录为 `harness/evidence/WORKFLOW-AGGREGATION-002/WORKFLOW-AGGREGATION-002-20261005T065226Z-c0916b1e`；schema3，head5010e05，invocation a0a6425f-8578-4c71-b7ed-c838a7fade2c，2026-10-05T07:02:01Z passed。harness/backend/frontend三个scope共8步全部退出0。Python78项，backend97类685项全部执行，failures/errors/skipped/flakyAttempts全部0，underfilledSuites为空；typecheck/build通过。没有单独重跑migration/runtime/eval scope，不把重叠测试相加。管理浏览器3/3是另行窄测，不属于frontend scope中的typecheck/build。

backend-tests.tests.json SHA256为ee90582c36fb84e6550e53823a93b5aae3fe63d8e5a08755399a6eee61b88642；verification为7e7a604553b86324c9864ba54801cbfb75d6b82cf7d8ef6879e84c7dd492c707。三棵源码树及空diff保存在source-identity.json；没有未跟踪的backend/frontend/deploy源码。

从新XML导出method-evidence后，再显式使用该portable输入重算；两个文件都用cmp比较，JSON/MD逐字节相同。57个既有具名子场景pass、0 fail/not-run；新聚合用例存在于suite/method清单，不声称已新增路由级场景。具体SHA和命令在reproduction.json。原始日志/XML未提交，脱敏输入不是签名，第三方不能据此证明日志真实性。

## 独立复核与继续开放的P3

Claude对5010e05相对f99c51d做只读代码核对和金样SHA复算，未重跑测试，结论没有P0/P1/P2；本段不把它说成独立运行验收。保留以下边界：

1. 原有通用rejects辅助方法只断言异常类型；祖先和未声明输出共用错误消息。新增sib用例由实际移除祖先判断的突变红灯提供区分证据，不声称所有负例都有独立错误码。
2. 保存后父组件回读会重置草稿和选择；回读返回前继续输入仍可能被覆盖。此片保证节点间切换保留非法草稿，不保证跨保存/重载持久化。
3. 金样覆盖普通条件/模板和historicalExtension，没有覆盖publication戳、KNOWLEDGE、外部节点或输入schema。旧/新writer源码未变化；固定SHA作为未来回归保护，不外推到全部历史版本。生成探针在docs下，不属于常规编译。
4. 非法JSON节点拖动被忽略；合法但不完整的配置（如{}）会丢失__ui坐标。没有宣称编辑器全部交互问题均解决。
