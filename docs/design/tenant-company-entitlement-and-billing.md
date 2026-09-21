# Tenant、Company 权益与计费详细设计

## 1. 目标、范围与已确认规则

本设计将 BOSS 建设为身份、合同、价格、资产、预占、结算和审计的唯一权威；IntelligentRecruitment 与新建的 OpenAPIPlatform 只作为用户界面与 BFF，不保存可写的钱包、权益或计费真相。

目标是同时支持三种业务：

1. Recruitment SaaS / OpenAPIPlatform 的 Tenant 自主购买套餐或充值；
2. 外部招聘平台作为 Tenant，向其入驻 Company 销售套餐并将最终权益订单快照同步给 BOSS；
3. Personal Tenant 自用套餐和充值。

本期包含：企业试用金、两类金额余额、套餐权益、Tenant 向 Company 分配、按量计费、预占和结算、支付宝及线下支付、套餐到期前 7 天站内通知、运营端配置价格和套餐、OpenAPIPlatform 的 Tenant Owner 与 App/Key 管理。

本期不包含：自动续费自动扣款、Tenant 共享余额池、外部开发者/技术服务商独立门户和角色体系、历史数据迁移、Company 直接充值前台入口、自动计算套餐升级差价。

### 1.1 不可变业务规则

- 外部招聘平台/业务客户是 **Tenant**；其平台内企业是 **Company**。一个 Tenant 可以有多个 Company。
- 外部平台由其自身向 Company 收款；BOSS 不直接向该 Company 收款，也不计算外部平台的补差价。
- 不设 Tenant 共享池回退。Company 的任何调用只能消耗其自身已获分配的权益或余额；不足时在业务执行前拒绝。
- 货币余额只有 `GIFT`（赠送余额）和 `RECHARGE`（充值余额）两类。人工补偿、人工扣减不是第三种余额，而是对当时实际使用余额类型的、可审计的批次调整。
- 套餐只承载能力单位：次数、份数和 Token；不把套餐权益折算为可提现或可转移的现金余额。
- Company 的扣减顺序固定为：`套餐权益 -> GIFT -> RECHARGE -> 拒绝`。同一类资产按最早到期优先。
- 外部平台订单、套餐版本、权益快照和来源订单号必须保存；不能按后续套餐配置重新推导历史权益。
- 金额、单价和账单内部均以微元（micro-CNY）保存：`1 CNY = 1,000,000 micro-CNY`。支付、退款和发票账期汇总时再按分四舍五入。
- 所有资产变动、预占、释放、结算、订单状态改变必须幂等、可审计、不可原地改写历史。

## 2. 主体、资产与归属模型

### 2.1 主体

| 主体 | 含义 | 可持有资产 | 谁付款/谁负责 |
|---|---|---|---|
| Enterprise Tenant | 自营企业、招聘平台或业务客户 | 套餐权益、GIFT、RECHARGE | 自营渠道由 Tenant 付款；外部平台作为 Tenant 向我方付款 |
| Personal Tenant | 完成实名认证的个人 | 套餐权益、GIFT、RECHARGE | 个人付款；资产不得转至企业或 Company |
| Company | Tenant 下的实际使用企业 | 被 Tenant 分配的权益/余额；预留直接充值资产 | 外部平台模式不直接向我方付款；未来直接充值时由 Company 自行承担付款、发票和退款 |

资产账户以 `owner_type + owner_id` 唯一确定；每笔资产还记录 `tenant_id`、可选 `company_id`、来源与原始批次。Company 资产必须带其父 Tenant，确保跨 Tenant 不能使用或转移。

### 2.2 两本资产账

**金额账**只管理人民币微元：

- `GIFT`：企业初始试用金、Tenant 分配的试用/赠送余额以及与原余额类型一致的补偿。
- `RECHARGE`：支付宝或线下确认的充值余额、Tenant 分配的充值余额以及与原余额类型一致的补偿。
- 每一笔可用金额是一个不可变 `money_lot`；可预占金额与已预占金额均落在具体 lot 上。余额展示是 lot 聚合结果，不是可写总额。

