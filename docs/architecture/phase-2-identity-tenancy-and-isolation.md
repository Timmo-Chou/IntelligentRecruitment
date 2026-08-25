# Phase 2 注册登录、授权、租户权限与数据隔离方案

版本：V1.0  
状态：MVP 开发基线  
适用对象：企业、猎头公司、个人 HR、SOHO 猎头

## 1. 结论

账号不永久区分“个人账号”和“企业账号”。用户使用同一个手机号账号登录，首次进入时选择使用方式：

1. 创建个人工作空间。
2. 认证企业并创建企业工作空间。
3. 接受邀请或申请加入已有企业/工作空间。

平台注册完全开放手机号入口，不要求平台邀请码或注册邀请码。文中的“邀请”仅指已存在企业或工作空间发出的成员邀请链接。

系统采用“统一用户 + 企业根租户/个人空间 + 业务子租户”模型：

```text
User 用户身份
├── PersonalIdentity 个人实名身份
│   └── Personal Workspace 个人工作空间
└── Company 企业根租户
    └── Workspace 企业业务子租户
        ├── WorkspaceMembership
        ├── 招聘业务数据
        └── BillingAccount / Ledger
```

- `Company` 表示企业法人或经营主体，是企业场景的根租户，负责认证、成员归属和企业级治理。
- `Workspace` 表示部门、行业团队、项目团队或个人招聘空间，是业务子租户，也是权限、数据和日常账本的主要隔离边界。
- 个人场景没有 Company，个人 Workspace 的 `company_id` 为空。
- MVP 不在 Workspace 下增加 `Department`、`OrganizationUnit` 或部门树。需要按部门、业务线或猎头行业组独立管理时，直接创建 Workspace。
- 同一用户可拥有个人空间，也可加入多个企业及多个工作空间。
- 同一企业可以有多个相互不可见的工作空间。
- Company Owner/Admin 默认不能查看其未加入工作空间的职位、人才、简历和任务正文。

## 2. MVP 范围

### 2.1 本期实现

- 手机号验证码登录、Access Token、Refresh Session。
- 首次进入选择个人使用、企业认证或接受邀请。
- 个人实名状态及30元试用金发放基础。
- 企业认证审核、企业去重和认领。
- 企业与工作空间创建。
- 企业和工作空间成员邀请/申请。
- Company 与 Workspace 两级角色和服务端授权。
- 工作空间级数据隔离。
- 工作空间账本、30/100元试用额度、90天到期。
- 平台审核与人工额度调整的受保护管理 API。
- 审计日志。

### 2.2 本期不实现，但预留

- 个人拉新20元奖励。
- 奖励提现或现金佣金。
- 在线充值、发票和退款。
- Company 共享试用额度池与动态分配。
- 企业共享人才库/职位库和跨空间数据授权。
- Workspace 下的 Department/OrganizationUnit 层级、复杂集团部门树和上下级权限继承。
- 企业 SSO、企微、钉钉和微信登录。
- 平台运营管理后台；Phase 2 先使用受保护管理 API。

## 3. 注册与登录交互

### 3.1 统一登录入口

```text
输入手机号
→ 获取验证码
→ 勾选用户协议和隐私政策
→ 验证成功
→ 已有用户进入最近使用的工作空间
→ 新用户进入“选择使用方式”
```

认证规则：

- Access Token：30分钟，客户端内存保存。
- Refresh Token：14天，`HttpOnly` Cookie 保存并每次刷新轮换。
- 支持多设备、当前设备退出、全部设备退出。
- 所有验证码发送、校验和刷新操作都需要限流、防重放和审计。

### 3.2 选择使用方式

页面提供三个入口：

- `个人使用`：个人 HR 或 SOHO 猎头。
- `认证企业`：企业或猎头公司负责人。
- `加入团队`：接受邀请或申请加入已有企业。

选择仅决定本次 onboarding，不改变用户账号类型，后续可从设置中增加其他身份关系。

### 3.3 个人使用流程

```text
选择个人使用
→ 创建个人工作空间
→ 进入产品（未实名、无试用金）
→ 提交个人实名认证
→ 审核通过
→ 幂等发放30元试用额度
```

规则：

