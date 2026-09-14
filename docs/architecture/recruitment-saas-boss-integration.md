# Recruitment SaaS 对接 BOSS 实施契约

版本：v2.0（2026-09-15）
状态：开发环境实现基线；Recruitment 企业 Tenant 直接对应企业，不存在 Company 中间层。

## 1. 目标与边界

Recruitment SaaS（IntelligentRecruitment）保持独立的业务系统、后端和数据库；BOSS 是统一的身份、租户、成员席位、权限、套餐、积分、能力授权与计量控制面。

本次对接目标是让 Recruitment SaaS 以 BOSS 的用户和产品租户为准入依据，并将需计费的 AI 使用量可靠回传至 BOSS。招聘业务对象仍归 Recruitment SaaS 所有：职位、候选人、简历原文及解析结果、面试、招聘任务、JD 草稿、聊天内容和 AI 执行明细。

不在本次范围：把招聘业务数据迁入 BOSS、让浏览器持有 BOSS 服务端密钥、或继续以 Recruitment SaaS 自有 Workspace 作为跨系统授权边界。

### 当前确认的业务约束

- 招聘产品中一个企业就是一个 `ENTERPRISE` Tenant；个人使用是一个用户对应一个 `PERSONAL` Tenant。用户可以加入多个企业 Tenant。
- 企业 Owner 唯一且默认不占席位；成员加入企业即自动占用席位。Owner 需要使用招聘功能时，在企业成员管理中为自己开通一个已购买席位。
- 套餐、套餐内积分、单购积分包、账单和使用量均挂在 Tenant；积分按所有席位共享，按用户记录消耗，采用 FEFO。
- 已生效套餐保存权益快照；新套餐规则只影响新 Trial、新合同和新积分包，平台可对既有 Tenant 增加或关闭权益覆盖。
- 企业数据池是成员数据的只读副本；成员只能修改自己的源数据，修改后自动同步副本。共享关闭后企业池和同步入口对所有人不可见。
- 企业注册、Trial 发放、合同订单、套餐开通和积分调整均由 BOSS 控制面完成；企业首次购买/续费走合同与运营开通，个人线上支付独立处理。
- 开发环境没有历史数据，不执行 `Company`、旧套餐、旧余额或旧订单迁移，直接使用新的数据库基线。

## 2. 目标架构

```text
Recruitment Web
      |
      | Recruitment 会话 / API
      v
Recruitment SaaS Backend（BFF + 业务服务）
      |                         ^
      | OAuth client_credentials | BOSS 业务事件（至少一次）
      v                         |
 BOSS Internal OpenAPI ---------+---- BOSS Outbox Relay
```

规则如下：

- 浏览器只调用 Recruitment SaaS；不得直接调用 BOSS Internal OpenAPI，也不得获得 `client_secret`。
- Recruitment BFF 保存和刷新 BOSS 的机器访问令牌；令牌、密钥和 Authorization 请求头不得写入日志、前端、监控标签或错误消息。
- BOSS 是身份、租户状态、成员席位、权限、能力和商业授权的最终来源；Recruitment SaaS 仅保存可重建的本地投影和业务侧索引。
- BOSS 对活跃成员自动授予只读基础访问权限；需要成员管理、资料写入等管理能力时，仍通过 BOSS 自定义角色授权。
- Recruitment SaaS 自己负责业务对象级的数据隔离，并在每次受保护的操作前向 BOSS 取得授权结论或使用其短时缓存。

## 3. 上线前必须冻结的 BOSS 契约

在 Recruitment SaaS 开始身份切换和正式联调前，BOSS 团队必须发布与实际 Controller 一致、已通过 OpenAPI lint/契约测试的版本化快照（URL、版本号、SHA-256 或 Git revision）。以下能力是切换的前置条件：

