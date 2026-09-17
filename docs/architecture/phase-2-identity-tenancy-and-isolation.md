# Phase 2 身份、租户、权限与数据隔离（BOSS 版）

版本：v2.0（2026-09-07）
状态：已被 BOSS 控制面架构取代；本文保留作为历史方案索引。

## 结论

Recruitment SaaS 不再自行实现账号注册、密码/验证码认证、Tenant、Company、成员权限、账单、充值、价格和审计主数据。上述能力统一由独立 BOSS 系统提供，Recruitment 只保存招聘业务数据、可重建投影和历史迁移兼容列。

当前生效契约与实现以以下文档和代码为准：

- [Recruitment SaaS 对接 BOSS 实施契约](recruitment-saas-boss-integration.md)
- [BOSS 控制面方案](../../../BOSS/docs/architecture/boss-system-plan.md)
- [BOSS OpenAPI](../../../BOSS/docs/contracts/openapi/boss-openapi-v1.yaml)

## Recruitment 侧边界

- 浏览器只调用 Recruitment BFF；BOSS 用户 access token 仅保存在浏览器内存，BOSS 机器凭证只存在服务端配置。
- 对外业务上下文使用 BOSS `tenant_id`，业务 URL 使用 `/api/v1/tenants/{tenantId}`。
- Recruitment 已移除 `workspace_id` 兼容列；Tenant 是唯一业务隔离与授权上下文。
- 每次受保护业务请求都通过 BOSS 上下文和权限校验；AI 请求还必须经过 BOSS 能力/配额、价格查询、预占和使用量上报。
- BOSS 状态事件通过 `/internal/v1/boss/events` 写入本地 inbox 并更新可重建投影；投影不能绕过实时授权。

## 已下线的旧接口

Recruitment 原本的本地密码登录、个人/企业 Workspace 创建、用户端本地账单/充值、价格管理和本地平台账务接口已删除。历史数据库表只用于迁移和旧数据读取，不得新增依赖；新功能必须遵循 BOSS 对接契约。

历史方案中的 Workspace、个人实名、试用金、账本和成员 API 均不再代表当前产品设计。任何新增文档、测试或 Agent 实现不得引用旧接口作为行为依据。
