---
name: platform-operations
description: Design, implement, or review the BOSS-owned platform operations system and its recruitment-service ticket integration.
---

# Platform Operations

平台运营后台由 BOSS 统一提供身份、管理员会话、角色权限、产品套餐、权益、订单和审核数据。IntelligentRecruitment 只负责招聘业务和工单业务，不维护第二套平台管理员或运营菜单存储。

## Scope

- Admin 前端通过 BOSS 的管理员认证和平台管理接口工作。
- 企业认证、成员申请和用户审核查询直接调用 BOSS。
- 招聘服务保存招聘域工单和工单消息。
- 用户工单使用普通用户 Bearer Token，并按 BOSS 返回的 Tenant 权限校验。

## Service boundaries

- BOSS：用户身份、管理员身份、Tenant、成员关系、权限、审核、产品、权益、订单和积分。
- IntelligentRecruitment：招聘任务、候选人、职位、面试、筛选、工单及其消息。
- AIAgentPlatform：已授权 AI 任务的模型执行。

## Ticket invariants

- support_tickets.creator_user_id 保存 BOSS 用户 UUID，不建立本地身份外键。
- support_tickets.creator_name 是创建时的展示名称快照，不是身份权威源。
- support_tickets.tenant_id 保存 Tenant UUID；Tenant 名称通过 BOSS 查询。
- 用户工单只能由当前登录用户访问；平台管理员通过 BOSS 的内部服务凭证操作工单。
- 工单消息只追加，不修改或删除历史消息。

## Review and authentication

- Admin 登录、Bootstrap、注册、刷新和权限检查均调用 BOSS。
- 招聘服务不提供平台管理员登录、运营审核列表或本地管理员权限接口。
- 当前版本暂不提供个人实名认证提交功能。

## Implementation workflow

1. 先确认 BOSS 的管理员和审核 API 契约。
2. 招聘业务接口使用 BOSS Bearer Token 进行实时 Tenant/权限校验。
3. 工单内部接口使用独立服务凭证，并校验请求来源。
4. 所有跨服务调用记录可追踪的请求标识和幂等键。
5. 使用数据库和契约测试验证 BOSS 与招聘服务边界。

## Non-negotiable invariants

- IR 不创建或维护第二套用户、Tenant、管理员、审核或积分权威数据。
- 不恢复旧的本地管理员认证、旧平台菜单表或旧本地 billing 表。
- creator_user_id 必须来自当前认证上下文，不能由浏览器自由指定。
- Tenant 访问必须通过 BOSS 授权结果。