**能力权益账**管理能力单位，不是金额：

- `JD_GENERATION`：次数；`RESUME_PARSE`：份数；`RESUME_SCREENING`：份数；`CHAT_TOKEN`：Token。
- 每一笔套餐购买、试用权益（若以后配置）或 Tenant 分配，产生独立 `entitlement_lot`。
- 套餐消费为零现金扣款，但用量记录必须保留当时合同价、理论金额，以支持成本、利润和价格追溯。

### 2.3 可转移性矩阵

| 来源资产 | 可分配/转移 | 约束 |
|---|---|---|
| Tenant GIFT / RECHARGE | Tenant -> 自己的 Company | 类型不变，保留原到期日；不得跨 Tenant |
| Tenant 套餐权益 | Tenant -> 自己的 Company | 同能力、同单位，不能超过未预占且未过期数量，保留原到期日 |
| Company 获分配资产 | 仅 Company 注销时回收至原 Tenant | 仅未使用、未预占、未过期部分；回收原类型与原到期日 |
| Company 直接充值 | 不可转回 Tenant 或其他 Company | 首期只保留后端兼容能力，不展示入口 |
| Personal Tenant 任意资产 | 不可转给企业 Tenant 或 Company | 不提供例外 |

## 3. 试用金与资格去重

### 3.1 发放规则

| 对象 | 触发条件 | 发放 | 失效 | 去重键 |
|---|---|---:|---|---|
| Enterprise Tenant | 首次注册 Tenant，平台审核通过 | 100 CNY GIFT | 发放时刻 + 90 天 | 已审核付款法人/统一社会信用代码 |
| Personal Tenant | 本期不发放试用金 | — | — | — |

Recruitment SaaS 与 OpenAPIPlatform 的注册均调用同一 BOSS 审核完成事件；事件重复投递不能重复发放。企业名称仅做展示和辅助风控，资格唯一性以规范化后的统一社会信用代码为准。企业法人变更、名称变更或 Tenant UUID 改变均不得重新获得试用金。Personal Tenant 本期不接入实名认证试用金流程。

### 3.2 实现

创建 `trial_grants`：`policy_code`、`subject_kind`、`verified_subject_hash`、`tenant_id`、`money_lot_id`、`granted_at`、`expires_at`，并加唯一键 `(policy_code, subject_kind, verified_subject_hash)`。审核通过事务中先锁定/插入资格记录，成功后创建 GIFT lot 与双分录账本；冲突即返回已发放状态而非报错。审核拒绝、注销、重复回调均不得发放。

## 4. 套餐、购买、升级与有效期

### 4.1 套餐定义

运营端配置 `package_catalog` 与版本：

- `package_family`：同一产品线；
- `subscription_group`：允许续接/升级比较的套餐组；
- `tier_rank`：正整数，越大代表等级越高；
- `billing_period`：`MONTH` 或 `YEAR`；
- `duration_count`：首期均为 1；
- `sale_price_micro`：自主购买时的原价；
- `benefits`：能力 code、单位、数量；
- `status`：`DRAFT/ACTIVE/RETIRED`。

套餐版本一旦有订单引用不可修改；运营调整必须创建新版本。每张订单保存完整套餐版本快照、权益快照、价格快照和税务展示信息。

### 4.2 自主购买规则

适用于 Recruitment SaaS、OpenAPIPlatform 的 Enterprise Tenant 与 Personal Tenant（Personal 本期不接入实名认证试用金流程）：

