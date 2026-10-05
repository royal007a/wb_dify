# WORKFLOW-AGGREGATION-001 验证记录

当前范围：本地源码和窄测；完整门禁待补，不是线上交付。设计提交975df82、0cf8545；实现commit由后续门禁head绑定。

## 实际运行（2026-10-05）

- `mvn -B -o -pl hify-workflow -am -Dtest=WorkflowAggregationTest,WorkflowEngineTest,WorkflowGraphValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`：最终56项（12/16/28），零失败/错误/skip，14:32:25 CST完成；单元测试，不是PG。
- `mvn -B -o -pl hify-app -am -Dtest=WorkflowApiIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`：7项，零失败/错误/skip，14:29:28 CST完成；H2/MockMvc，不是真实网络。
- `npm run typecheck`：exit0。
- 独立本地Vite端口127.0.0.1:5187，`E2E_BASE_URL=http://127.0.0.1:5187/ npm run test:e2e -- e2e/management.spec.ts`：2/2通过（11.6s），包括新增节点和候选编辑后请求体精确匹配。HTTP打桩，不能说明真实后端保存/发布成功。

## 首轮红灯，未删除事实

1. 补取消用例时，28项里1项失败：测试用mock控制并错误预期抛错且无Run行；既有引擎会创建并收敛CANCELLED记录。修正为真实ExecutionControl，断言CANCELLED、2次run保存、没有任何node保存或分支输出；没有修改生产取消行为。最终包含原图校验的56项重跑通过。
2. 管理浏览器2项首轮1失败：修改合法JSON后立即点保存，发出旧的默认候选。原编辑器只在change时更新，保存触发的重渲染可保留旧值。将合法JSON同步改到input事件；精确请求体断言保留，重跑2/2通过。不把失败归为模型或后端故障。

以上原始Maven/浏览器日志及失败trace不作为已提交可复算证据，工具记录与本段只保留命令/结果说明。正式gate将另存独立run目录与摘要，不追认窄测为完整门禁。

## 尚未验证

- 尚未部署132，无线上聚合运行证据。
- 聚合不调用外部模型，不能算真实LLM或语义质量验证。
- 不支持列表聚合、并行join、迭代子图或聚合输出的事实核验。
- 回滚旧二进制不能执行新节点；旧目录静默过滤非法发布版本的行为仍是已知边界。
