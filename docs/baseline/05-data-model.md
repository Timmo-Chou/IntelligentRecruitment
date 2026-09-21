# 当前招聘服务数据模型基线

> 本文描述当前实现，不保留历史 Company、Workspace、本地计费或本地审核模型。BOSS 是身份、Tenant、成员、权限和商业数据的权威系统。

## 1. 归属规则

- 招聘业务表使用 tenant_id。
- 用户和管理员字段保存 BOSS UUID。
- 业务操作前通过 BOSS 校验当前用户和 Tenant 权限。
- 本地表不保存用户密码、会话、成员关系、套餐、权益或积分权威数据。

## 2. 招聘业务

主要表：

- jobs、job_versions
- candidates、resume_files、resume_source_files
- resume_parse_drafts、resume_parse_versions
- recruitment_tasks、conversations、messages
- jd_drafts、jd_source_files、jd_run_events
- screening_plans、screening_plan_versions、screening_runs、screening_run_items、screening_results
- interview_kits、interview_kit_versions、interview_questions

这些表保存招聘业务本身及其 tenant_id，不保存本地 Tenant 权威对象。

## 3. AI 执行

ai_runs 保存：

- business operation 和招聘任务关联；
- tenant_id、created_by、capability；
- BOSS 授权执行上下文；
- AIAgentPlatform provider task id；
- attempt、idempotency、状态、进度、错误和时间；
- 输入版本引用、策略决策和数据处理策略。

IR 不保存模型价格、积分预占、结算或供应商账本。AI 使用量通过 BOSS 正式用量契约处理。

## 4. 工单

support_tickets 保存：

- creator_user_id：当前 BOSS 登录用户 UUID；
- creator_name：创建时的名称快照；
- tenant_id：BOSS Tenant UUID；
- assigned_to_id：BOSS 管理员 UUID；
- 标题、分类、优先级、状态和时间。

support_ticket_messages 保存追加式消息。工单用户所有权由 creator_user_id 和 tenant_id 校验，Tenant 名称通过 BOSS 查询。

## 5. 基础设施和可靠性

- audit_logs：敏感业务操作审计；
- idempotency_records：幂等请求和结果引用；
- outbox_events：本地异步任务和事件投递；
- file_assets：对象存储文件元数据；
- notifications：招聘服务通知；
- verification_challenges：仅用于招聘服务转发 BOSS 认证流程所需的短期状态；
- foundation_async_probe：本地异步基础设施探针。

## 6. 明确不属于招聘服务数据库的内容

- 用户身份和密码；
- 用户会话；
- 平台管理员；
- 审核申请和审核状态；
- Tenant、成员和角色权威数据；
- 套餐、权益、订单、充值和积分账本；
- 平台运营菜单；
- 个人实名认证提交记录。

## 7. 迁移要求

开发环境从单一 V1 当前基线初始化。新增表或字段必须先确认归属系统和 Tenant 边界，不得恢复旧的本地身份、组织或计费模型。