1. 存在未过期套餐时，**同一 `subscription_group` 不得购买同一等级或更低等级**，只能购买 `tier_rank` 更高的套餐；接口在创建待支付订单时校验，支付回调时再次校验。
2. 当前组所有套餐均已过期时，可购买任意等级。
3. 购买按套餐原价付款；不计算旧套餐剩余价值或升级补差价；不支持退款。
4. 支付成功（支付宝回调验签）或运营确认线下收款后才激活权益。未支付、超时、支付失败和取消订单均不发权益。
5. 新套餐权益购买后立即可用；原批次仍按原到期日存在且先到期先消耗。
6. 新套餐批次的到期日为：`同一 owner + subscription_group 当前未过期套餐批次的最晚 expires_at + 新套餐周期`。若不存在有效批次，则为支付确认时刻加周期。`MONTH` 用日历月加法，`YEAR` 用日历年加法，统一存 UTC、按 Asia/Shanghai 展示。
7. 一个套餐批次的“可用开始”固定为激活时刻；它不需要等待其顺延后的到期日才可用。该规则是“即买即用、有效期顺延”的精确定义。

例如：基础月包在 5 月 10 日到期，5 月 1 日购买更高档月包；新权益 5 月 1 日可用，新批次到期为 6 月 10 日；基础包剩余权益依旧在 5 月 10 日失效。消费会优先消耗 5 月 10 日到期的基础权益。

为避免无限提前锁价，`subscription_group` 必须配置 `max_future_end_months`，首期建议为 24；创建订单与支付确认均校验新到期日不得超过 `now + 上限`。运营可以配置更严格值，不允许无上限。

### 4.3 外部平台 Company 套餐订单

外部平台调用 BOSS 的服务端接口提交：外部平台订单号、Company、外部套餐版本标识、权益快照、下单/生效时间和幂等键。

- BOSS 不以外部售价计算差价，也不创建对外支付订单。
- BOSS 首先校验 Company 属于该 API Client 绑定的 Tenant；再对 Tenant 的对应权益/金额进行全量预占。不能覆盖时返回 `TENANT_ENTITLEMENT_INSUFFICIENT` 或 `TENANT_BALANCE_INSUFFICIENT`，且不产生部分分配、不进入业务处理层。
- 成功后原子创建 Company 权益/金额 lot、Tenant 到 Company 的 allocation、外部订单快照及账本。金额分配严格保持 `GIFT -> GIFT`、`RECHARGE -> RECHARGE`。
- 重放同一外部订单号且负载相同返回原结果；同键不同内容返回冲突。外部订单取消/退款不自动回滚；若未来要支持，必须另行定义已使用权益和资金的结算规则。

外部订单的套餐内容以快照为准，可以没有我方 `package_catalog` 的对应版本；但 capability、单位、数量必须被 BOSS 白名单校验。

### 4.4 线下合同 Tenant 的套餐履约

“企业线下付款购买”与“外部招聘平台已签线下合同、运营为 Tenant 配置我方套餐”是两个流程，不能混用。

- 前者仍创建 `PENDING_OFFLINE_CONFIRMATION` 的支付订单，运营确认到账后激活套餐或充值。
- 后者不创建支付单、也不参与本期自动续费扣款。运营在合同生效范围内创建 `OPERATOR_PROVISIONED` 套餐履约订单，必须填写合同编号、套餐版本/权益快照、有效期、配置理由和操作人；审核后的 Tenant 获得可再分配给 Company 的权益。
- 该履约订单同样不可原地修改；续期、增配和更正均创建新的履约/调整记录。到期提醒仍发送 Tenant Owner。

因此，只有 Recruitment SaaS / OpenAPIPlatform 的自主购买用户可以使用支付宝或企业线下支付入口；外部平台合同 Tenant 由运营配置，不在 BOSS 中自动扣款或续费。

## 5. 钱包、套餐权益的预占与结算

### 5.1 调用前决策

每次业务请求由 IntelligentRecruitment 或外部 API 在执行前向 BOSS 请求 `reserve`，请求至少携带：`tenant_id`（由认证上下文推导，不信任客户端）、`company_id`（个人自用可为空/映射个人 owner）、`capability`、`reservation_key`、单位预估、价格上下文、执行超时时间。

服务端在一个事务中加锁候选 lot，按下列顺序创建 `asset_reservation_lines`：

