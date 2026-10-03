# 真实HTTP错误面修复

1. 原样恢复SPEC-VERIFY-001的HttpErrorSurfaceTest，重新运行红灯并记录，不改断言迁就实现。
2. 未知资源使用已有BizException/NOT_FOUND，不解析异常字符串；multipart在Advice分类MaxUploadSizeExceededException/MultiPartException，不回显路径/内容。
3. 真实Tomcat测试扩至未知Run详情、事件、流和取消，保护原400/405/415协议。必要时加Advice单测但不以standalone代替网络入口。
4. 更新spec错误码、suite清单、红/绿证据；运行harness/backend全量（含隔离PG），保持不动共享服务、hify-cc、132和真实凭据。
5. 小提交交独立review，回到SPEC-VERIFY-001继续矩阵；部署另起已授权任务。
