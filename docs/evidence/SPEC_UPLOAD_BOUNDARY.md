# 上传上限：真实应用与两种nginx入口

任务SPEC-UPLOAD-BOUNDARY-001，基线`1f5bbfc`（生产代码同`36eaae6`），证据根`harness/evidence/SPEC-UPLOAD-BOUNDARY-001/SPEC-UPLOAD-BOUNDARY-001-20261004T000555Z-a33dd2b2/`。补HTTP review的上传P1与Accept/优先级/契约边界P2；不部署132，不操作共享容器。

## 反例与修复

`UploadBoundaryNetworkTest`启动真实Tomcat/H2，加载生产application.yml的multipart限制；两份仓库nginx模板分别放进独立nginx:1.27-alpine（本次镜像digest `sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10`）。测试只替换upstream为Testcontainers宿主转发，路径片段包在独立server块；不覆盖上传限制/错误页。容器内nginx -t退出0。无外部模型、无真实凭据。

修复前用真实2MiB文件，应用直连、根路径代理、/hify/前缀代理全部413（预期202），3项失败、0错误/skip，退出1。该反例证明不能上传2MiB；没有在失败后的分支单独断言旧HTML正文。最早一次脚本因作者漏写/v1得到404，不算产品红灯，保留red.log；正确反例在red-corrected.log。

修复：单文件10MiB、整个multipart请求12MiB；Tomcat有限吞包16MiB。两份nginx在Hify API location设置12m和专用error_page 413，在命名location返回HTTP413/code41300/固定JSON，未改其他应用的入口。知识服务内防御性大小检查也统一413，不继续宣称正常HTTP的>10MB业务400可达。

## 实际验证

命令：`mvn -B -Dapi.version=1.44 -pl hify-app -am -Dtest=UploadBoundaryNetworkTest,HttpErrorSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false test`，配置本机Testcontainers socket/host与localhost非代理，不读取业务环境变量。2026-10-04 08:07:50 Asia/Shanghai退出0，3项生产大小测试+7项小上限/错误协议测试，0失败/错误/跳过。

| 路径/请求 | 断言 |
|---|---|
| 直连、根代理、/hify/代理，2MiB合法TXT | 202；回读已持久化文档fileSize=2×1024×1024 |
| 直连，精确10MiB合法TXT | 202 |
| 三条路径，10MiB+1字节文件（请求仍小于12MiB） | 413 / JSON code41300，不含文件名/boundary/HTML/Exception |
| 三条路径，13MiB文件（整个请求也超12MiB） | 同上；有限body、普通JDK HttpClient、未用Expect100或放宽客户端断言 |
| 不存在Run的订阅和取消，Accept:text/event-stream | 建流前404 / JSON，未宣称响应已提交后可改JSON |
| 缺boundary的multipart发到不存在路由及非上传conversations | 400，证明解析可先于404/415 |

知识后台索引用MockBean隔离；因此202及回读仅证明接收/持久化/代理链路，不能作为10MiB索引成功、性能或磁盘压力验证。参数化nginx两个入口算两项测试，不宣称新增两个产品功能。

完整harness/backend门禁以本run的verification.json为准。原始日志保留本地，结构化摘要含命令、计数、SHA。没有真实TLS/132配置生效证据；发布代理前须nginx -t和检查临时目录磁盘余量。默认请求缓冲可能落盘，12MiB只是单请求限额；并发/总存储配额不在本次实现范围。

## 保留边界

- 16MiB是有限吞包，不是任意巨大/慢请求的JSON保证。磁盘满、客户端断连、容器提前拒绝和默认/error的path字段另属边界。
- 类型/空内容/UTF-8错误仍400；Memory未知Run、resume未知runId仍可能400并回显标识。本次未把所有IllegalArgumentException一刀切。
- 部署需同时更新jar与nginx片段，不能只换jar就声称外部413已统一。未修改132真实文件/服务/凭据。
