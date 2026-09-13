# Provider 模块交付契约

## 领域四问

1. **是什么**：Provider 是外部模型能力的配置、鉴权、模型目录和协议适配边界，不是一个 URL 表单。
2. **用在哪里**：Agent 发布、Chat Query Loop、Intent Router 和后续 Workflow 都只消费已启用 Provider 的运行快照。
3. **由什么组成**：Provider 配置、ProviderModel 目录、ProviderHealth 状态、CredentialResolver、四类协议 Adapter、管理 API 和 Console。
4. **技术边界**：模块化单体内用 MyBatis-Plus + 小型 Adapter Registry；密钥不入库；健康写与配置写分离；外部调用复用 LlmHttpClient/Resilience4j。

## 一期类型

| 类型 | 默认 Base URL | 协议 |
|---|---|---|
| `OPENAI` | `https://api.openai.com/v1` | OpenAI Chat Completions |
| `ANTHROPIC` | `https://api.anthropic.com` | Anthropic Messages |
| `GEMINI` | `https://generativelanguage.googleapis.com/v1beta` | Gemini generateContent |
| `OPENAI_COMPATIBLE` | 必填 | OpenAI-compatible Chat Completions |

`MOCK` 是内部 bootstrap 类型，管理 API 拒绝创建。

Chat Completions/generateContent 是一期跨供应商公共协议基线，不代表否认 OpenAI Responses 或 Gemini Interactions 等新接口；升级必须由流式、usage、服务端状态等明确需求触发，并保持 `ModelClient` 上层契约稳定。

协议核对入口：[OpenAI Chat Completions](https://platform.openai.com/docs/api-reference/chat/create)、[Anthropic Messages](https://platform.claude.com/docs/en/api/http/messages/create)、[Gemini generateContent](https://ai.google.dev/api/generate-content)。

## JSON 鉴权契约

```json
{
  "version": 1,
  "credentialRef": "env:OPENAI_API_KEY",
  "headerName": "Authorization",
  "prefix": "Bearer "
}
```

响应只返回 `credentialConfigured`，不返回 JSON 或 credentialRef。更新时 `auth` 缺省表示保留现有鉴权；显式提供则整体替换并重新校验。切换 Provider 类型时必须同时提交新鉴权，避免沿用旧协议的 Header。

## 模型目录

```json
{
  "displayName": "GPT-4.1 Mini",
  "modelId": "gpt-4.1-mini",
  "enabled": true,
  "isDefault": true
}
```

同一 Provider 的 `modelId` 唯一；必须至少有一个启用模型且只能有一个默认模型。Agent 和运行时保存/发送 `modelId`，Console 显示 `displayName`。

## 健康语义

- `UNKNOWN`：从未测试或配置已改变。
- `HEALTHY`：最近一次最小模型请求成功。
- `UNHEALTHY`：认证、限流、超时、供应商不可用或响应无效。

健康表只保存状态、延迟、检查时间、稳定错误码和脱敏消息。任何响应体、Header 或秘密都不得写入。

## API 与验收

- `GET/POST /api/v1/providers`
- `GET/PUT/DELETE /api/v1/providers/{publicId}`
- `POST /api/v1/providers/{publicId}/connection-tests`

验收覆盖四类型校验、分页、创建/更新/逻辑删除、模型目录约束、credentialRef 不回传、健康独立写入、缓存驱逐、三种原生协议请求/响应映射和前端真实 CRUD。