```text
有效 Company entitlement_lot（能力匹配，expires_at 升序）
  -> 有效 Company GIFT money_lot（expires_at 升序）
  -> 有效 Company RECHARGE money_lot（expires_at 升序）
  -> INSUFFICIENT_ASSET，拒绝执行业务
```

如果套餐权益可以覆盖，现金预估为零；如果权益不足，剩余部分按当前有效价格规则预占现金。预占可以跨多个 lot，但必须在单一数据库事务内完成。不得在 Company 不足时读取或扣减 Tenant 余额。

`expires_at IS NULL` 的 RECHARGE lot 在同类排序中永远排在有到期日 lot 之后。若套餐仍在有效期但其可用权益已耗尽、且 Company 两类金额余额也不足，BOSS 返回 `COMPANY_ASSET_INSUFFICIENT` 及 `recharge_recommended=true`；Recruitment SaaS / OpenAPIPlatform 在调用被拒绝处展示“套餐权益已用尽，请充值”的明确提示。外部平台则只收到机器可读的余额/权益不足错误，不触发我方向其 Company 的直接收费界面。

### 5.2 Token 的预估与结算

前端输入字符数只能用于展示估算，不能作为扣费依据。服务端在调用模型前使用目标模型 tokenizer 对系统提示词、用户输入、历史消息、RAG 上下文和 `max_output_tokens` 计算上界；再进行套餐 Token 或金额预占。模型返回后用实际输入/输出 Token 结算，多占部分释放。模型失败、取消或超时则完整释放。

### 5.3 结算、补偿与扣减

- `capture` 以实际成功单位/实际 Token 为准；对预占的权益或金额逐行结算，未使用预占释放。
- 实际额大于预估额时，可在同一既定顺序补预占；仍不足则将执行标记为“已交付待补偿”，绝不静默形成负余额。系统自动创建运营异常工单；运营依据实际扣款类型发放补偿或冲正。
- 人工补偿/调整必须引用 `original_reservation_id`、`original_lot_id` 或明确的目标余额类型；目标只能为 GIFT 或 RECHARGE。人工扣减同理，且不得使可用余额为负。
- 对原调用的技术性补偿，系统默认继承原 lot 的余额类型和到期日；若原批次已经到期，运营必须在调整单中明确新的有效期及理由，不能生成已经失效的补偿。RECHARGE 的常规补偿默认无到期日；GIFT 补偿必须记录有效期。
- 金额 lot 过期或权益 lot 过期时，不再可预占；已有预占允许完成或超时释放，不能因到期直接丢失预占。到期任务只更新 lot 状态并生成审计事件，绝不物理删除。

### 5.4 价格

价格计划按 Tenant 生效：平台默认价格计划为兜底，合同/运营指定的 Tenant 价格计划优先。每条规则包含 capability、计价单位、生效时间和 `unit_price_micro`。每次预占和结算持久化规则 ID、版本、单价、单位、理论金额；之后调价不影响历史。

IntelligentRecruitment 运营管理端是价格与套餐配置界面，调用 BOSS 平台管理员 API；浏览器不持有平台管理员密钥，BFF 传递已认证运营人员身份用于审计。前期发布无需双人复核，但状态机仍采用 `DRAFT -> ACTIVE -> RETIRED`，防止原地改历史价格。

## 6. Tenant 分配和 Company 注销回收

### 6.1 分配

Tenant Owner 可手动分配；外部平台订单可自动分配。两种方式复用同一个 `allocation` 服务，输入必须声明资产种类、金额/能力与 Company。服务锁定 Tenant 来源 lot，校验可用、未过期、未预占余额后，创建 Company 目标 lot 和一对关联分录。

分配不改变资金类型、能力、来源可追溯性和到期日。试用金可分配给多个 Company，所有拆出的目标 lot 继承原试用 lot 的到期日。不得提供常规“收回”入口。

### 6.2 Company 注销

