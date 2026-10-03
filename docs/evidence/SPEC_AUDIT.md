# 全接口/功能规格审计证据

## 范围与来源

- 原版 Hify，源码基线 `973257c`；计划登记 `269af92`，任务 `SPEC-AUDIT-001`。
- 直接读取12个业务Controller、六个前端路由、Runtime/Knowledge/Workflow源码、相关测试和已有课程对齐文档；未把 hify-cc 的功能/测试结论搬进来。
- 规格：`docs/spec/http-api.json`、生成的 `HTTP_API.md`、`FUNCTION_TESTS.md`（F01-F38）、`AUDIT_FINDINGS.md`（A01-A07）。库存67个显式 /api 方法路径，不包含框架端点。

## 新鲜执行（不是旧报告）

2026-10-03 执行：

```sh
mvn -q -f backend/pom.xml -pl hify-app -am \
  -Dtest=ApiContractInventoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

退出0，`ApiContractInventoryTest` 1/1，无失败、错误、跳过。测试启动真实 Spring 上下文与隔离 H2，20个 Flyway 迁移成功；比较实际 RequestMappingHandlerMapping 与人工规格的 method/path/handler 双向集合，同时检查重复项、源文件和候选测试路径存在。
本次上下文启动约219s，受当前本机环境影响；**不能当作产品启动/接口延迟基准**。完整本轮 Harness 结果见本任务的 verification.json。

生成一致性：`python3 harness/render-api-spec.py --check` 通过；`git diff --check` 通过。

## 本次没有宣称的结果

- 67是库存匹配数，不是67个接口行为测试全部通过；F01-F38也不是已全验收。
- API测试引用是候选入口，部分只测组合happy path，未覆盖该组每个路由。
- `management.spec.ts` mock响应，不能证明真实管理CRUD；真实Provider/MCP/PG/浏览器矩阵由下一原子任务逐项执行。
- A01-A07为代码审查发现，尚需公开API/确定性故障回归来确认行为与修复；没有为通过门禁而删除这些要求。
- 本任务不修改生产代码/迁移，不需要重新发布后端；前轮Token适配部署已在独立证据中记录，后续修复部署另有任务。

## 独立 review

已通过 botmux 向 mentionable 的 mymacclaude 发送只读固定基线请求（消息 `om_x100b63255df6f0a8c34a18ed3585e3f`），范围为规格/运行差距与Token安全边界。未收到review结论前不标注“独立复核通过”。

## 既有部署只读核对

同日 SSH 只读检查：hify.service=active；localhost:28080/api/v1/health 返回 HTTP200 / code200 / Hify is running；已部署 JAR SHA256 为 `299838bdd9b7fddcb4d7554e2f06099aec6d14e38c49181829a152f69801a667`，与前轮Token部署记录一致。本次没有重新部署，未读取或输出密钥。
