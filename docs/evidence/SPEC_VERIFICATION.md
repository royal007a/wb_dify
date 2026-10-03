# 全接口与功能新鲜验证（进行中）

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
