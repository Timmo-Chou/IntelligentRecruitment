# BOSS 开放平台接入指南

> 版本：v1.0 ｜ 更新日期：2026-09-07 ｜ 适用对象：需要接入 BOSS 身份、企业、权限、计费与事件能力的服务端应用

## 1. 接入概览

BOSS 是用户、租户、企业、成员权限、能力授权与计费的唯一权威来源。接入方保留自己的业务数据和业务 API，但必须在服务端调用 BOSS API 来确认用户身份、企业上下文和可计费能力。

```text
用户浏览器 ──> 接入方前端 ──> 接入方服务端 ──> BOSS OpenAPI
                                      ^              |
                                      └── BOSS 事件 ─┘
```

**请先确认边界：** 浏览器、移动端和小程序不得保存 `client_secret`，也不得直连 `/internal/v1/**`。机器凭证、BOSS 用户 access token 与事件密钥只能存在于接入方服务端。

## 2. 完整接入顺序

1. 在 BOSS 开放平台创建或领取 `INTERNAL_SERVICE` 类型 API Client。
2. 由 BOSS 管理员为该 Client 配置 Contract、允许的 tenant scope、所需 capability、价格或额度。
3. 在接入方服务端安全地配置 `BOSS_BASE_URL`、`BOSS_INTERNAL_CLIENT_ID`、`BOSS_INTERNAL_CLIENT_SECRET` 与 `BOSS_EVENT_BEARER_TOKEN`。
4. 实现用户登录、刷新、`/me` 与企业上下文读取；以 BOSS `user_id`、`tenant_id`、`company_id` 作为跨系统标识。
5. 在每个受保护业务操作前读取企业上下文并执行 BOSS 授权检查；不要以本地角色或本地缓存替代 BOSS 结论。
6. 对 AI 或其他计费动作，按“授权/额度 → 报价 → 预占 → 执行 → 用量回传”的顺序实现，失败时使用原幂等键补偿。
7. 配置事件回调，按 `event_id` 去重并更新本地可重建投影。
8. 使用第 11 节清单完成沙箱联调，再开启生产流量。

不要调换第 5、6 步：未确认企业、权限、能力和额度前，不得启动可计费任务。

## 3. 上线前准备

### 3.1 向 BOSS 申请的配置

请提供接入系统名称、回调 URL、目标环境和需要的 capability。BOSS 管理员需要完成以下配置：

| 配置项 | 用途 | 接入方验收方式 |
| --- | --- | --- |
| `INTERNAL_SERVICE` API Client | 获取机器访问令牌 | 能成功调用 `/oauth2/token` |
| Contract 与 tenant scope | 限制 Client 的服务边界 | 未授权租户请求被拒绝 |
| capability、价格和额度 | 控制可计费能力 | quote、quota-check 和 reservation 结果符合预期 |
| Outbox Relay 目标与事件密钥 | 向接入方投递状态事件 | 回调收到测试事件且鉴权通过 |

### 3.2 服务端环境变量

```bash
BOSS_BASE_URL=https://boss.example.com
BOSS_INTERNAL_CLIENT_ID=由开放平台分配的 Client ID
BOSS_INTERNAL_CLIENT_SECRET=由开放平台分配的 Client Secret
BOSS_EVENT_BEARER_TOKEN=双方约定的高强度随机回调密钥
```

将密钥注入密钥管理系统或部署环境；不要提交到仓库、前端构建变量、日志、监控标签或错误响应。生产环境使用 HTTPS，并限制回调入口的网络来源。

## 4. 两类认证令牌

### 4.1 用户 access token：用户态接口

登录、用户资料、企业上下文、企业注册、加入企业和账单查询使用用户 access token：

```http
Authorization: Bearer <BOSS 用户 access token>
```

接入方可将登录页面请求转发至 BOSS，也可在自己的 BFF 中封装。每次需要识别用户时应调用 `GET /api/v1/me`；不要仅凭 token 字符串解析用户身份。

### 4.2 机器 access token：内部服务接口

权限检查、额度检查、报价、预占和用量回传使用机器令牌。服务端按以下请求获取令牌：

```http
POST /oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=<client_id>&client_secret=<client_secret>
```

返回体至少包含 `access_token`、`expires_in` 和 `api_client_id`。将令牌缓存在服务端，并在到期前刷新；多并发实例需合并刷新请求。当前接入实现会在剩余不足 30 秒时刷新，默认有效期按 900 秒处理。

## 5. 用户与企业上下文

### 5.1 登录与会话

| 场景 | BOSS API | 调用顺序 |
| --- | --- | --- |
| 验证码登录 | `POST /api/v1/auth/challenges` → `POST /api/v1/auth/verify` | challenge 的 `purpose=LOGIN`；使用返回的 `challenge_id` 验证 |
| 密码登录 | `POST /api/v1/auth/password-login` | 直接提交 `phone`、`password` |
| 刷新会话 | `POST /api/v1/auth/refresh` | 服务端携带 `boss_refresh` Cookie |
| 忘记密码 | `POST /api/v1/auth/challenges` → `POST /api/v1/auth/password-reset` | challenge 的 `purpose=PASSWORD_RESET`，不可复用登录验证码 |
| 设置/修改密码 | `POST /api/v1/auth/password` | 首次设置可不传 `currentPassword`；已有密码时必须传当前密码 |