只有 Company 已完成注销，才执行一次回收流程。回收仅限 Tenant 分配来源的、未过期、未使用、未预占部分；直接充值来源排除。回收产物回到原 Tenant 的原类型/能力及原到期日。若有预占，注销应先阻止新调用、等待预占结算/释放；超过预占 SLA 由超时任务释放后再回收。回收事件、剩余不可回收原因与 lot 映射均须可查询。

## 7. 支付、退款与对账

### 7.1 订单状态

套餐订单和充值订单统一采用：`CREATED -> PENDING_PAYMENT -> PAID -> FULFILLED`；线下订单增加 `PENDING_OFFLINE_CONFIRMATION`。`CANCELLED/EXPIRED/PAYMENT_FAILED` 为终态；回调或人工确认须带幂等键。

- 支付宝：创建支付单，回调必须验签、校验商户订单号/金额/币种/交易状态；只有首次合法成功回调可进入 `PAID`。
- 线下：显示收款账户，上传/登记凭证；运营确认人、确认时间、凭证和备注形成审计记录。不得仅凭前端“已付款”激活。
- 套餐不退款。充值允许退款，但须按来源 lot、未预占可退金额、原支付订单和已使用金额校验，不能退款超出可用充值余额。
- 自营 Tenant 的发票与付款主体为购买人；外部平台 Company 的消费不产生我方向 Company 的收款或发票义务。

### 7.2 精度与报表

数据库字段和 API 统一使用 `*_micro`，禁止浮点数。前端只作字符串/Decimal 格式化。账期内逐笔用量保留 micro；账单总额与发票金额在账期汇总后统一四舍五入到分。报表须同时展示：套餐权益消耗、理论金额、GIFT 消耗、RECHARGE 消耗、预占和实际结算差异。

## 8. 数据模型（BOSS）

以下为 BOSS 计费域目标表。开发环境直接使用新 schema，不在 IntelligentRecruitment 中保留本地计费表或兼容映射。

| 表 | 关键字段与约束 |
|---|---|
| `asset_accounts` | `id, owner_type(TENANT/COMPANY), owner_id, tenant_id, currency`；Company 唯一且归属 Tenant 不可改 |
| `money_lots` | `account_id, balance_kind(GIFT/RECHARGE), issued_micro, available_micro, reserved_micro, expires_at nullable, source_type, source_ref, parent_lot_id, status`；`available + reserved <= issued + adjustments` |
| `entitlement_lots` | `account_id, tenant_id, company_id, capability, unit, issued_quantity, available_quantity, reserved_quantity, available_from, expires_at, package_order_id, source_ref, parent_lot_id, status` |
| `asset_allocations` | `tenant_id, company_id, source_lot_id, target_lot_id, asset_kind, quantity_or_micro, external_order_id nullable, status`；来源/目标一一可追溯 |
| `asset_reservations` | `tenant_id, company_id, capability, idempotency_key, status, expires_at, actual_usage_ref`；唯一 `(calling_client_id, idempotency_key)` |
| `asset_reservation_lines` | `reservation_id, lot_id, asset_kind, reserved_quantity_or_micro, captured_quantity_or_micro, released_quantity_or_micro, price_snapshot` |
| `asset_ledger_entries` | `entry_type, debit_account/lot, credit_account/lot, amount_or_quantity, currency/unit, reference_type/id, occurred_at`；不可更新/删除 |
| `trial_grants` | 第 3.2 节定义的资格去重和发放证据 |
| `package_catalogs/package_versions` | 家族、续费组、等级、周期、价格、权益、上限和状态；版本不可变 |
| `package_orders` | `buyer_owner, channel(SELF/EXTERNAL), external_order_no nullable, package_version_snapshot, benefits_snapshot, payment_order_id nullable, status, activated_at, expires_at`；外部订单加 `(tenant_id, external_order_no)` 唯一 |
| `payment_orders/payment_transactions` | 支付渠道、原始金额 micro、支付分、第三方流水、回调原文摘要、状态、幂等键 |
| `billing_price_plans/billing_price_rules` | Tenant 覆盖或平台默认、能力、单位、micro 单价、生效区间、版本 |
| `in_app_notifications` | `tenant_id, recipient_user_id, notification_type, dedupe_key, payload, read_at`；`dedupe_key` 唯一 |
| `usage_events` | `tenant_id, company_id nullable, reservation_id, capability, actual units/tokens, price snapshot, theoretical_micro, cash_charged_micro, outcome` |

