# Recruitment SaaS 与 BOSS 对接契约

版本：v3.0（2026-09-20）
状态：开发环境实现基线。

## 1. 目标与边界

BOSS 是统一的身份、用户会话、Tenant、成员、角色、权限、审核、套餐、权益、订单、积分和 AI 授权控制面。

IntelligentRecruitment 保持招聘业务独立，负责职位、候选人、简历、招聘任务、面试、筛选、AI 业务记录和工单。

AIAgentPlatform 只执行已经由 BOSS 授权的 AI 任务。

浏览器只访问招聘服务或 Admin 对应的 BOSS 接口，不得持有服务端 client secret。

## 2. 用户与 Tenant

- 跨系统用户标识使用 BOSS user UUID。
- 招聘业务边界使用 BOSS tenant_id。
- IR 不创建本地用户、Tenant、成员、管理员或计费权威记录。
- 每次受保护的招聘操作都需要通过 BOSS 上下文和权限校验。
- 招聘表只保存业务数据、tenant_id 和必要的 BOSS UUID。
- 当前版本不提供个人实名认证提交功能。

## 3. Admin

Admin 直接调用 BOSS 平台接口：

- 管理员 Bootstrap、注册、登录和 Token 刷新；
- 管理员、角色、权限和菜单；
- 用户、企业认证和成员申请审核；
- 产品、套餐、权益、订单、充值和积分配置。

IR 不提供平台管理员登录、平台管理员权限过滤器或审核查询 BFF。

## 4. 招聘服务认证

IR 的登录接口是 BOSS 认证 BFF：

- 验证码登录和密码登录转发到 BOSS；
- 密码设置、修改和重置转发到 BOSS；
- 当前用户信息从 BOSS 获取；
- IR 不保存密码、密码散列或本地用户会话。

## 5. 工单契约

用户端接口：

- GET /api/v1/tenants/{tenantId}/tickets
- GET /api/v1/tenants/{tenantId}/tickets/{ticketId}
- POST /api/v1/tenants/{tenantId}/tickets
- POST /api/v1/tenants/{tenantId}/tickets/{ticketId}/messages

创建工单时：

- creator_user_id 必须来自当前 BOSS 登录上下文；
- creator_name 是展示名称快照；
- tenant_id 必须经过 BOSS Tenant 权限校验；
- 不接受客户端自行指定 creator_user_id；
- 工单所有权按 creator_user_id 和 tenant_id 校验。

BOSS 通过独立服务凭证调用 IR 内部工单接口，实现查询、创建、回复、分配、状态更新和关闭。

assigned_to_id 保存 BOSS 管理员 UUID，不建立 IR 本地管理员外键。

## 6. AI 调用契约

IR 调用 AIAgentPlatform 前必须完成 BOSS 授权和积分预占。

请求至少包含：

- execution_id；
- request_id 和 trace_id；
- tenant_id；
- actor_id；
- business_task_id；
- idempotency_key；
- capability；
- policy_decision；
- data_handling；
- input_versions。

AIAgentPlatform 完成、失败、取消或重试后，通过 BOSS 正式 usage-reports 或 execution-cancellations 契约完成用量上报和结算。

IR 不保留模型直连、本地扣费、本地预占或旧 settle 兼容路径。

## 7. 服务凭证

IR 到 BOSS 的内部调用使用：

- BOSS_INTERNAL_CLIENT_ID
- BOSS_INTERNAL_CLIENT_SECRET

BOSS 到 IR 的内部工单调用使用 IR 配置的独立服务凭证。

服务凭证只能保存在服务端环境变量或密钥管理系统中，不得进入浏览器、日志、错误响应或前端构建产物。

## 8. 数据库边界

IR 开发基线只包含当前招聘业务、工单、审计、异步处理和文件元数据表。

不包含：

- 本地平台管理员表；
- 本地平台菜单表；
- 本地个人认证审核表；
- 本地企业审核表；
- 本地成员申请表；
- 本地套餐、充值和 billing 表；
- 本地 Tenant、成员和权限权威表。

## 9. 故障与幂等

- BOSS 认证失败不得使用本地身份放行；
- Tenant 权限拒绝不重试业务写入；
- 内部调用保留 request_id 和幂等键；
- AI usage 上报超时必须使用原幂等键重试；
- 重复上报必须由 BOSS 幂等处理；
- 服务凭证缺失时明确返回配置错误。

## 10. 验收要求

- 全新开发库只执行当前 V1 基线；
- 六张旧认证/管理员表不存在；
- 旧平台菜单、旧本地 billing 和旧审核表不存在；
- IR 不存在旧平台管理员认证或本地审核查询入口；
- Admin 审核请求直接到 BOSS；
- 用户工单与登录用户 BOSS UUID 正确关联；
- Tenant 名称来自 BOSS；
- AI 调用必须经过 BOSS 授权后到 AIAgentPlatform；
- 全量 Maven 测试、Flyway 启动和 BOSS/IR 契约测试通过。
