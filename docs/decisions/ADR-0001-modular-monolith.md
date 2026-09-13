# ADR-0001：Maven 多模块的模块化单体

- 状态：Accepted
- 日期：2026-09-08

## 背景

Hify 由一人开发，初期 20-50 人使用。单 package 分层会模糊边界；微服务会引入部署、网络、配置、观测和一致性成本。

## 决策

采用一个 Spring Boot 部署单元和 Maven 多模块代码结构。领域模块只通过公开 application port/DTO 交互，禁止跨模块 Repository/Entity/internal 引用。

## 后果

本地事务和部署简单，同时保留模块 owner。未来拆服务仍需补远程协议、幂等、错误和版本语义；“Service 接口存在”不等于零成本拆分。

## 未选方案

- 单模块按 controller/service/repository 分包：短期快，长期域边界弱。
- 微服务：当前规模没有足够收益抵消运维复杂度。

