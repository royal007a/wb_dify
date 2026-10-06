# 数据库就绪与进程存活

## 来源与已知实现

研究C133提倡把依赖就绪和进程存活分开。基线3d52a22：`HealthController`只返回固定存活字符串，`application.yml`已开启Actuator probes且关闭Redis健康项。systemd安装脚本轮询旧health，compose启动脚本检查actuator/health。不能把“没有数据库就绪契约测试”说成“没有任何健康探针”。

## 本切片

1. 在真实Spring装配下验证现有db HealthContributor与readiness分组，用独立H2数据库，不停止共享数据库。先健康、再通过测试替身让连接抛SQLException、恢复；故障替身只作用于自己的应用上下文。
2. 若readiness在数据库故障时仍200，仅配置既有readiness分组加入db。liveness只反映存活，旧`/api/v1/health`保持原JSON；拒绝输出连接配置、异常详情。不调用模型或外部服务。
3. Redis是可降级缓存，不是就绪硬依赖。数据库是业务事实源，不就绪应明确503。readiness只代表检查时刻，不是未来事务必然成功的保证。
4. 验证正常/故障/恢复、liveness状态、旧接口兼容、默认不暴露组件详情；没有制造真实网络黑洞，因此不能据此宣称数据库故障响应延迟已有严格上界。

## 交付边界

先红后绿、固定提交review、完整六scope。产品配置与文档一起交付；不在本任务悄悄更换生产探针或重启线上服务。部署脚本切换探针需要另行验证故障后处理路径，不能把readiness红灯直接当作重启进程的理由。

## 失败复现检查点（未修复）

2026-10-06 23:58:58 +08:00 完成窄测：`mvn -o -B -pl hify-app -am -Dtest=ReadinessIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`。2 项测试，1 failure、0 errors、0 skipped，退出码 1。数据库连接故障后总 health 已返回 503，但 readiness 仍返回 200；失败位置是第 52 行预期 readiness 为 503 的断言。另一个可选依赖隔离测试通过。

这是刻意保留的失败复现，不是已修复或门禁通过。生产配置尚未改动，故障后的后续恢复断言在本轮因提前失败而未执行。原始日志保留于本机临时目录，未提交仓库；SHA-256 为 `bc948477c599c06a894512b1e845312df092a82673752e5733baf9d04f92068e`。完整门禁、修复和复核仍待完成。

## 后续窄测检查点

2026-10-07 00:11:39 +08:00：仅将生产readiness分组设为`readinessState,db`，同一组未修改的测试2/2通过，故障后的liveness、旧接口兼容及恢复断言均执行。新增运维说明区分各探针和现有部署消费方式。证据见`docs/research/jikesummary-20261006/readiness-checks.json`；前节保留的是历史红灯，不是当前窄测结果。完整门禁及外部review仍未完成。