- 一个实名自然人只能获得一次个人试用金。
- 试用金为不可提现的产品额度，自发放起90天过期。
- MVP 每个用户最多创建一个个人 Workspace；加入企业或企业 Workspace 不受此限制。
- 一个个人工作空间只有一个 `WORKSPACE_OWNER`，Owner 可转让。
- 可存在多个 `WORKSPACE_ADMIN` 和 `RECRUITER`。
- 个人可邀请成员加入其空间，但不会因此自动获得奖励。

限制为一个个人 Workspace 是 MVP 产品约束，不是数据模型限制：它用于保持个人用户入口、试用额度归属和数据导航简单，并避免个人用户用多个空间模拟多个企业或反复制造隔离单元。个人的多个职位、客户或项目先在同一空间内用职位和标签区分；未来如确有多客户隔离需求，可以开放多个个人 Workspace，但不会重复发放个人试用金。

### 3.4 企业认证与创建流程

新用户和已有个人用户使用同一流程：

```text
填写企业名称、统一社会信用代码
→ 上传营业执照
→ 填写申请人和联系方式
→ 系统按信用代码查重
→ 提交平台审核
→ 审核通过
→ 创建或认领Company
→ 申请人成为COMPANY_OWNER
→ 创建首个Workspace
→ 同时成为WORKSPACE_OWNER
→ 幂等发放100元企业试用额度到首个Workspace
```

规则：

- 统一社会信用代码是企业主体唯一标识，公司名称只用于展示和辅助匹配。
- 同一企业主体只能领取一次企业试用金。
- 企业试用金自发放起90天过期。
- 企业已存在时，不得重复创建；进入加入申请或企业认领流程。
- 企业审核未通过前不能成为 Company Owner，也不发放试用金。
- 猎头公司与普通企业使用相同 Company 模型，行业团队通过 Workspace 隔离。

### 3.5 路径 B：部门独立接入

企业没有统一管理员时：

```text
销售/平台核验部门申请
→ 找到或创建Company（PLATFORM_MANAGED）
→ 创建独立Workspace
→ 部门负责人成为WORKSPACE_OWNER
```

- 部门负责人不是 Company Owner。
- Company 可以暂时没有用户侧 Owner，由平台代管企业元数据。
- 后续只有通过企业级授权审核的人才能认领 Company Owner。
- 平台管理员只管理归属、认证和审计元数据，不获得招聘业务数据查看权。

### 3.6 邀请和加入

企业邀请：

```text
Company Owner/Admin邀请手机号
→ 用户登录或注册
→ 接受邀请
→ 建立CompanyMembership
```

工作空间邀请：

```text
Workspace Owner/Admin邀请手机号
→ 用户接受
→ 校验其企业成员关系
→ 建立WorkspaceMembership
```

用户申请：

```text
搜索/输入企业信息
→ 提交加入申请
→ 平台管理员核验申请人与企业关系并审批
→ 加入企业
→ 再由工作空间Owner/Admin邀请进入Workspace
```

邀请必须包含手机号、目标、角色、有效期、状态和创建人；可撤销、过期且只能按限定次数使用。

企业 Owner/Admin 发出的企业成员邀请由被邀请人接受后生效，不再重复经过平台审批；用户未受邀而主动申请加入企业时，才进入平台审批。MVP 不开放 Workspace 自助申请入口。

## 4. 租户和组织设计

### 4.1 租户边界

`Workspace` 是业务租户。所有招聘业务表必须带 `workspace_id`：

- 职位和JD版本。
- 候选人、简历文件和解析版本。
- 招聘任务、会话和消息。
- 筛选方案、运行和结果。
- 面试题包。
- 文件资产、AI运行、费用记录和业务审计。

Workspace 名称和类型直接表达“技术部”“商业化招聘部”“金融猎头组”等管理单元；职位可以保留岗位自身的部门名称文本，但不关联独立部门实体或部门权限。

企业 Workspace 的业务数据必须同时保存 `company_id + workspace_id`；个人 Workspace 的业务数据 `company_id` 为空。服务端必须校验 `workspace.company_id` 与业务数据的 `company_id` 一致，且 `company_id` 不能替代 `workspace_id` 鉴权。

### 4.2 工作空间创建策略

MVP 固定为 `OWNER_ADMIN_ONLY`：只有 Company Owner/Admin 可以创建企业 Workspace，普通企业成员、Workspace 成员均不能创建或申请创建。

