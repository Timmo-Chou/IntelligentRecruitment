# 平台运营与招聘服务边界设计

## 1. 目标

平台运营能力由 BOSS 统一承载。IntelligentRecruitment 不再维护第二套平台管理员、审核、Tenant、成员、套餐或计费权威数据。

## 2. 系统职责

| 能力 | 负责系统 |
|---|---|
| 用户身份、会话和管理员登录 | BOSS |
| Tenant、成员、角色和权限 | BOSS |
| 企业认证、成员申请和审核查询 | BOSS |
| 产品、套餐、权益、订单和积分 | BOSS |
| 招聘任务、职位、候选人、简历和面试 | IntelligentRecruitment |
| 招聘工单及工单消息 | IntelligentRecruitment |
| 已授权 AI 任务执行 | AIAgentPlatform |

## 3. Admin 调用方式

Admin 直接调用 BOSS 平台接口完成：

- Bootstrap、管理员注册、登录和 Token 刷新；
- 管理员列表、管理员权限和菜单；
- 企业、用户、企业认证和成员申请审核；
- 产品、权益、订单、充值和积分配置。

IntelligentRecruitment 不提供平台管理员登录或审核查询 BFF。

## 4. 招聘服务认证

招聘服务通过 BOSS BFF 完成普通用户认证：

1. 用户登录请求由招聘服务转发到 BOSS；
2. BOSS 返回用户访问令牌；
3. 招聘服务每次请求使用 BOSS 令牌解析当前用户；
4. Tenant 访问权限通过 BOSS 实时校验；
5. 招聘服务只保存招聘业务数据和 BOSS 用户 UUID。

当前版本不提供个人实名认证提交功能，不返回本地占位状态。

## 5. 工单设计

### 5.1 用户端接口

- GET /api/v1/tenants/{tenantId}/tickets
- GET /api/v1/tenants/{tenantId}/tickets/{ticketId}
- POST /api/v1/tenants/{tenantId}/tickets
- POST /api/v1/tenants/{tenantId}/tickets/{ticketId}/messages

用户创建工单时：

- creator_user_id 取自当前 BOSS 登录用户；
- creator_name 保存创建时的展示名称快照；
- tenant_id 取请求 Tenant，并通过 BOSS 校验权限；
- 不接受浏览器直接指定 creator_user_id；
- 不依赖本地用户表或本地身份外键。

### 5.2 平台侧工单接口

BOSS 通过独立服务凭证调用招聘服务内部工单接口，可执行：

- 查询工单；
- 创建代办工单；
- 回复工单；
- 分配工单；
- 修改状态；
- 关闭工单。

assigned_to_id 保存 BOSS 管理员 UUID，不建立本地管理员外键。

### 5.3 工单数据表

support_tickets 保存工单主体、BOSS 用户 UUID、Tenant UUID、名称快照和状态。

support_ticket_messages 保存追加式消息记录。历史消息不可修改或删除。

Tenant 名称通过 BOSS 查询，不在招聘服务维护企业名称权威副本。

## 6. AI 边界

招聘服务只能在完成 BOSS 授权、积分预占和执行上下文构造后调用 AIAgentPlatform。

招聘服务不保留模型直连、旧本地扣费或本地积分结算路径。

## 7. 数据库边界

招聘服务数据库只保留招聘业务表、工单表、审计和异步处理所需表。

以下内容不在招聘服务数据库中维护：

- 平台管理员；
- 平台运营菜单；
- 个人认证审核记录；
- 企业认证审核记录；
- 成员申请；
- 本地套餐、充值和计费账本；
- Tenant、成员和权限权威数据。

## 8. 安全约束

- BOSS 用户令牌和服务凭证不能暴露给浏览器；
- 内部工单接口必须校验服务凭证；
- Tenant 访问必须通过 BOSS 授权；
- creator_user_id 必须来自认证上下文；
- AI 调用必须携带幂等键、Tenant、用户和授权执行上下文；
- 敏感操作写入审计记录。

## 9. 验证要求

- 全新开发数据库执行 Flyway 成功；
- 旧认证、管理员、审核和本地计费表不存在；
- IR 运行时代码不包含旧管理员认证和本地审核查询；
- Admin 审核接口请求直接到 BOSS；
- 用户工单与登录用户 UUID 正确关联；
- 工单 Tenant 名称来自 BOSS；
- AI 请求只能通过 BOSS 授权后的 AIAgentPlatform 链路执行。