`verify`、`password-login` 与 `password-reset` 的响应包含 `user_id`、`access_token`、`expires_at`、`new_user`、`password_setup_required`。若 `password_setup_required=true`，应在进入受保护页面前引导用户设置密码。

### 5.2 获取并校验企业上下文

```http
GET /api/v1/me
Authorization: Bearer <user_access_token>

GET /api/v1/me/contexts
Authorization: Bearer <user_access_token>
```

`/me/contexts` 返回 `user_id` 和 `companies`；每个企业项包含 `company_id`、`tenant_id`、`legal_name`、`entity_type`、`company_status`、`tenant_status`、`is_company_owner`。业务请求必须从该列表选定 `company_id`，并确认 `company_status` 与 `tenant_status` 均为 `ACTIVE`。

**不能做：** 不能根据手机号、邮箱或企业名称推断公司归属；不能把历史 `workspace_id` 当作跨系统授权标识；不能把 Company Owner 当成读取其他用户私有业务数据的默认权限。

### 5.3 企业生命周期接口

```text
POST /api/v1/companies/registrations
GET  /api/v1/companies/search?q=<query>
POST /api/v1/companies/{companyId}/membership-applications
GET  /api/v1/me/company-registrations/pending
GET  /api/v1/companies/{companyId}/wallet
GET  /api/v1/companies/{companyId}/statements
```

以上均使用用户 access token。注册企业时提交 `legalName`、`creditCode`、`licenseReference`、`contactName`、`contactPhone`；不要把未审核的企业当作已启用企业使用。

## 6. 授权与能力检查

每一个受保护操作都先确认当前用户、公司上下文和企业状态，然后使用机器令牌调用授权检查：

```http
POST /internal/v1/authorization/check
Authorization: Bearer <machine_access_token>
Content-Type: application/json

{
  "userId": "9e3a27b9-4ef3-4f39-9180-0cb0e2f9ab71",
  "companyId": "3d635253-d1e0-4939-94df-afb62af13a42",
  "permission": "company.job.write",
  "capability": "JD_GENERATION"
}
```

仅当响应的 `permitted` 为 `true` 时执行该业务动作。普通非 AI 操作的 `capability` 传 `null`；AI 操作必须传对应 capability。实际权限字符串以 BOSS 冻结的权限字典为准，下面是当前接入使用的映射：

| 业务动作 | permission | capability |
| --- | --- | --- |
| 查看企业职位/人才 | `company.job.read` | 无 |
| 新建或修改职位 | `company.job.write` | 无 |
| AI 生成 JD | `company.job.write` | `JD_GENERATION` |
| 解析简历 | `company.candidate.write` | `RESUME_PARSE` |
| AI 筛选简历 | `company.candidate.write` | `RESUME_SCREENING` |

## 7. 计费能力接入（严格顺序）

### 7.1 先检查额度和价格

在启动任务前，使用机器令牌按以下顺序调用：

```http
POST /internal/v1/authorization/quota-check
Authorization: Bearer <machine_access_token>
Content-Type: application/json

{"companyId":"3d635253-d1e0-4939-94df-afb62af13a42","capability":"JD_GENERATION"}
```

只有响应 `authorized=true` 才能继续。接着查询 BOSS 当前生效单价：

```http
POST /internal/v1/billing/quote
Authorization: Bearer <machine_access_token>
Content-Type: application/json

{"companyId":"3d635253-d1e0-4939-94df-afb62af13a42","capability":"JD_GENERATION"}
```

从响应读取 `unit_price_minor`。金额一律为最小货币单位，接入方不得维护替代的本地价格表。

### 7.2 预占后才投递任务

为每个业务执行生成且只生成一次稳定的 `reservationKey`，然后预占：

```http
POST /internal/v1/billing/reservations
Authorization: Bearer <machine_access_token>
Content-Type: application/json

{
  "companyId": "3d635253-d1e0-4939-94df-afb62af13a42",
  "reservationKey": "jd-run:01JXYZ...",
  "estimatedAmountMinor": 120
}
```

仅当响应 `reserved=true` 才写入/投递异步任务。`reserved=false` 或余额不足时立即停止，不得先运行再扣费。网络超时也不得生成新的 key；应以相同 key 重试或转入补偿队列。

### 7.3 完成后回传使用量

任务完成、部分完成、失败或取消后，始终使用同一稳定 `usageEventId` 上报最终结果：

```http
POST /internal/v1/usage-events
Authorization: Bearer <machine_access_token>
Content-Type: application/json

{
  "usageEventId": "recruitment:ai-run:01JXYZ...",
  "executionId": "01JXYZ...",
  "sourceApiClientId": "BOSS 返回的 api_client_id",
  "companyId": "3d635253-d1e0-4939-94df-afb62af13a42",
  "capability": "JD_GENERATION",
  "outcome": "SUCCEEDED",
  "successfulUnits": 1,
  "inputTokens": 860,
  "outputTokens": 540,
  "occurredAt": "2026-09-07T08:20:31Z"
}
```