创建时必须指定唯一的 `WORKSPACE_OWNER`。创建人只有在被指定为 Owner/Admin/成员时才获得该 Workspace 的招聘数据权限；“创建动作”本身不产生跨 Workspace 查看权。

### 4.3 角色

平台角色：

- `PLATFORM_ADMIN`：企业审核、归属处理和额度人工调整。
- `PLATFORM_OPERATOR`：执行受限审核操作，不查看招聘内容。

企业角色：

- `COMPANY_OWNER`：唯一，可转让。
- `COMPANY_ADMIN`：可多个。
- `COMPANY_MEMBER`：企业普通成员。

工作空间角色：

- `WORKSPACE_OWNER`：唯一，可转让。
- `WORKSPACE_ADMIN`：可多个。
- `RECRUITER`：执行招聘业务。

## 5. 权限矩阵

| 能力 | Company Owner | Company Admin | Workspace Owner | Workspace Admin | Recruiter |
|---|---:|---:|---:|---:|---:|
| 查看企业基本信息 | 是 | 是 | 是 | 是 | 是 |
| 修改企业基本信息 | 是 | 是 | 否 | 否 | 否 |
| 邀请企业成员 | 是 | 是 | 否 | 否 | 否 |
| 创建企业Workspace | 是 | 是 | 否 | 否 | 否 |
| 查看Workspace治理元数据 | 是 | 是 | 本空间 | 本空间 | 本空间 |
| 查看Workspace招聘内容 | 仅同时是空间成员 | 仅同时是空间成员 | 是 | 是 | 是 |
| 邀请Workspace成员 | 否 | 否 | 是 | 是 | 否 |
| 修改Workspace角色 | 否 | 否 | 是 | 是，但不能修改Owner | 否 |
| 创建职位/招聘任务 | 否 | 否 | 是 | 是 | 是 |
| 查看Workspace余额 | 否 | 否 | 是 | 是 | 是 |
| 查看完整账本 | 否 | 否 | 是 | 是 | 否 |
| 人工调整额度 | 仅平台角色 | 仅平台角色 | 否 | 否 | 否 |

授权原则：

- 前端隐藏按钮只是体验，服务端必须逐请求校验。
- 每次业务请求从认证上下文取得 `user_id`，从明确的 Workspace 上下文取得 `workspace_id`。
- 不允许客户端提交角色或任意 company/workspace scope 后直接信任。
- Company 角色不会自动继承招聘数据读取权限。
- Workspace 招聘数据权限只取决于有效的 WorkspaceMembership，不取决于 Company 角色或是否执行过创建操作。

## 6. 数据隔离与共享

### 6.1 默认隔离

- 个人空间、企业工作空间之间完全隔离。
- 同一 Company 下不同 Workspace 之间也完全隔离。
- Company Owner/Admin 只能看到空间名称、Owner、成员数、状态和费用汇总等治理元数据。
- 未加入空间时不能读取职位、人才、简历、任务、对话和面试结果。
- 对象存储 Key、缓存 Key、消息和搜索索引均必须携带 Workspace scope。

### 6.2 人才库和职位库

MVP 默认都归 Workspace，不在注册时让普通用户选择共享策略。

- 企业共享库默认关闭。
- Phase 2 不复制数据到 Company 级表，也不实现跨空间访问。
- 数据模型预留 `data_sharing_grants`，未来由已认证 Company Owner/Admin 开启共享能力。
- 即使未来开启，也必须逐项授权；历史候选人不能自动共享。
- 原简历、联系方式、结构化画像和评价应支持不同共享范围。

### 6.3 查询防越权

- Repository 查询必须同时包含资源 ID 和 `workspace_id`。
- 唯一约束应包含 `workspace_id`。
- 跨 Workspace 访问统一返回安全的 `404` 或权限错误，避免资源枚举。
- 文件下载先完成服务端授权，再签发短时 URL。
- 跨空间管理查询使用专门的企业治理投影，不能直接查询业务明细表。

## 7. 额度和账本边界

### 7.1 MVP 规则

