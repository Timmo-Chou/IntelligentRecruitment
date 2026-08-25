# 核心数据模型

> 本文的单层 `Organization` 模型已被 [Phase 2 身份、租户、权限与数据隔离方案](../architecture/phase-2-identity-tenancy-and-isolation.md) 部分取代。新开发必须使用 `Company + Workspace`：Company 是企业治理主体，Workspace 是招聘数据、权限和 MVP 账本的租户边界；旧文中的业务 `organization_id` 应理解为待迁移的 `workspace_id`。

## 1. 建模原则

- 业务实体按招聘领域建模，不按 Agent 名称建表。
- 业务服务拥有事实数据，AI Platform 只返回结果和技术用量。
- 可能影响历史解释的输入使用不可变版本。
- 多租户记录必须带 `organization_id`。
- 金额精确、账本不可变、调用和回调幂等。
- 原始简历在对象存储，数据库保存元数据和业务引用。

## 2. 关系总览

```text
Organization
├── Membership ── User
├── WalletAccount ── BillingLedgerEntry
├── Job ── JobVersion
├── Candidate ── ResumeFile ── ResumeParseVersion
└── RecruitmentTask
    ├── Conversation ── Message
    ├── AI run
    ├── ScreeningPlan ── ScreeningPlanVersion
    ├── ScreeningRun ── ScreeningRunItem ── ScreeningResult
    └── InterviewKit ── InterviewQuestion
```

## 3. 身份与租户

### `users`

- `id`
- `phone`（受保护、唯一策略需考虑区域）
- `nickname`
- `avatar_asset_id`
- `status`
- `created_at`、`updated_at`

### `organizations`

- `id`
- `name`
- `industry`
- `size_band`
- `status`
- `created_at`、`updated_at`

### `memberships`

- `id`
- `organization_id`
- `user_id`
- `role`：P0 先支持 `admin/recruiter` 或只启用 `admin`
- `status`
- `joined_at`

唯一约束：`organization_id + user_id`。

## 4. 职位

### `jobs`

稳定职位身份：

- `id`、`organization_id`
- `title`
- `status`：`draft/active/archived`
- `current_version_id`
- `source`：`manual/ai_generated/workflow`
- `created_by`、时间字段

### `job_versions`

- `id`、`organization_id`、`job_id`
- `version_number`
- 岗位、部门、地点、用工性质、招聘人数、薪资范围
- 职责、必备条件、优先条件、教育/经验要求
- 技能标签、人才画像、风险/待确认项
- `source_ai_run_id`
- `created_by`、`confirmed_at`

确认后不可原地修改。`job_id + version_number` 唯一。

## 5. 候选人与简历

### `candidates`

- `id`、`organization_id`
- 脱敏展示名
- 加密姓名、联系方式字段或受控引用
- `status`
- 去重辅助哈希（具体规则待确认）
- 创建人和时间字段

同一自然人在不同组织中是不同业务记录。

### `file_assets`

- `id`、`organization_id`
- `object_key`
- 原始文件名（展示前清理）
- MIME、Magic Type、大小、哈希
- 安全扫描状态
- 存储区域、保留/删除状态

不保存长期签名 URL。

### `resume_files`

- `id`、`organization_id`
- `candidate_id`（解析/匹配前可空）
- `file_asset_id`
- `upload_status`
- `parse_status`
- `uploaded_by`
- 时间字段

### `resume_parse_versions`

- `id`、`organization_id`、`resume_file_id`、`candidate_id`
- `version_number`
- 基本信息、教育、履历、技能、项目等结构化 JSON/子结构
- 解析置信度和缺失字段
- `source_ai_run_id`
- `schema_version`
- `created_at`

解析结果的人工纠正创建新版本或可审计修订，不直接抹除 AI 原始版本。

## 6. 招聘任务与对话

### `recruitment_tasks`

- `id`、`organization_id`
- `title`
- `status`：`draft/active/completed/cancelled`
- 当前能力/阶段
- 关联 `job_id/current_job_version_id`
- `created_by`、时间字段

### `conversations`

- `id`、`organization_id`、`recruitment_task_id`
- `status`
- `summary`
- 时间字段

### `messages`

