# 应用/代理上传边界

1. 先写真实网络反例：默认应用2MiB上传失败，两份nginx实际启动后2MiB上传被HTML413拒绝。隔离H2，Mock索引后台（只验收接收/持久化及HTTP边界，不伪称大文档索引）。不触碰132/共享容器。
2. 应用显式10MiB file、12MiB request、有限12MiB swallow；代理12m、专用命名错误location保留413+固定JSON。代理请求缓冲有磁盘成本，部署前检查目标余量；巨大慢请求仍可断连，不设无限吞包。
3. 新鲜直连/两代理测试2MiB、10MiB边界、超文件/请求上限；小上限测试补SSE Accept取消、解析优先级。保留红灯日志SHA。
4. 对齐API/OPERATIONS/HTTP规格，明确请求解析早于路由及现存memory/resume回显边界。全量零skip通过后交readonly review；132另任务部署。