- 个人实名试用：30元，即3,000分，发放至该用户唯一的个人工作空间。
- 企业认证试用：100元，即10,000分，发放至企业首个工作空间。
- 均为一次性、不可提现，自发放起90天过期。
- Company 共享额度池暂不实现，避免部门抢占和复杂并发分配。
- 新增企业 Workspace 不自动获得试用金；平台可通过审计账本向指定 Workspace 人工赠送。
- 在线充值暂不实现。

### 7.2 未来充值预留

充值不永久绑定用户身份，而归属于 `BillingAccount`。未来支持：

- Workspace 独立账户。
- Company 统一账户及 Workspace 分配。
- 用户付款但授权一个或多个 Workspace 使用的账户映射。

用户离职、空间转让或账号停用时，资金权益仍按 BillingAccount 归属处理。

### 7.3 账本要求

- 金额使用整数“分”，不使用浮点数。
- 发放、预占、结算、释放、过期、调整和冲正均为不可变流水。
- 每笔流水包含 `workspace_id`、账户、额度批次、业务引用、幂等键和操作者。
- 余额是账本投影，必须可对账和重建。

## 8. 数据模型

### 8.1 身份与认证

```text
users
- id, phone_ciphertext, phone_hash, nickname, status

personal_identities
- user_id, real_name_ciphertext, identity_hash, verification_status

verification_challenges
- id, phone_hash, purpose, code_hash, expires_at, attempt_count, consumed_at

refresh_sessions
- id, user_id, token_hash, device_info, expires_at, revoked_at, rotated_from_id
```

### 8.2 企业、工作空间与成员

```text
companies
- id, legal_name, display_name, credit_code_ciphertext, credit_code_hash
- verification_status, management_status

company_verification_requests
- id, applicant_user_id, company_id, submitted_data, license_asset_id
- status, reviewed_by, reviewed_at, rejection_reason

company_memberships
- id, company_id, user_id, role, status, joined_at

workspaces
- id, company_id nullable, type PERSONAL|COMPANY
- name, owner_user_id, status

workspace_memberships
- id, workspace_id, user_id, role, status, joined_at

membership_invitations
- id, target_type COMPANY|WORKSPACE, target_id, phone_hash
- role, token_hash, expires_at, status, created_by

membership_applications
- id, target_type=COMPANY, target_id, applicant_user_id, status
- reviewed_by_platform_user_id, reviewed_at, review_reason
```

关键约束：

- `users.phone_hash` 唯一。
- `companies.credit_code_hash` 唯一。
- Company 和 Workspace 分别只能有一个有效 Owner。
- 每个用户最多拥有一个 `type=PERSONAL` 的 Workspace。
- Membership 唯一键为目标 ID + User ID。
- 企业招聘数据的 `company_id + workspace_id` 必须与 Workspace 归属一致；个人数据的 `company_id` 必须为空。
- 角色变更、Owner 转让和成员移除必须审计。

### 8.3 额度与账本

```text
billing_accounts
- id, workspace_id, currency, status

credit_lots
- id, billing_account_id, source_type, original_amount_minor
- available_amount_minor, issued_at, expires_at, status

billing_ledger_entries
- id, billing_account_id, workspace_id, credit_lot_id
- entry_type, amount_minor, business_reference, idempotency_key
- operator_user_id, reason, created_at

trial_eligibilities
- id, subject_type PERSONAL_IDENTITY|COMPANY
- subject_id, policy_code, granted_at, workspace_id
```

关键约束：个人实名主体和认证企业主体对同一试用政策只能有一条成功资格记录。

### 8.4 招聘业务表改造

原先所有 `organization_id` 应明确迁移为或映射到 `workspace_id`。Company 级汇总使用独立投影，不允许模糊使用 organization 同时表达企业和租户。

## 9. 服务端模块

```text
identity       登录、验证码、Session、个人实名
company        企业认证、认领、Company成员、加入申请
workspace      Workspace、成员、邀请、租户上下文
authorization  角色权限和方法级校验
billing        试用资格、额度批次、账本和余额投影
audit          审核、角色、PII和资金操作审计
```

API 示例：