| 编号 | BOSS 契约/配置项 | 当前处理 | 原因 |
| --- | --- | --- |
| B-01 | 登录/刷新响应包含 `user_id`；`GET /api/v1/me` 返回当前 BOSS 用户。 | 已由 BOSS 实现；接入方必须使用。 | Recruitment 无法仅凭 opaque access token 建立用户映射。 |
| B-02 | `GET /api/v1/me/contexts` 返回当前用户可用的 Recruitment Tenant、状态、角色、Owner 和席位标识。 | 已由 BOSS 实现；接入方必须使用。 | 请求必须确定 BOSS 上下文，且不能猜测企业归属。 |
| B-03 | `GET /internal/v1/tenants/{tenantId}/context` 返回已授权 Tenant 状态快照。 | 由 BOSS 内部契约提供。 | 事件采用至少一次投递，消费方需要从丢失或失败事件恢复。 |
| B-04 | 已配置服务间调用凭证、有效套餐/积分规则、Tenant 权益和 AI 能力授权。 | 上线前由 BOSS 平台配置并在沙箱验证。 | 否则授权、配额或 usage-events 会被拒绝。 |
| B-05 | 已配置 Outbox Relay 的 Recruitment 回调 URL 与事件 Bearer token。 | 上线前由双方配置、连通并演练。 | 未配置目标 URL 时，BOSS 会保留 PENDING 事件，不会自动送达。 |

在 B-04、B-05 完成实际环境配置与联调前，不得将 BOSS 身份作为生产唯一登录来源。

## 4. 身份、组织与数据归属

### 4.0 统一登录和密码契约

- BOSS 是唯一保存密码散列、签发登录会话和执行密码策略的系统；Recruitment 不保存密码、密码散列或找回凭据。
- Recruitment 登录页保留原有的“验证码登录 / 密码登录 / 忘记密码”界面和交互，但全部经 Recruitment BFF 调用 BOSS：`/auth/verify`、`/auth/password-login`、`/auth/password-reset`、`/auth/password`。
- 首次验证码登录返回 `password_setup_required=true` 时，前端必须在进入受保护页面前提示设置密码；首次设置操作携带当前 BOSS access token。已有密码的修改必须同时提交 `current_password`，忘记密码只能走验证码重置。
- 忘记密码必须先请求 `POST /api/v1/auth/challenges`，明确传入 `purpose=PASSWORD_RESET`；登录验证码只可用于 `purpose=LOGIN`，两者不得互换。
- 当前密码策略为 8 至 64 位，至少包含一个英文字母和一个数字。连续 5 次密码失败时，BOSS 锁定该密码登录 15 分钟；错误响应不得区分手机号不存在、账号无密码或密码错误。

### 4.1 标识规则

- BOSS UUID 是跨系统主标识：`boss_user_id`、`tenant_id`。
- 招聘业务请求只允许传递 BOSS `tenant_id` 与 `user_id`；不得创建、猜测或拼接 `company_id`。
- 本开发环境没有历史数据，不执行按手机号的迁移匹配，也不自动合并账号。
- Recruitment SaaS 生成的 `execution_id`、`usage_event_id`、`reservation_key` 是其业务幂等键；它们不得被重用。
- 时间使用 RFC 3339 UTC；金额使用最小货币单位（minor units）。

### 4.2 Recruitment SaaS 本地模型调整（当前基线）

> 本项目没有历史迁移任务。下表中的旧 `Company`/`workspace_id` 兼容描述已失效；新代码和新数据库基线必须使用 BOSS `tenant_id`。本地招聘表只保存业务数据及其 Tenant 归属，不得把旧兼容列当作组织或授权事实源。

Recruitment 已完成增量迁移和投影表创建；投影可重建，不能用于绕过 BOSS 授权：

| 数据 | 最低字段/约束 | 说明 |
| --- | --- | --- |
| `boss_company_projections` | `company_id` 主键、`tenant_id`、`company_status`、`tenant_status`、`synchronized_at` | 本地准入缓存，不能覆盖 BOSS 最终结论。 |
| Personal Tenant 上下文 | BOSS `tenant_id`、当前 `user_id`、`tenant_status` | 个人使用没有 `company_id`；Recruitment 仅以同一 BOSS Tenant UUID 映射历史物理 `workspace_id`，不得创建或伪造 Company。 |
| `boss_event_inbox` | `event_id` 主键、`event_type`、`aggregate_id`、`payload`、`received_at`、`processed_at`、`failure_reason` | 事件去重、审计和失败重放。 |
| `boss_legacy_workspace_links` | `legacy_workspace_id`、`company_id` 唯一、`tenant_id`、`migration_source` | 仅用于历史物理列兼容，不是授权边界。 |
| `boss_usage_reports` | `usage_event_id` 主键、`occurred_at` | 保证 Recruitment 重试 usage 上报使用稳定时间戳。 |
| 业务共享数据 | `tenant_id`、`company_id`、索引 `(tenant_id, company_id)` | 职位、人才库、流程模板、公司共享统计等。 |
| 私有业务数据 | 共享字段外加 `private_owner_user_id` | 招聘任务、JD 草稿、AI 会话、筛选草稿和个人结果。 |