所有涉及 Company 的资产、预占、订单、用量和账本必须同时保存 `tenant_id`、`company_id`（若适用）和实际 lot/account ID。数据库 check、外键和服务层权限三重保证 Company 不会读取、预占或结算到其他 Tenant 的资产。

## 9. API 与集成边界

### 9.1 BOSS 对内能力

| 能力 | 调用方 | 要点 |
|---|---|---|
| 资产概览、账本、账单、套餐 | Recruitment BFF / OpenAPIPlatform BFF | 认证后由服务端确定 owner，禁止任意传 owner ID 越权 |
| 创建套餐/充值支付订单、查询支付状态 | 两个 BFF | 仅自主购买支持支付宝和线下；订单幂等 |
| 套餐/余额分配、Company 注销回收 | Tenant Owner BFF / 外部平台服务端 | 外部调用校验 API Client 与 Tenant 绑定 |
| 预占、捕获、释放 | Recruitment 内部服务 / 外部业务网关 | 必带幂等键；禁止客户端直接 capture 其他 Company |
| 外部 Company 套餐订单导入 | 外部平台服务端 | 权益快照、订单唯一、全量原子校验 |
| 套餐和价格运营配置 | IntelligentRecruitment admin BFF | 平台运营权限、操作审计 |
| 合同 Tenant 套餐履约配置 | IntelligentRecruitment admin BFF | 无支付单；必须绑定合同与权益快照 |

错误码最少包含：`TRIAL_ALREADY_GRANTED`、`PACKAGE_NOT_PURCHASABLE`、`PACKAGE_TIER_NOT_HIGHER`、`PACKAGE_MAX_FUTURE_END_EXCEEDED`、`PAYMENT_NOT_CONFIRMED`、`TENANT_ENTITLEMENT_INSUFFICIENT`、`TENANT_BALANCE_INSUFFICIENT`、`COMPANY_ASSET_INSUFFICIENT`、`RESERVATION_IDEMPOTENCY_CONFLICT`、`ASSET_EXPIRED`、`ALLOCATION_NOT_ALLOWED`、`COMPANY_CLOSURE_REQUIRED`。

### 9.2 IntelligentRecruitment 改造

- 后端移除“本地账户/账本为权威”的遗留假设，继续作为 BOSS 适配器；将现有 Company 单钱包接口替换为资产概览、套餐、预占/结算接口。
- 所有 JD 生成、简历解析、简历筛选、对话咨询在执行业务前调用 BOSS 预占；成功用量由服务端实际数据结算，异常路径可靠释放。
- 顶栏不再读取“第一个 ACTIVE Company”。根据当前工作空间/上下文读取资产：默认显示**总钱包余额**，展开显示 `赠送余额`、`充值余额`；同时展示当前套餐，未购买显示“未购买”。Company 上下文只显示该 Company；Tenant Owner 的 Tenant 上下文显示 Tenant 自身资产与可分配资产；个人显示个人资产。金额均使用微元格式化。
- 运营管理端新增套餐、套餐版本、权益内容、续费组、等级、价格计划、Tenant 价格覆盖、线下订单确认、资产调整和审计查询；不得由前端直连 BOSS 管理密钥。

### 9.3 OpenAPIPlatform 新项目

新项目路径为 `/Users/zhouwanchen/Documents/ChatGPT/OpenAPIPlatform`。它是独立业务前端与 BFF，BOSS 仍是全部数据权威。

