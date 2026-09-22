# SECURITY-001

目标：在 Provider 配置与每次 Runtime client 创建时执行出站网络策略，关闭重定向绕过。

1. 校验 http/https、host、userinfo、fragment，并解析全部 DNS 结果。
2. 默认拒绝 loopback、link-local、site-local、CGNAT、benchmark、multicast 与保留地址。
3. 支持精确 host 和 `*.` 子域 allowlist；企业内网仅可显式放行。
4. Provider Runtime 每次创建 client 时重新解析，覆盖 DNS 重绑定后的第二次拒绝。
5. 阻塞与 SSE 客户端都禁止重定向，测试确认目标地址未被访问。
6. 运行 backend/runtime 验证矩阵，证据写入 Harness。
