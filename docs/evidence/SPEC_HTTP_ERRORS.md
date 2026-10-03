# 真实 HTTP 错误面：红灯修复

任务 SPEC-HTTP-ERROR-001，基线fa5f48f（生产代码同f64a690），证据根`harness/evidence/SPEC-HTTP-ERROR-001/SPEC-HTTP-ERROR-001-20261003T234851Z-03837d9f/`。不动共享服务、hify-cc或132。

## 红灯

从SPEC-VERIFY-001证据恢复HttpErrorSurfaceTest，重新运行`mvn -B -pl hify-app -am -Dtest=HttpErrorSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false test`。退出1，5项中4失败、0错误/skip，与原反例一致：

1. 未知Run详情返回400，不是404；此断言失败后未继续到事件请求，不能说红灯已独立验证后者。
2. 未知路由虽404，但正文含请求路径中的合成marker。
3. multipart缺boundary返回500，不是400。
4. 文件超过容器限制返回500，不是413。

仅400/405/415及缺header的组合用例通过。red.log保留，不修改旧SPEC-VERIFY证据。

## 修法与绿灯

- 使用已有BizException/NOT_FOUND处理未知Run详情、事件、SSE订阅及取消回读，不解析异常字符串决定状态。
- NoResourceFoundException不回显路径，返回固定NOT_FOUND文案。
- Advice单独处理MaxUploadSizeExceededException为41300/413；其他MultipartException解析失败为40000/400；不输出文件名、boundary或原始异常。
- 新增SSE-only Accept的未知Run订阅和取消负例。`mvn -B -pl hify-app -am -Dtest=HttpErrorSurfaceTest,HttpErrorContractTest -Dsurefire.failIfNoSpecifiedTests=false test`退出0：真实Tomcat/H2的6项、standalone MVC的7项均通过，0跳过。不得将standalone七项当作另七条真实网络测试。
- 测试中容器file上限1KiB、request上限4KiB，提交2KiB合成正文；证明解析异常协议映射，不是生产超大/弱网上传压力测试。知识模块自身10MB业务校验仍400；Memory及部分旧会话缺资源的400边界未在本切片改动。

更新API错误码、5条受影响路由的检查要求和候选测试、F34说明以及Harness预期类清单（新增6项，没有降低旧分母）。

## 完整门禁

2026-10-04 07:53（Asia/Shanghai），代码提交`9aac4e7`，本任务`verification.json`为schema 3 / strictEvidence / passed，绑定本次runId、HEAD和harness/backend范围。后端新鲜Surefire XML报告79个类、526项执行，failures/errors/skipped/flakyAttempts均为0；Harness Python 31项通过。PG集成类本轮实际执行，没有以H2或跳过代替。原始XML不提交（含进程属性），逐类计数和SHA保存在`backend-tests.tests.json`，日志SHA在verification中。

这是本切片本地测试证据；未做132部署，也不代表全部接口、全部故障排列已经验收。
