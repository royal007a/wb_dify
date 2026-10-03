# 已提交模型响应的恢复重放

来源：E1 6191211 的只读复验提出的新 P1 假设，需先复现，不把静态推断当成已验证故障。

反例：model:N 已提交 canonical history，工具开始前/执行中关闭，轮末 checkpoint 尚未推进；重启重复调用随机模型，产生不同语义摘要，触发 HISTORY_COMMIT_FAILED。不能放宽 operationId 的摘要冲突保护。

步骤与验收：

1. 每次调用返回不同内容/工具 ID 的模型夹具；在已提交 model:N 后、工具执行中用 latch 挂起；复用同一数据库重启，旧实现应确定性失败。
2. 恢复必须读取可信 canonical response 并重建模型消息/工具调用，不重新生成已经提交的 model:N。保持租约、取消、schema/能力快照和工具幂等边界；历史工具结果能复用，外部副作用不能声称自动回滚或恰好一次。
3. checkpoint 与 history revision/operationId 的恢复关系必须有契约；损坏或语义不一致仍 fail-closed，不覆盖历史。
4. 验证没有重复模型调用、重复 delta、重复历史提交；真实关闭/恢复及已有运行时回归，记录 red/green。

只改 ~/hify，不改 hify-cc，不访问共享数据或真实凭据。代码与测试独立提交，交由 mymacclaude 只读复核；回滚只针对代码，不重写已提交历史。