目标隔离规则：公司共享数据的键为 `tenant_id + company_id`；私有数据再加 `private_owner_user_id`。Company/Tenant Owner 也不能因角色而直接读取其他用户的私有草稿、任务或结果，除非产品另行定义显式的共享/转交机制。

现有数据库中的 `workspace_id` 是历史物理列：企业记录以 BOSS `company_id` 兼容承载，个人记录以 BOSS Personal `tenant_id` 兼容承载且 `company_id=NULL`。这两个值均来自 BOSS，不是 Recruitment 创建的组织实体。对外企业 BFF URL 使用 `/companies/{companyId}`；后续仅在完成历史数据回填、Worker 对账和数据迁移后再物理重命名列。

## 5. BFF 与 BOSS API 调用规范

### 5.1 机器令牌

Recruitment Backend 使用已由 BOSS 平台配置的 `INTERNAL_SERVICE` client 调用：

```http
POST /oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=...&client_secret=...
```

返回的 Bearer token 有效期约 15 分钟。BFF 应按过期时间缓存，在到期前刷新；单实例并发刷新要合并；失败时不把令牌或密钥暴露给调用方。BOSS 不可用时，禁止以本地缓存“放行”新的高风险写操作。

### 5.2 同步接口清单

以下字段名以当前 BOSS Controller 为准；冻结后的 OpenAPI 版本是联调唯一准绳。

| 场景 | BOSS 调用 | 请求要点 | Recruitment 处理 |
| --- | --- | --- | --- |
| 操作准入 | `POST /internal/v1/authorization/check` | `userId`、`companyId`、`permission`、`capability` | 仅当 `permitted=true` 且 `capability_enabled=true` 执行操作。 |
| AI 启动前配额 | `POST /internal/v1/authorization/quota-check` | `companyId`、`capability` | `authorized=false` 立即拒绝，不启动模型。 |
| AI 价格查询 | `POST /internal/v1/billing/quote` | `companyId`、`capability` | 预占前读取 BOSS 生效单价；Recruitment 不再维护本地价格表。 |
| 预占计量 | `POST /internal/v1/billing/reservations` | `companyId`、稳定的 `reservationKey`、`estimatedAmountMinor` | 预占成功才提交异步 AI 任务。 |
| 释放预占 | `POST /internal/v1/billing/reservations/{reservationKey}/release` | 相同 reservation key | 任务未启动、取消或明确失败时调用。 |
| 使用量回传 | `POST /internal/v1/usage-events` | 见 6.2 | 仅完成业务结果后发送；按稳定事件 ID 幂等重试。 |
| 能力展示 | `GET /internal/v1/capabilities` | 机器 Bearer token | 仅作后台配置/校验，不可替代用户操作授权。 |

授权检查推荐携带当前 BOSS 用户和公司，例如：

```json
{
  "userId": "9e3a27b9-4ef3-4f39-9180-0cb0e2f9ab71",
  "companyId": "3d635253-d1e0-4939-94df-afb62af13a42",
  "permission": "company.job.write",
  "capability": "JD_GENERATION"
}
```

注意：当前请求字段为 camelCase，而 BOSS 授权响应字段为 snake_case，如 `capability_enabled` 与 `permitted`。适配层必须明确做 DTO 映射，禁止通过前端透传 JSON 猜字段名。

### 5.3 权限和能力的使用方式

Recruitment SaaS 维护“业务动作 -> BOSS permission/capability”的显式映射，并为每个动作增加测试。建议初始映射：

