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
