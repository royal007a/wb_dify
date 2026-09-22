# PROVIDER-001

目标：将原生 Provider 流式调用从正常路径测试提升为可复现的故障矩阵。

1. 对流式 401/403、429、5xx 建立稳定错误分类。
2. 注入响应体中途断开与客户端主动取消，验证不会误报完成，取消在 1 秒内传播。
3. 为 OpenAI-compatible 流启用 usage 回传，并为 OpenAI、Anthropic、Gemini 统一 `ModelUsage` 观察契约。
4. 本地合成流运行 10 次，输出首 token P95 与 usage 结构化证据。
5. 运行 backend/runtime 验证矩阵，证据写入 Harness。