第一期仅提供一个 `Tenant Owner` 角色，具备注册/资质提交、审核状态、套餐购买、充值、余额/权益/账单查看、Company 管理与分配、创建/禁用/轮换 App Key、外部订单查看和站内通知查看权限。App Secret 只在首次创建或轮换时显示一次，数据库仅保存 hash；浏览器不得保存 Secret。

技术服务商/外包开发者独立入驻、委托开发者权限和 Partner App 多 Tenant 访问不在本期开放；数据模型预留 `api_clients` 与成员/角色扩展点，但不暴露入口。这样招聘平台/业务客户统一作为 Tenant 注册，不产生 Tenant 关联不清的问题。

## 10. 到期、通知与后台任务

每日定时任务在 Asia/Shanghai 业务日计算到期日（数据库保存 UTC）。套餐只以 `package_order` 为通知主体：一个订单无论含多少权益批次，只在到期前 7 天向 Tenant Owner 发一条站内通知。试用金以 `trial_grant/money_lot` 为通知主体。通知唯一键分别为 `EXPIRY_7D:PACKAGE:{package_order_id}` 与 `EXPIRY_7D:TRIAL:{trial_grant_id}`，可重复扫描但不能重复发送。对于 Company 获分配的套餐权益，通知仍归属其来源 Tenant Owner，不向 Company 成员扩散。

到期任务执行顺序：先处理超时预占释放，再使未预占可用部分失效；预占中的资产不可直接失效。任务必须有租约/分布式互斥、分批处理和失败重试。站内通知是本期唯一渠道，后续可由 Outbox 扩展邮件/短信，不改变资产逻辑。

## 11. 权限、安全与审计

- 初期 OpenAPIPlatform 只有 Tenant Owner；BOSS 每个接口仍验证 Tenant/Company 归属，不能以“Owner 全权”跳过隔离。
- 外部 API 使用 App ID + Secret；Secret hash 存储、支持轮换、可撤销，调用日志脱敏，不记录明文 Secret、支付宝签名密钥或个人资料。
- 所有支付回调验签、来源 IP/幂等/重放防护、订单金额比对必须在 BOSS 完成。
- 所有运营操作（发放、调整、线下确认、配置、注销回收）记录 actor、理由、前后 lot、关联订单/预占及时间；账本与历史快照禁止 UPDATE/DELETE。
- 资产锁采用行级锁或等价的乐观版本锁；所有库存类更新以 `available >= requested` 条件更新防止并发超卖。

## 12. 实施顺序与验收

### 12.1 实施顺序

1. BOSS schema 重建：微元资产 lot、双分录账本、预占行、套餐/订单/试用资格、通知和新价格规则；删除测试数据后停用旧 Company 单钱包路径。
2. BOSS 领域服务：资格审核发放、套餐下单支付、外部订单导入、分配/注销回收、预占捕获释放、到期任务与权限审计。
3. BOSS API 与契约测试：所有写接口幂等，所有读取按 owner 权限过滤。
4. IntelligentRecruitment：适配新 BOSS API，替换业务预占/结算，改顶栏与账单页，增加运营配置页。
5. 创建 OpenAPIPlatform：Tenant 注册审核、Owner 门户、购买/充值、Company/资产、App/Key、账单与通知。
6. 端到端联调：支付宝沙箱/验签、线下确认、外部平台订单、四项能力、并发预占、到期通知。

### 12.2 必测验收场景

1. 同一统一社会信用代码审核成功两次，只产生一次 100 CNY、90 天 GIFT；Personal Tenant 注册、登录和购买套餐均不产生试用金。
2. Tenant 试用金分给两个 Company，均为 GIFT 且到期日与原 lot 完全一致；Company 不足时不扣 Tenant 剩余余额。
3. Company 同时有套餐、GIFT、RECHARGE，分别验证完整套餐扣减、套餐不足后 GIFT、GIFT 不足后 RECHARGE、全部不足拒绝。
   当套餐仍有效但权益和余额均耗尽时，自营门户显示充值提示；外部平台只收到不足错误。