| 业务动作 | Permission | Capability |
| --- | --- | --- |
| 查看公司职位/人才 | `company.job.read` | 无（如 BOSS 定义了对应能力则按契约补充） |
| 创建或修改职位 | `company.job.write` | 无 |
| AI 生成 JD | `company.job.write` | `JD_GENERATION` |
| 解析简历 | `company.candidate.write` | `RESUME_PARSE` |
| AI 筛选简历 | `company.candidate.write` | `RESUME_SCREENING` |
| 招聘助手对话 | 对应业务读写权限 | `GENERAL_CHAT` |

具体 permission 字符串必须在 BOSS 权限字典冻结后确认；上述表是 Recruitment 侧调用位点，不得擅自将权限失败降级为只检查本地角色。

## 6. AI 执行、预占与用量结算

### 6.1 可靠流程

1. BFF 从 BOSS 当前上下文确认 `user_id`、`tenant_id`、`company_id` 均有效。
2. 对 AI 动作调用 `authorization/check`，随后调用 `quota-check`。
3. 用与本次执行绑定的稳定 `reservationKey` 预占额度；记录本地 outbox/执行记录后才投递 AI Worker。
4. Worker 完成、部分完成、失败或取消后，以同一 `usageEventId` 上报最终结果。
5. 未开始或可确认未消耗时释放预占；网络不确定时保留幂等键，交由重试任务补偿，不可新建第二个 reservation 或 usage event。

### 6.2 Usage Event 请求

```json
{
  "usageEventId": "recruitment:ai-run:01JXYZ...",
  "executionId": "01JXYZ...",
  "sourceApiClientId": "BOSS 中配置的 Recruitment client UUID",
  "companyId": "3d635253-d1e0-4939-94df-afb62af13a42",
  "capability": "JD_GENERATION",
  "outcome": "SUCCEEDED",
  "successfulUnits": 1,
  "inputTokens": 860,
  "outputTokens": 540,
  "occurredAt": "2026-09-07T08:20:31Z"
}
```

BOSS 接受后返回 `settlement_status=ACCEPTED` 或 `ALREADY_PROCESSED`。二者都应将本地 usage outbox 标记为已结算；不得因超时或 5xx 生成新的 `usageEventId`。

## 7. BOSS 事件接收

### 7.1 回调端点

Recruitment SaaS 提供仅供 BOSS 调用的端点，例如：

```http
POST /internal/v1/boss/events
Authorization: Bearer <RECRUITMENT_SAAS_EVENT_TOKEN>
Content-Type: application/json
```

BOSS Outbox Relay 当前投递格式：

```json
{
  "event_id": "d6428de0-b587-44f3-8af6-d07b8c1b5209",
  "event_type": "company.status.changed",
  "aggregate_type": "company",
  "aggregate_id": "3d635253-d1e0-4939-94df-afb62af13a42",
  "payload": {
    "company_id": "3d635253-d1e0-4939-94df-afb62af13a42",
    "tenant_id": "f6e4c909-f6d2-4fc2-b1f7-6639df530304",
    "status": "SUSPENDED",
    "reason": "..."
  }
}
```

当前需消费的事件包括：

- `company.activated`
- `company.status.changed`
- `tenant.status.changed`

事件是至少一次投递，可能重复、延迟或顺序颠倒。消费者必须在同一数据库事务中先按唯一 `event_id` 写入 `boss_event_inbox`，再更新本地投影和执行必要的业务侧停用动作；重复事件直接返回 2xx。只有事务提交成功才返回 2xx。鉴权失败、格式错误返回 4xx；临时依赖失败返回 5xx 以让 BOSS 重试。

公司或租户不是 `ACTIVE` 时，Recruitment 必须阻止新建/修改/AI 执行，并按产品策略保留只读访问或显示停用提示。事件仅负责快速收敛；关键写操作仍必须走 5.2 的同步授权，不可只相信本地投影。

### 7.2 恢复与对账

消费方对每个已知 `company_id` 调用 `GET /internal/v1/companies/{companyId}/context` 完成状态恢复；BOSS PENDING/FAILED outbox 仍需由平台按运行手册重投。每日至少对账：BOSS 活跃公司/租户、Recruitment 投影、未处理 inbox、BOSS PENDING/FAILED outbox 以及本地未结算 usage outbox。发生不一致时先停止相关公司的可计费操作，按 BOSS 最终状态修复投影，再恢复服务。

## 8. 错误、重试和可观测性