```text
POST /api/v1/auth/challenges
POST /api/v1/auth/verify
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
POST /api/v1/auth/logout-all
GET  /api/v1/me
POST /api/v1/personal-verifications
POST /api/v1/company-verifications
POST /api/v1/companies/{id}/claim-requests
POST /api/v1/companies/{id}/membership-applications
POST /api/v1/companies/{id}/invitations
POST /api/v1/platform/company-membership-applications/{id}/approve
POST /api/v1/platform/company-membership-applications/{id}/reject
POST /api/v1/workspaces
POST /api/v1/workspaces/{id}/invitations
GET  /api/v1/workspaces/{id}/members
GET  /api/v1/workspaces/{id}/balance
GET  /api/v1/workspaces/{id}/ledger
```

## 10. 前端交互和路由

```text
/login                         手机验证码登录
/onboarding                    选择个人/认证企业/加入团队
/onboarding/personal           创建个人空间
/onboarding/personal/verify    个人实名认证
/onboarding/company            企业认证申请
/onboarding/join               接受邀请或申请加入
/company/settings              企业治理设置
/workspace/settings            工作空间设置与成员
/billing                       余额、额度批次和账本
```

关键状态：审核中、审核拒绝、邀请失效、已属于目标、Owner不可退出、无空间权限、Session过期、试用金已领取、试用金即将到期。

## 11. 安全与审计

- 手机号、身份证明、信用代码和营业执照均按敏感数据管理。
- 可检索标识使用哈希，原值加密保存，日志禁止输出。
- 企业认证文件使用私有对象存储和短时授权 URL。
- 平台人员访问认证材料、审核、额度调整、Owner转让均写审计日志。
- Refresh Token 只存哈希，支持轮换、重放检测和设备级吊销。
- Redis 实现发送频率、错误次数和接口速率限制；数据库唯一约束作为最终防重复保障。

## 12. 验收标准

- 同一个手机号不能重复创建用户。
- 同一个实名自然人不能重复领取个人试用金。
- 同一信用代码不能创建两个 Company，也不能重复领取企业试用金。
- 用户可同时拥有个人空间和多个企业工作空间关系。
- 未加入 Workspace 的用户不能通过 ID、列表、搜索、文件 URL 或异步任务访问其数据。
- Company Owner/Admin 未加入 Workspace 时不能查看招聘内容。
- 重复验证码、审核回调、邀请接受和试用发放不会产生重复数据或重复额度。
- Owner 不能直接退出，必须先完成转让。
- 余额能够由账本重建，过期额度不会参与新任务预占。
- 登出当前设备和全部设备后，对应 Refresh Session 立即失效。

## 13. Phase 2 方案审计

### 13.1 已明确、不再待确认

- 不使用平台注册邀请码；用户可通过手机号验证码注册。
- 企业/Workspace 成员邀请链接保留，它不是注册门槛。
- 账号不固化个人/企业类型，同一用户可同时拥有个人空间和企业成员关系。
- Workspace 直接代表部门、业务线或猎头行业组，不增加 Department 层级。
- 不设置 `UNIFIED/ISOLATED` 企业治理模式；所有企业始终按 WorkspaceMembership 隔离招聘数据。
- Workspace 是招聘数据隔离键；企业空间的数据同时需要企业归属。
- 人才库、职位库默认归 Workspace，MVP 不实现跨空间共享。
- 个人实名试用30元、认证企业试用100元，均一次性、90天过期。
- 企业100元试用金发放到首个 Workspace；MVP 不实现 Company 共享额度池。
- 个人拉新奖励、在线充值和企业共享库不进入 Phase 2。

### 13.2 本轮已冻结

- Company Owner/Admin 只拥有企业治理权限；未加入某 Workspace 时不能查看其招聘业务数据。
- 不提供 `UNIFIED/ISOLATED` 模式，也不提供治理模式切换入口。未来如引入新的治理能力，只能由平台审核迁移。
- 每个用户最多创建一个个人 Workspace。
- 只有 Company Owner/Admin 能创建企业 Workspace，普通成员不能创建或申请创建。
- 未受邀用户主动申请加入 Company 时由平台审批；企业认证或认领通过后才能成为 Company Owner。
- 企业业务数据强制保存一致的 `company_id + workspace_id`；个人数据 `company_id` 为空。
- 企业100元试用金发放到首个 Workspace。

### 13.3 非阻塞待确认

- 企业和个人认证材料保留期限：Phase 2 暂不冻结具体期限，但上线前必须取得法务/隐私结论。
- 实名和企业认证供应商：Phase 2 先使用平台人工审核并保留适配接口，后续再选型。