- `id`、`organization_id`、`conversation_id`
- `role`：`user/assistant/system/tool`
- `content` 或受控结构
- `capability`
- `source_ai_run_id`
- `usage_display_reference`
- 时间字段

消息中避免重复保存大段简历正文；使用授权业务引用和必要摘录。

## 7. 筛选

### `screening_plans`

- `id`、`organization_id`
- `job_id`
- `current_version_id`
- `status`

### `screening_plan_versions`

- `id`、`organization_id`、`screening_plan_id`
- `job_version_id`
- `version_number`
- 评估维度、权重、说明
- 必须项、排除项、缺失信息规则
- `source_ai_run_id`
- `confirmed_by`、`confirmed_at`

### `screening_runs`

- `id`、`organization_id`、`recruitment_task_id`
- `job_version_id`
- `screening_plan_version_id`
- `status`
- 总数、完成、成功、失败统计
- 费用预估/预占/结算引用
- 时间字段

### `screening_run_items`

- `id`、`organization_id`、`screening_run_id`
- `candidate_id`
- `resume_parse_version_id`
- `status`
- 当前 Attempt、错误码、可重试标志

唯一约束建议：同一 Run 下 Candidate/ParseVersion 不重复。

### `screening_results`

- `id`、`organization_id`、`screening_run_item_id`
- `score`、`level`
- 匹配点、不匹配点、可协商项
- 缺失信息、风险和证据
- 人工复核建议
- `ai_run_id`
- `contract_schema_version`
- 生成时间

## 8. 面试题

### `interview_kits`

- `id`、`organization_id`
- `recruitment_task_id`
- `job_version_id`
- `candidate_id`
- `resume_parse_version_id`
- `screening_result_id`
- `version_number`
- `status`：`generated/edited/confirmed/archived`
- `source_ai_run_id`
- 时间字段

### `interview_questions`

- `id`、`organization_id`、`interview_kit_id`
- `order_number`
- `category`
- `question`
- `reason`
- `competency`
- `follow_ups`
- `scoring_guide`
- `evidence_refs`

## 9. AI 集成

### `ai_runs`

- `id`、`organization_id`
- `business_task_id`
- `business_operation_type/id`
- `capability`
- `provider_adapter`
- `ai_task_id`
- `attempt_number`
- `idempotency_key`
- `status`、`progress`
- 输入版本引用/摘要哈希
- 契约版本、Prompt/模型摘要
- 错误码和可重试标志
- 时间字段

不保存包含候选人 PII 的完整 Prompt 日志。

### `usage_records`

- `id`、`organization_id`、`ai_run_id`
- 输入/输出 Token
- Provider Unit
- 供应商成本和币种
- 上报事件 ID
- 时间字段

供应商成本不等于客户收费。

## 10. 计费

### `wallet_accounts`

- `id`、`organization_id`
- 币种
- 可用余额投影、预占投影
- 投影版本和更新时间

### `billing_ledger_entries`

- `id`、`organization_id`、`wallet_account_id`
- `entry_type`
- 精确金额/最小货币单位
- `business_reference_type/id`
- `idempotency_reference`
- `related_entry_id`
- `status`
- 时间字段

唯一约束保证同一业务事件不会重复入账。修正使用补偿记录。

## 11. 可靠性与审计

### `idempotency_records`

- 组织、Actor、操作类型、Key
- 请求摘要
- 处理中/完成状态
- 可重放结果引用
- 过期时间

### `outbox_events`

- 事件类型、聚合 ID、Payload 引用
- 发送状态、尝试次数、下次尝试时间
- 创建/发送时间

### `audit_logs`

- 组织、Actor、动作、资源
- 安全结果、原因、来源 IP/设备摘要
- 不含敏感正文的变更摘要
- 时间字段

重点审计 PII Reveal、原简历下载、导出、删除、角色变化、账本调整和 AI 契约管理。

## 12. 删除与保留

- 业务删除优先使用状态/软删除，避免破坏账本和审计。
- 候选人永久删除必须覆盖原文件、解析版本、派生筛选/面试结果以及伙伴临时副本。
- 无法删除的法定账务/审计记录应匿名化业务内容并保留最小必要字段。
- 具体保留期和删除 SLA 进入待确认事项。