4. 同套餐有效期内再次购买被拒绝；低等级购买被拒绝；高等级支付成功后即刻可用且新到期日顺延；原批次到期日不改变。
5. 相同支付回调、外部订单、预占键、捕获请求重复调用均不重复入账；相同键不同负载明确冲突。
6. 外部平台订单在 Tenant 权益不足时原子失败，Company 不获得任何资产，业务接口未执行。
7. Token 预占覆盖上下文和最大输出；实际少用释放，多用补扣；模型失败与预占超时均释放。
8. 人工补偿/扣减只能落在原实际使用的 GIFT 或 RECHARGE 类型，不能产生第三类余额或负可用余额。
9. Company 注销仅回收合格的 Tenant 来源资产；直接充值、已用、预占、过期资产不回收。
10. 到期前 7 天同批次仅生成一条 Tenant Owner 站内通知；到期后不能新预占，旧预占先结算或释放。
11. 价格修改后，历史用量、理论金额、账单和权益快照保持不变；账期汇总后才按分取整。
12. 顶栏切换 Tenant/Company/Personal 上下文时不串账；默认总余额与下拉 GIFT、RECHARGE 之和一致，并正确显示套餐或“未购买”。

## 13. 设计自检与已修复缺陷

本设计已逐项核对并修复以下容易遗漏的点：

| 风险/遗漏 | 修复结果 |
|---|---|
| 将“平台调整”当成第三种钱包 | 明确仅 GIFT/RECHARGE；调整以目标 lot 和原扣款类型入账 |
| Tenant 与 Company 计费混淆 | Company 只消耗已分配资产，无 Tenant 共享池回退；记录 tenant/company/lot 三重归属 |
| 套餐即买即用与顺延相互矛盾 | 用独立权益批次、立即 `available_from`、顺延 `expires_at`、FIFO 消耗精确定义 |
| 同套餐重复购买或变相降级 | 以 `subscription_group + tier_rank` 在下单和支付确认双重拦截 |
| 高等级套餐与旧权益被覆盖 | 所有套餐版本/权益 lot 不可变，旧到期日不改、先到期先用 |
| 外部平台订单按价格重算 | 只接收并保存外部权益快照，BOSS 仅校验 Tenant 资产和能力白名单 |
| Token 仅按前端字数预占 | 改为服务端模型 tokenizer 加完整上下文与最大输出上界 |
| 已过期资产被物理删除或预占丢失 | 资产状态化失效，预占先完成/释放，再处理到期 |
| 支付回调或外部订单重复发权益 | 订单、资格、预占和通知都有唯一幂等键/负载冲突规则 |
| 微元精度与支付/发票分单位冲突 | 内部全微元，账期汇总后统一按分四舍五入 |
| 公司注销回收越权或误回收 | 仅注销触发，限 Tenant 分配来源且未过期/未使用/未预占；保留来源追溯 |
| 顶栏继续读取第一个 Company | 明确按当前上下文读取资产并展示两类余额和套餐 |
| 有效套餐耗尽后用户不知道下一步 | 不足错误返回 `recharge_recommended`；自营门户展示充值提示，外部平台只返回机器可读错误 |
| 一个套餐的多个权益批次导致重复到期提醒 | 套餐仅以订单为通知主体，试用金单独以试用发放记录为主体 |
| 自动续费被误纳入本期 | 明确排除；本期仅 7 天前 Tenant Owner 站内提醒 |
| 外部平台合同套餐被当作线下支付订单 | 增加 `OPERATOR_PROVISIONED` 合同履约订单；无支付、无自动扣款，必须绑定合同和运营审计 |

结论：在“同一套餐有效期内不能重复购买、只允许升级”“即买即用但到期顺延”“无 Tenant 共享池”“两类金额余额”“外部平台 Company 不直接向我方付费”“Personal 不可转企业”的前提下，本设计不存在尚未闭合的业务链路。实施中不得恢复旧的 Company 单余额模型，也不得在任何不足路径隐式回退到 Tenant。