`occurredAt` 使用 RFC 3339 UTC。收到 `settlement_status=ACCEPTED` 或 `ALREADY_PROCESSED` 都表示该事件已处理。超时、429 或 5xx 时保留相同 `usageEventId` 重试，绝不能创建第二个事件。

未开始或确定未消耗的任务，可释放预占：

```http
POST /internal/v1/billing/reservations/{reservationKey}/release
Authorization: Bearer <machine_access_token>
Content-Type: application/json

{}
```

## 8. BOSS 事件回调

接入方必须提供服务端回调端点。当前 Recruitment 接入端点示例：

```http
POST https://<your-domain>/internal/v1/boss/events
Authorization: Bearer <BOSS_EVENT_BEARER_TOKEN>
Content-Type: application/json
```

事件格式：

```json
{
  "event_id": "d6428de0-b587-44f3-8af6-d07b8c1b5209",
  "event_type": "company.status.changed",
  "aggregate_type": "company",
  "aggregate_id": "3d635253-d1e0-4939-94df-afb62af13a42",
  "payload": {
    "company_id": "3d635253-d1e0-4939-94df-afb62af13a42",
    "tenant_id": "f6e4c909-f6d2-4fc2-b1f7-6639df530304",
    "status": "SUSPENDED"
  }
}
```

当前需处理的事件为 `company.activated`、`company.status.changed`、`tenant.status.changed`。事件采用至少一次投递，可能重复、延迟或乱序。接收方应在同一数据库事务中按以下顺序处理：

1. 验证完整的 Bearer 值；缺失或不匹配返回 401。
2. 校验 `event_id`、`event_type` 与对象形式的 `payload`；不合法返回 400。
3. 将 `event_id` 写入带唯一约束的 inbox 表；已存在时直接返回 2xx。
4. 仅在首次写入成功后，更新本地企业/租户状态投影。
5. 事务提交成功后返回 202 或其他 2xx；临时依赖失败返回 5xx 供 BOSS 重试。

事件用于快速收敛状态，不能替代关键写操作前的同步授权检查。发现企业或租户不是 `ACTIVE` 时，应阻止新建、修改和可计费执行。

## 9. 错误处理、重试与安全

| 情形 | 正确处理 |
| --- | --- |
| 401（机器令牌失效） | 合并刷新机器令牌后仅重试一次；仍失败则失败闭合 |
| 403 或 `permitted=false` | 不重试；提示无权限或联系管理员开通 |
| `authorized=false` | 不启动任务；检查 capability、额度或租户配置 |
| 余额不足 / `reserved=false` | 不启动任务；引导充值或调整额度 |
| 429、5xx、网络超时 | 对幂等请求指数退避，始终复用原 reservationKey / usageEventId |
| 事件处理失败 | 返回 5xx，并保留失败原因供重放与对账 |

API 错误体按 `{ "code", "message", "request_id" }` 处理。请记录 `request_id`、`event_id`、`company_id`、`tenant_id`、`execution_id` 和 `usage_event_id` 进行排障；绝不记录 access token、client secret、回调密钥、验证码或个人敏感原文。

## 10. 状态恢复与日常对账

当回调失败、部署迁移或状态不一致时，接入方可使用机器令牌读取指定企业快照：

```http
GET /internal/v1/companies/{companyId}/context
Authorization: Bearer <machine_access_token>
```

建议每天至少核对 BOSS 活跃租户/企业、接入方本地企业投影、未处理 inbox、BOSS PENDING/FAILED outbox，以及接入方未结算 usage outbox。发现差异时，先暂停该企业的可计费操作，按 BOSS 最终状态修复投影后再恢复。

## 11. 沙箱验收清单

在生产上线前，逐项完成以下用例并留存 `request_id`、事件 ID 和截图/日志证据：

1. 验证码登录、密码登录、刷新、登出、首次设置密码和忘记密码流程均可用。
2. 无效用户 token、无企业上下文、停用 company 或停用 tenant 均不能访问受保护业务。
3. 两家公司之间的数据与权限隔离；同公司成员权限不足时写操作被拒绝。
4. 未开通 capability、额度不足、无生效价格、预占失败时均不启动任务。
5. 成功、失败、取消和超时重试均只使用一个 reservationKey 与一个 usageEventId，不重复计费。
6. 同一个事件投递两次只生效一次；乱序或失败重试不把停用企业重新放行。
7. 浏览器包、前端源码、日志和错误页均不包含 BOSS 服务端密钥或机器令牌。

## 12. 上线前最后确认

在切换生产流量前，请与 BOSS 团队确认已冻结的 OpenAPI 版本、Client ID 所属 Contract、tenant scope、权限字典、capability、价格、额度、回调 URL 和重放方案。接口字段、权限字符串或状态机存在歧义时，应先冻结契约，再实施；不要根据猜测兼容。