| 情形 | Recruitment 行为 |
| --- | --- |
| 401 OAuth token 失效 | 单飞刷新机器令牌后重试一次；仍失败则失败闭合。 |
| 403 或 `permitted=false` | 不重试，向用户显示无权限/能力未开通。 |
| `COMPANY_NOT_ACTIVE`、`CAPABILITY_NOT_ENABLED`、`MONTHLY_QUOTA_EXCEEDED` | 不启动执行，记录 BOSS request_id 和业务执行 ID。 |
| 429、5xx、网络超时 | 对幂等读写使用指数退避；保留原始 idempotency key。 |
| usage-events 超时 | 不认定失败或成功；以相同 `usageEventId` 重试，接受 `ALREADY_PROCESSED`。 |
| 回调处理失败 | 返回 5xx，保留 inbox 失败原因；经对账/重放恢复。 |

日志和指标必须使用 `request_id`、`event_id`、`company_id`、`tenant_id`、`execution_id`、`usage_event_id` 做关联，但不得记录 access token、client secret、验证码、简历正文或完整个人敏感信息。BOSS 错误信封按 `{code, message, request_id}` 处理，前端仅显示经过本地翻译的安全提示。

## 9. IntelligentRecruitment 已完成项与上线前置项

1. **已完成**：BOSS 用户/Personal Tenant、企业注册、Tenant/Company 上下文、Recruitment BFF 登录、上下文和企业加入。个人使用不创建 Company；营业执照由 Recruitment BFF 透传上传，文件与审核引用由 BOSS 保存。
2. **已完成**：Recruitment 业务 URL 切换至 `/companies/{companyId}`；BOSS 事件 inbox、状态投影、AI 配额/预占/usage 上报接入。
3. **上线前置**：配置 `BOSS_INTERNAL_CLIENT_ID`、`BOSS_INTERNAL_CLIENT_SECRET`、`BOSS_EVENT_BEARER_TOKEN`，并为该 BOSS client 配置有效 contract、tenant scope、能力和价格。
4. **上线前置**：在沙箱完成登录、企业审核、成员权限、余额不足、AI 成功/失败/重试、事件重复/乱序和两家公司隔离的端到端演练。
5. **后续清理**：历史 `workspace_id` 仅在迁移验证完成后物理删除；不得在此之前直接删除历史数据或兼容列。

禁止一次性把 `workspace_id` 替换为 `company_id` 后直接上线；该做法会遗漏私有数据归属、后台任务和历史权限。

## 10. 验收标准与 Agent 工作要求

研发或 Coding Agent 每完成一个步骤都应提交代码、迁移、测试和更新后的契约证据。至少覆盖：

- 无 BOSS `user_id` / context 时不能访问受保护招聘数据。
- 租户、公司、成员权限或能力任一项无效时，写操作和 AI 执行均被拒绝。
- 两家公司及同公司两位用户的共享/私有数据隔离测试通过。
- 同一 BOSS event 投递两次仅生效一次；乱序事件不会把已停用公司重新放行。
- 同一 `usageEventId` 重试不会重复计费；预占失败不启动 Worker；失败执行会释放或可靠补偿。
- 浏览器包、前端源码、日志和错误页中均无 BOSS client secret 或机器令牌。
- BOSS OpenAPI contract test、Recruitment HTTP client contract test、回调集成测试和端到端灰度用例全部通过。

每个 PR 必须写明：使用的 BOSS 契约版本、是否涉及数据迁移、feature flag 名称、回滚方式，以及尚未满足的 BOSS 前置项。遇到契约字段、权限字符串、状态机或重放语义不明确时，停止猜测并反馈给 BOSS 团队冻结后再实现。

## 11. 交付责任划分

| 责任方 | 交付物 |
| --- | --- |
| BOSS 团队 | 冻结且校验过的 OpenAPI、B-01 至 B-05、Integration client 与 scope 配置、事件 Relay 配置、沙箱凭据、状态查询/重放方案。 |
| Recruitment SaaS 团队 | BFF adapter、身份/上下文会话、数据迁移、业务隔离、授权护栏、AI usage outbox、事件 inbox、对账、测试与灰度开关。 |
| 双方 | 契约测试样例、端到端演练、故障恢复演练、上线和回滚 Runbook。 |
