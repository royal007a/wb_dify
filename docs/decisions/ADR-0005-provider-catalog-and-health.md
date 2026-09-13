# ADR-0005：Provider 目录、鉴权与健康状态

- 状态：Accepted
- 日期：2026-09-13
- 范围：`hify-provider`、Provider Console、模型运行时

## 背景

Provider 是 Agent、Chat、Intent Router 的共同上游。现有实现只有 `MOCK/OPENAI_COMPATIBLE`、list/create 和一张 `model_providers` 表；前端仍使用 mock 数据。若直接继续加字段，会把供应商协议、凭证、模型目录和高频健康写入耦合在一个对象里。

## 决策过程

### 支持哪些供应商

候选是“所有厂商插件化”“只做 OpenAI-compatible”“固定主流协议 + 通用兼容”。一期面向 20–50 人内部团队，插件框架的安装、版本和进程边界成本过高；只做 OpenAI-compatible 又无法诚实覆盖 Anthropic 和 Gemini 的原生消息/工具协议。因此选择 OpenAI、Anthropic、Gemini 三个原生协议，加 OpenAI-compatible 通用类型。`MOCK` 仅用于开发和测试，不出现在管理 API 的可选类型中。

### 是否引入模型供应商框架

不引入。四种协议由小型 `ProviderProtocolAdapter` 注册表管理，统一输出现有 `ModelClient`。当协议数、独立发布频率或第三方扩展需求显著增加时，再评估插件 SDK/daemon。

一期协议面选择能与现有 Query Loop 对齐的无状态公共交集：OpenAI 使用 Chat Completions，Gemini 使用 generateContent。两家官方都已有更新的 agentic API，但直接切换会同时改变会话状态、工具事件和流式语义；只有 usage/streaming/服务端状态等需求与评测证明收益时，才以新 Adapter/ADR 升级，不把供应商演进泄漏给上层运行时。

### 鉴权如何存储

使用版本化 JSON 结构保存鉴权元数据，但 JSON 中只允许 `credentialRef/headerName/prefix`，禁止保存 token、API Key 或 Authorization 值。秘密由环境变量或受控 Secret Resolver 提供。这样既保留厂商差异，也不推翻 ADR-0004 的“原始凭证不入库”。

### 模型如何表示

模型是 Provider 的子资源。`displayName` 面向人，`modelId` 是发给供应商的稳定调用标识，两者不得混用；同一 Provider 下 `modelId` 唯一，并且恰有一个启用模型作为默认值。Provider 保存冗余的 `defaultModelId`，用于运行时一次读取；更新时由事务校验模型目录和默认值一致。

### 健康状态为什么独立

连通性测试是高频、易抖动的运维写入，Provider 配置是低频管理数据。把健康字段放在 Provider 行会制造无意义的行版本竞争、缓存失效和审计噪声。因此 `provider_health` 以 `provider_id` 一对一独立更新；Provider 配置缓存不包含健康状态。

### ID 与持久化

内部表使用 `BIGINT` 自增并复用 `BaseEntity` 的创建、更新时间和逻辑删除；外部 API 使用随机 `publicId`，不暴露内部规模。旧字符串 ID 迁移为 `public_id`，Agent 继续引用外部 ID，避免一次性改写会话与运行记录。

## 后果

- Provider 管理走 MyBatis-Plus，不再由 Controller 直接访问 JPA Repository。
- 配置读取使用 `provider-cache`；创建、更新、删除驱逐缓存，健康测试不驱逐配置缓存。
- 连接测试通过真实协议发一个最小请求，可能产生极少量供应商费用；结果只保存脱敏错误分类。
- 当前不做自动模型发现、负载均衡、多凭证轮换、插件市场和流式协议改造。
