# 业务应用前端与服务端开发阶段

版本：V0.2  
状态：已按 Phase 2 最新租户与账本基线修订，Phase 3—8 开发前评审稿  
技术栈：Next.js + TypeScript；Java 21 + Spring Boot

## 1. 阶段设计原则

开发不按照“先做完所有后端，再做所有前端”，也不按照页面数量平均拆分。建议按可验证的业务纵切逐阶段交付：

```text
产品规则和契约
→ 工程基础
→ 账号/租户/额度基础
→ JD 纵向闭环
→ 简历与人才纵向闭环
→ 筛选纵向闭环
→ 面试题和任务回看闭环
→ P0 整体加固
→ 真实 AI Platform 联调
```

每个业务阶段必须同时包含：

- 前端页面和状态。
- 服务端领域、API、数据库迁移和权限。
- Mock AI 能力或真实业务依赖。
- 失败、刷新恢复、幂等和审计。
- 测试和阶段验收。

## 2. 总体阶段

| 阶段 | 目标 | 主要结果 |
|---|---|---|
| Phase 0 | 冻结可开发基线 | 产品规则、关键决策、API/AI Contract V0.1 |
| Phase 1 | 建立工程地基 | Monorepo、前端壳、Spring 模块、基础设施、CI、Mock 框架 |
| Phase 2 | 建立身份和商业基础 | 登录、Company/Workspace、权限、试用额度、全局框架、账本骨架 |
| Phase 3 | 打通 JD 纵向闭环 | 招聘任务、AI 对话、JD 草稿/确认/版本、职位库 |
| Phase 4 | 打通简历和人才闭环 | 安全上传、对象存储、异步解析、人才库、PII 脱敏 |
| Phase 5 | 打通筛选和计费闭环 | 筛选方案、预算确认、异步匹配、部分成功、结算 |
| Phase 6 | 完成面试题和产品闭环 | 候选人选择、题目包、任务回看、概览、设置、账单详情 |
| Phase 7 | P0 整体加固 | E2E、安全、性能、恢复、可观测性、发布准备 |
| Phase 8 | 伙伴平台联调 | 逐能力替换 Mock、契约和故障验收 |

Phase 8 可在伙伴具备开发条件后与 Phase 4–7 部分并行，但不能绕过契约测试直接替换全部 Mock。

---

## Phase 0：可开发基线冻结

### 目标

在写业务代码前，确定会影响数据库、接口和计费的规则；其余非阻塞事项保留可配置默认值。

### 产品任务

- 确认 P0 是内部 Demo 还是真实客户试用。
- 确认 P0 用户、页面和明确不做的 P1/P2 功能。
- 确认试用额度、JD、筛选、面试题的计费单位。
- 确认失败、取消、部分成功和重试收费原则。
- 确认文件格式、大小、批量数量和 OCR 最低范围。
- 确认 PII Reveal 角色、数据地域和保留原则。
- 确认筛选缺失信息、硬性条件和敏感属性规则。

### 前端准备

- 确认桌面端 1280/1440/1600 的页面框架。
- 确认智能招聘工作台的左成果区和右 AI 区布局。
- 完成 P0 页面流程和关键异常状态线框。
- 固定 Design Tokens、状态颜色和核心业务组件清单。

### 服务端准备

- 评审领域实体、状态机和数据版本策略。
- 评审 REST API 资源边界和错误格式。
- 评审 AI Platform V0.1 能力、Task、Webhook、Usage 和错误协议。
- 确认认证、对象存储、RabbitMQ、短信和安全扫描的实现/供应商方向。

### 交付物

- Product Baseline V1 或可开发的 V0.x 冻结版。
- OpenAPI 初稿。
- AI Contract V0.1 和 JSON Schema 初稿。
- 页面流程/关键状态稿。
- 决策记录和仍可后置的事项。

### 退出条件

- 不存在会使核心表结构完全相反的未决问题。
- 计费即使未确定具体金额，也确定单位和生命周期。
- 伙伴已评审 AI Contract 的可实现性；若尚不能评审，Mock 严格按草案实现并标记风险。

---

## Phase 1：工程与架构基础

### 目标

建立能够持续开发、测试和部署的项目骨架，但不追求大量业务页面。

### 前端任务

- 初始化 Next.js App Router、严格 TypeScript 和 pnpm 工作区。
- 配置 Tailwind CSS、CSS Token 和 Radix UI 基础组件。
- 建立 `app/features/components/lib/styles` 结构。
- 建立路由壳、错误边界、Loading 和未授权页。
- 建立统一业务 API Client、错误映射和 OpenAPI 类型生成流程。
- 配置 TanStack Query、React Hook Form、Zod、MSW。
- 配置 Vitest、Testing Library、Playwright。

### 服务端任务

- 初始化 Java 21、Spring Boot 和 Maven Wrapper。
- 建立按业务能力分包的模块化单体。
- 配置 Spring MVC、Spring Security、Validation、JPA、jOOQ、Flyway。
- 配置 PostgreSQL、Redis、RabbitMQ、MinIO/S3 Client。
- 建立统一错误协议、请求 ID、结构化日志和基础审计接口。
- 建立事务、Outbox、幂等记录和 Worker 基础设施。
- 建立 `AIPlatformClient` Port、Mock/HTTP Adapter 接口骨架和契约校验器。
- 配置 JUnit 5、Testcontainers、ArchUnit。

### 基础设施/工程任务

- Docker Compose：PostgreSQL、Redis、RabbitMQ、MinIO。
- CI：前端检查/测试/构建；Java 编译/测试/迁移/架构检查。
- 环境配置：local、test、staging；Secret 不进入仓库。
- 健康检查、Readiness、数据库迁移策略。

### 纵切验证

实现一个非业务化最小链路：

```text
Web 页面
→ Business API
→ PostgreSQL 读写
→ RabbitMQ Worker
→ 状态查询
```

同时验证 Mock AI Adapter 能产生一个可查询的模拟任务。

### 退出条件

- 新环境可通过文档化命令启动。
- CI 全部通过。
- 数据迁移可重复执行。
- API 与 Worker 共用领域代码但可独立启动。
- 架构测试阻止跨层/跨模块错误依赖。
- 尚未实现正式业务流程是正常的。

---

## Phase 2：身份、租户、额度与产品框架

实现状态：**已完成 MVP 代码基线（2026-08-24）**。最新租户模型以 `Company + Workspace` 为准，旧的单层 `Organization` 表述不再用于新代码。

### 目标

完成所有后续业务必须依赖的用户、Company/Workspace、权限、余额和应用框架。

### 前端任务

- 登录/注册、验证码倒计时、协议勾选和错误状态。
- 可选企业信息采集和首次进入引导。
- Top Navigation、Sidebar、用户菜单和基础响应式。
- 余额展示和构成 Popover。
- 概览占位采用真实 API 数据结构，不写死假 KPI。
- 个人设置基本表单和退出登录。
- 受保护路由、登录过期和权限错误处理。

### 服务端任务

- `User`、`Company`、`Workspace`、两级 Membership、Session/Token 模型。
- 企业创建认证、已有企业认领、成员邀请和主动加入的平台审核流程。
- 验证码发送/验证抽象、限流和防重复注册。
- Spring Security 认证、Company 治理上下文和 Workspace 数据权限。
- 资源/方法级授权基础设施。
- `BillingAccount`、额度批次和 `BillingLedgerEntry`。
- 注册试用额度幂等发放。
- 余额查询和账本列表基础 API。
- PII/Secret 字段保护和审计基础能力。

### 测试重点

- 重复验证码提交不重复创建用户、Company、Workspace 或额度。
- 未登录、非 Workspace 成员和跨 Workspace 请求被服务端拒绝。
- 客户端隐藏按钮不是唯一权限手段。
- 余额可由账本解释，金额无浮点误差。

### 退出条件

- 新用户能完成注册、创建或加入 Workspace，并按已确认规则获得一次试用额度后进入应用。
- 登录态过期和退出流程正确。
- 所有招聘业务 API 必须具有 Workspace 上下文；企业数据同时保存一致的 `company_id + workspace_id`。
- Phase 3 不需要重做认证和账本基础。

---

## Phase 3—8 共同约束（继承 Phase 2）

以下规则是后续阶段的强制开发基线，不再在各业务模块中自行解释：

1. `Workspace` 是招聘业务、权限和账本的隔离边界；不增加 `Department` 层级，也不以 Company 代替 Workspace 鉴权。
2. 所有业务 API 使用 `/api/v1/workspaces/{workspaceId}/...`，服务端从 Workspace 反查 `company_id` 并校验当前用户的有效 `WorkspaceMembership`，不信任客户端独立提交的 `company_id` 或角色。
3. 企业 Workspace 的招聘业务记录强制保存一致的 `company_id + workspace_id`；个人 Workspace 的 `company_id = NULL`。唯一约束、索引、Repository 查询、缓存 Key、对象存储 Key、消息和搜索索引均包含 `workspace_id`。
4. Company Owner/Admin 只能管理企业及 Workspace 的名称、Owner、成员数、状态等治理元数据。未加入目标 Workspace 时，不能读取其中的职位、人才、简历、对话、任务、AI 结果或账单明细，也不能通过聚合、搜索或导出推断这些内容。
5. 职位库、人才库、任务历史和面试题产出均归属于 Workspace。MVP 不实现 Company 共享库、跨 Workspace 搜索、复制、关联或数据授权。
6. 每个 Workspace 对应独立 `BillingAccount`。个人30元试用金进入唯一个人 Workspace；企业100元试用金只进入认证后的首个 Workspace，其他 Workspace 不自动获得试用金；两者均自发放起90天过期。
7. 收费任务统一执行“报价/确认 → 预占 → 成功单位结算 → 失败或差额释放”。金额使用整数分；业务任务、预占、Outbox 和幂等记录必须在可靠事务边界内建立，AI Usage 不能直接作为客户账单。
8. 异步任务、Webhook 和重试必须继承创建时冻结的 `company_id + workspace_id`，回调不能改变 Scope。审计记录包含 Actor、Scope、业务引用和结果，但不得记录完整 PII、Prompt 或简历正文。
9. 当前 Workspace 是前端所有业务页面的明确上下文。切换 Workspace 后必须清空或按 Scope 隔离查询缓存、草稿、上传队列和流式连接，禁止显示上一个 Workspace 的残留数据。
10. Phase 2 的 Mock 验证码、人工认证审核和非真实支付现状不阻塞 Phase 3—6 开发，但进入真实客户候选发布前必须按 Phase 7 门禁处理。

---

## Phase 3：招聘任务、JD 生成与职位库

### 目标

先完成第一个真正的 AI 招聘纵向闭环，用它验证工作台、流式输出、结构化结果、版本和 Mock Adapter。

### 前端任务

- 所有入口绑定当前 Workspace；无 Workspace、无成员权限和 Workspace 已停用时进入明确阻断页。
- 招聘任务创建、任务路由和刷新恢复。
- 左侧成果区 + 右侧 AI 助手区。
- 用户/AI 消息、Agent Badge、费用提示和流式状态。
- JD 需求采集、缺失字段提示和生成过程。
- 结构化 JD 编辑器、人才画像、警告和人工确认栏。
- 职位列表、搜索/筛选、详情、版本和归档。
- 生成失败、流中断、重试、草稿恢复和并发编辑提示。
- 切换 Workspace 时关闭原流式连接并清除未提交草稿；不得提供 Company 级职位汇总或跨 Workspace 搜索。

### 服务端任务

- `RecruitmentTask`、`Conversation`、`Message` 均保存 Scope；产品可见消息与技术 Trace 分库存储或严格分层查询。
- `Job`、`JobVersion` 均保存 Scope；版本为不可变快照，`Job` 只引用当前确认版本并使用乐观锁。
- JD 草稿、确认和新版本业务命令。
- JD 生成 AI Run、流式代理/转发和结构化结果校验。
- Product-visible 消息与 AI 技术 Trace 分离。
- JD 收费执行报价、用户确认、Workspace 账户预占、成功结算和失败释放；价格未冻结时只允许使用明确标记的非生产配置。
- 创建招聘任务、计费预占、AI Run 和 Outbox 使用同一业务幂等键；刷新或双击不能产生重复任务和扣费。
- Repository 以资源 ID + `workspace_id` 查询；企业记录落库前校验 `company_id` 与 Workspace 归属一致。
- Mock JD：追问、流式 Delta、正常结果、超时、非法 Schema。

### 数据模型增量

```text
recruitment_tasks  - id, company_id, workspace_id, type, status, created_by, idempotency_key
conversations      - id, company_id, workspace_id, recruitment_task_id, status
messages           - id, company_id, workspace_id, conversation_id, role, content_ref, sequence
jobs               - id, company_id, workspace_id, current_version_id, status, version
job_versions       - id, company_id, workspace_id, job_id, version_no, structured_content, confirmed_by
ai_runs            - id, company_id, workspace_id, capability, business_reference, status, provider_task_id
```

企业记录的 Scope 一致性由服务层校验并以数据库约束/触发器或可验证的外键方案兜底；个人记录不得写入 `company_id`。

### API 重点

- `POST/GET /api/v1/workspaces/{workspaceId}/recruitment-tasks...`
- `POST /api/v1/workspaces/{workspaceId}/conversations/{id}/messages`
- `POST /api/v1/workspaces/{workspaceId}/jd-runs`，以及状态、重试和取消（若能力允许）。
- `/api/v1/workspaces/{workspaceId}/jobs...` 下的草稿、确认、版本、列表、详情和归档。
- 写操作要求 `Idempotency-Key`；资源 ID 不得脱离 Workspace 路径单独访问。

### 测试重点

- 生成结果先为草稿，不能直接覆盖确认版本。
- 刷新后恢复任务和 JD 草稿。
- 流式文本与最终结构化结果不混为业务事实。
- 非法 AI 结果不写入正式 JobVersion。
- 历史筛选输入未来可以稳定引用某个 JobVersion。
- 同一 Company 下两个 Workspace 也不能互查职位或任务；Company Owner/Admin 未加入 Workspace 时同样被拒绝。
- 个人 Workspace 记录 `company_id` 为空，企业 Workspace 记录 Scope 一致。
- 余额不足不创建收费 AI Run；失败释放、成功结算和重复请求不重复扣费。

### 退出条件

- 用户可以从招聘需求得到并确认 JD。
- 已确认 JD 可在职位库回看和创建新版本。
- Mock 延迟、流中断和错误路径可演示并测试。
- 职位库和任务历史严格限定当前 Workspace，账本能追溯每次 JD 生成。
- 这是第一条可部署的业务纵切。

---

## Phase 4：简历上传、解析与人才库

实现状态：**已完成 MVP 代码基线（2026-08-25）**。本地环境使用私有 MinIO 和同步 Mock Parse，以便在伙伴平台未就绪时完整演示上传、文件级失败、解析版本、PII 保护和 Workspace 隔离；真实恶意文件扫描、异步回调及伙伴侧删除确认仍受 Phase 7/8 上线门禁约束。

### 目标

建立真实候选人数据入口和安全文件链路，为筛选准备不可变解析版本。

### 前端任务

- 上传、人才库、候选人详情和原文入口均限定当前 Workspace；切换 Workspace 时取消或隔离尚未完成的上传展示。
- 拖拽/选择批量上传。
- 文件级格式、大小、上传和解析状态。
- 单文件失败和重试，成功文件保留。
- 人才列表、筛选、分页和解析状态。
- 候选人详情、结构化简历、原文对照入口。
- PII 默认脱敏、Reveal 操作和授权失败状态。
- 原简历下载和删除确认。
- MVP 不展示 Company 人才库、跨 Workspace 查重结果或共享开关。

### 服务端任务

- `FileAsset`、`ResumeFile`、`Candidate`、`ResumeParseVersion` 均保存冻结 Scope；Candidate 只属于一个 Workspace。
- 上传授权、Magic Bytes/MIME/大小校验、哈希和对象存储。
- 恶意文件扫描状态和解析前 Gate。
- 简历解析业务任务、Outbox、Worker 和 AI Adapter。
- 候选人匹配/重复提示只在当前 Workspace 内进行；P0 不自动合并，也不泄露其他 Workspace 是否存在相同人员。
- 解析 Schema 校验、版本持久化和人工纠错策略。
- PII 加密/脱敏、Reveal/下载/删除授权和审计。
- 对象 Key、上传凭证、下载凭证、队列消息、解析回调和临时文件路径全部携带且校验 `workspace_id`；短时 URL 只能在授权完成后签发。
- 删除采用状态机并保留最小审计/账务引用；对象、派生文本、索引和缓存异步清理可重试，不能只删除数据库主记录。
- Mock Parse：批量进度、单项失败、部分成功、非法字段。

### 数据模型增量

```text
file_assets           - id, company_id, workspace_id, object_key, hash, scan_status, lifecycle_status
resume_files          - id, company_id, workspace_id, candidate_id, file_asset_id, status
candidates            - id, company_id, workspace_id, display_name_masked, duplicate_hint, status
resume_parse_versions - id, company_id, workspace_id, candidate_id, resume_file_id, version_no, schema_version
```

文件哈希可用于当前 Workspace 内防重复，但不得提供跨 Workspace 存在性查询；如底层做全局物理去重，也不能改变逻辑隔离、密钥和删除语义。

### 测试重点

- 扩展名伪装、错误 MIME、超限、恶意文件和未授权下载。
- 批量部分成功。
- 重复回调不生成重复 Candidate/ParseVersion。
- 其他 Workspace（包括同一 Company 内）无法通过 ID、文件地址、哈希、列表、搜索或数量推断数据。
- 日志、URL 和前端缓存中无完整 PII。
- Workspace 成员被移除或停用后，已有下载 URL、轮询和解析结果访问按策略尽快失效。

### 退出条件

- 用户能安全导入多份简历并查看结构化、脱敏结果。
- 每个可筛选候选人都有明确的 ResumeParseVersion。
- 页面刷新后上传后续解析状态可恢复。
- 文件安全和租户隔离阻断级测试通过。
- 人才库归 Workspace，未引入 Company 共享库或 Department 模型。

---

## Phase 5：筛选方案、人岗匹配与费用结算

实现状态：**已完成 MVP 代码基线（2026-08-25）**。已实现筛选方案不可变版本、敏感属性拦截、5 分钟费用报价、当前 Workspace 余额校验、预占/部分结算/释放、幂等、失败项重试、取消契约和可解释结果；本地 Mock Screening 同步完成，真实异步 Webhook、乱序事件和 Provider 取消竞态在 Phase 8 按同一契约联调。

### 目标

完成产品最关键、风险最高的批量筛选闭环。

### 前端任务

- 只能选择当前 Workspace 内的 JD 版本、解析版本和候选人范围；不可粘贴其他 Workspace 的资源 ID 绕过选择器。
- 筛选方案生成、权重编辑、必须项/排除项和缺失信息规则。
- 权重和规则校验。
- 候选人数、价格和费用预估确认弹窗。
- 筛选进度、项目级状态和部分成功。
- 匹配列表、Score/Level、匹配点、不匹配点、可协商项、缺失、风险和证据。
- 候选人详情 Drawer/页面和人工复核提示。
- 失败项目重试、取消和余额不足状态。
- 费用确认展示扣款 Workspace、预计成功单位、单价/计价规则版本和可用余额，不展示或使用其他 Workspace 余额。

### 服务端任务

- `ScreeningPlan`、`ScreeningPlanVersion`。
- `ScreeningRun`、`ScreeningRunItem`、`ScreeningResult`。
- 上述实体及其输入快照均保存 `company_id + workspace_id`；Run 冻结引用 `JobVersion + ResumeParseVersion + ScreeningPlanVersion + PricingVersion`。
- 筛选方案合法性、敏感属性和权重规则。
- 费用 Estimate、Reservation、Settlement、Release。
- 报价只在短有效期内有效；确认时重新校验余额、输入版本和候选人数。在同一事务中创建 Run、Items、Reservation 和 Outbox。
- 预占只能来自当前 Workspace 的 `BillingAccount`；成功单位按冻结价格结算，失败/取消/未处理单位释放，释放额度保持原额度批次到期属性。
- AI Task 映射、Webhook 验签、事件去重和状态单调；回调通过本地 AI Run 反查 Scope，不接受回调提交的新 Scope。
- 项目级结果 Schema/证据引用校验。
- 部分成功和失败项目重试；重试创建新 Attempt，是否收费按冻结规则明确展示并保持幂等。
- Usage 记录与客户价格分离。
- Mock Screening：进度、项目结果、部分失败、重复/乱序回调、取消竞态。

### 数据模型增量

```text
screening_plans         - id, company_id, workspace_id, job_id, current_version_id, status
screening_plan_versions - id, company_id, workspace_id, plan_id, version_no, rules_snapshot
screening_runs          - id, company_id, workspace_id, job_version_id, plan_version_id, pricing_version
screening_run_items     - id, company_id, workspace_id, run_id, candidate_id, parse_version_id, status
screening_results       - id, company_id, workspace_id, run_item_id, attempt_no, result_snapshot
```

### 测试重点

- 双击确认不重复创建 Run 或预占。
- 重复/乱序回调不重复结果、回退状态或结算。
- 部分成功只结算已确认规则中的成功单位。
- Retry 只处理失败项目并创建新 Attempt。
- AI 结果缺证据、分数越界或版本不匹配时拒绝落正式结果。
- 敏感属性不作为默认评分/排除条件。
- 跨 Workspace 输入引用、非成员请求和未加入 Workspace 的 Company Owner/Admin 均被拒绝且不产生预占。
- 并发确认、到期额度、部分成功、取消竞态下，余额投影与不可变账本可重建且一致。

### 退出条件

- 筛选结果可以追溯 JD、解析、方案和 AI 版本。
- 用户看到的不只是分数，而是完整解释和人工确认提示。
- 账本可解释预占、结算和释放。
- 刷新、重复请求、部分失败和回调乱序测试通过。
- 不存在 Company 共享余额或从其他 Workspace 自动补足余额的隐式行为。

---

## Phase 6：面试题、任务历史、概览和账单闭环

### 目标

补齐筛选之后的业务产出和产品级回看，使 P0 完整可用。

### 前端任务

- 从当前 Workspace 的筛选结果明确选择候选人。
- 题目生成数量和费用确认。
- 面试题按专业/行为/场景/追问分组。
- 展示出题原因、考察点、追问、评分要点和证据。
- 题目编辑、确认和回看。
- 在招聘任务、职位和候选人详情展示关联题目包。
- 招聘任务列表、状态、费用、只读回看和继续处理。
- Workspace 概览展示真实 KPI、待确认项、异常任务和近期消耗；不得把多个 Workspace 的招聘数据合并成 Company 概览。
- 账单明细、额度批次、90天到期提醒、业务关联、状态和空状态；完整账本仅 Workspace Owner/Admin 可见，Recruiter 只见余额和本人可操作任务所需的费用提示。
- 基础个人设置完成。
- Company Owner/Admin 的企业治理页面只展示 Workspace 名称、Owner、成员数和状态；进入某 Workspace 的业务页面仍要求其是该 Workspace 成员。

### 服务端任务

- `InterviewKit`、`InterviewQuestion`、生成 Run 和版本均保存 Scope，并冻结职位、候选人、简历解析与筛选结果版本。
- 面试题生成执行当前 Workspace 账户的费用预占/结算、AI Adapter 和结果校验。
- 招聘任务聚合查询和历史回看只聚合当前 Workspace 内的业务引用。
- 任务继续处理的上下文摘要/业务引用。
- Workspace 概览统计查询，必要时使用 jOOQ；Company 治理投影独立查询，禁止 Join 招聘明细表产生越权字段。
- 账本筛选、分页、关联业务详情和对账查询必须校验 Workspace 角色；不存在 Company 级账单明细入口。
- Mock Interview：候选人级成功/失败、非法证据和 Usage。

### 数据模型增量

```text
interview_kits          - id, company_id, workspace_id, job_version_id, candidate_id, status
interview_kit_versions  - id, company_id, workspace_id, kit_id, screening_result_id, version_no
interview_questions     - id, company_id, workspace_id, kit_version_id, category, content, evidence_refs
dashboard_projections   - workspace_id, metric_date, metric_type, metric_value
```

概览投影只允许按单个 Workspace 构建和查询。Company 治理投影使用独立模型，且不包含上述招聘业务指标和正文引用。

### 测试重点

- 不自动为高分候选人出题；必须人工选择和确认费用。
- 题目包绑定正确的职位、简历和筛选结果版本。
- 继续任务不覆盖历史 AI Run 和费用。
- 任务、职位、候选人三个入口看到同一业务结果。
- 概览和账单来源一致。
- 同一 Company 不同 Workspace 的 KPI、任务历史、题目包和账本相互不可见；治理管理员不能通过统计接口推断业务内容。
- 试用额度临近到期、已过期、预占跨过期时点和释放回原批次的展示与账本一致。

### 退出条件

- P0 正常闭环在 Mock AI Platform 下端到端通过。
- 用户能回看职位、候选人、筛选、面试题和费用的完整关系。
- 不需要完整独立面试题库也能保存和复用本次招聘产出。
- Company 治理页与 Workspace 业务概览职责清晰，不存在“企业管理员默认看全部招聘数据”的入口。

---

## Phase 7：P0 整体加固与候选发布

### 目标

从“功能可用”提升到“可以安全试用”。

### 前端加固

- 1280/1440/1600 响应式和右侧 AI 面板行为。
- Loading、Empty、Error、Partial、Permission Denied 全量检查。
- 键盘、Focus、对比度和屏幕阅读器基础检查。
- 网络中断、流式重连和任务轮询退避。
- 敏感信息、错误监控和埋点检查。
- 核心页面性能和大列表体验。
- 多 Workspace 切换的 Query Cache、浏览器历史、草稿、下载 URL 和流式连接隔离检查。

### 服务端加固

- 全资源跨 Workspace 授权测试；覆盖个人 Workspace、同一 Company 双 Workspace、跨 Company、多重成员身份和 Company 管理员非 Workspace 成员。
- 账本对账、补偿和异常修复流程。
- Outbox、队列重试、死信和任务补偿。
- 限流、批量并发和资源配额。
- 文件扫描、下载和删除生命周期。
- 数据库索引、慢查询、连接池和批量处理。
- 结构化日志、指标、Trace、告警和运维接口。
- 备份、恢复、迁移和回滚演练。
- 对象存储、缓存、搜索索引、消息、导出和可观测数据的 Scope 泄漏专项检查。
- 成员停用、Owner 转让、Workspace 停用后的同步与异步权限收敛验证。

### 测试与验收

- P0 正常 E2E。
- 文件部分失败。
- AI 超时/重试。
- 回调重复/乱序。
- 取消与完成竞态。
- 余额不足和重复付费确认。
- 跨 Workspace/跨 Company 访问、Company 管理员越权和未授权 PII。
- Mock 契约全场景。
- 基础容量/性能验证。
- 90天额度到期、预占/结算/释放并发、重复回调和账本重建。
- 恶意注册、验证码滥用、认证材料枚举、邀请重放和批量资源枚举。

### 退出条件

- `07-quality-and-acceptance.md` 中所有阻断级问题关闭。
- P0 E2E 和必测异常通过。
- 监控能定位 Web、API、Worker、AI Platform 和账本阶段。
- 若面向真实客户，隐私/用户服务协议和数据处理规则已确认。
- 若面向真实客户，Mock 验证码必须替换为真实短信通道或限制为明确的封闭测试白名单；实名/企业认证方式及材料保留期限取得上线结论。
- 具备 Staging 或候选发布环境。

---

## Phase 8：真实 AI Platform 逐能力联调

### 目标

在不改变业务领域逻辑的前提下，将 Mock 逐项替换为伙伴能力。

### 推荐顺序

1. 服务鉴权、健康检查、Task 查询和 Webhook 签名。
2. JD 生成和流式对话。
3. 简历解析。
4. 筛选方案。
5. 批量筛选。
6. 面试题生成。
7. Usage、成本和全链路 Trace 对账。

### 我方任务

- 完成 `HttpAIPlatformClient` 的伙伴 DTO 转换。
- 同一组契约测试同时运行在 Mock 和真实环境。
- 配置按 Capability 切换 Adapter，而不是全局一次切换。
- 增加真实平台限流、超时、重试和熔断参数。
- 验证文件传输、数据地域、删除和日志策略。
- 对比 Mock 与真实结构化结果的 Schema 和业务表现。
- 所有请求上下文发送不可变的 `request_id`、`business_task_id`、`workspace_id` 和可空 `company_id`；个人 Workspace 的 `company_id` 必须为空。伙伴平台只信任我方服务身份，不能据此向终端用户开放数据访问。
- 文件能力只传递受限 File Reference 或短时签名地址，不传永久公网 URL；伙伴侧日志、缓存、保留和删除策略逐项验收。
- 回调仅携带伙伴任务标识和结果事件；业务服务通过本地映射恢复冻结 Scope，Scope 不匹配、未知任务或过期签名一律拒绝并审计。
- 真实能力按 Capability 和环境灰度。生产环境回退时优先禁用收费能力或明确失败，不能悄悄用 Mock 结果冒充真实结果。

### 双方验收

- OpenAPI/JSON Schema 兼容。
- 幂等任务创建。
- 流式断线和任务查询恢复。
- Webhook 重试、重复、乱序和验签。
- 批量部分成功和项目级重试。
- 取消竞态。
- Usage 精度和供应商成本对账。
- 性能、容量和 SLA。
- 数据保留、删除和敏感数据处理。
- Workspace 隔离验证：同一 Company 双 Workspace、个人 Workspace、重复任务标识、错误 Scope 回调和文件越权均通过负面测试。
- 伙伴 Usage 只用于成本与计量核对；客户结算仍依据业务服务冻结的价格版本和成功业务单位。

### 退出条件

- 每个 P0 Capability 单独通过契约和故障测试。
- 真实 Platform 接入不要求修改招聘领域模型或客户计费模型。
- 端到端业务闭环在真实环境通过。
- 可以按 Capability 快速回退 Stub（仅测试环境）或在生产禁用能力，不影响已确认业务版本和既有账本。

---

## 3. 前后端并行关系

### 可以并行

- 前端 Design System 与后端工程基础。
- 登录 UI 与身份、Company/Workspace API。
- 工作台 UI 与 RecruitmentTask/Conversation 模型。
- 职位页面与 Job/JobVersion API。
- 上传组件与对象存储/文件 API。
- 筛选结果组件与 Screening Contract/Mock。
- 面试题页面与 InterviewKit 模型。

前端先使用 MSW/业务 API Mock，随后切换业务服务；业务服务使用 Mock AI Platform，不等待伙伴。

### 必须先后

- 筛选 Run 开发依赖 JobVersion、ResumeParseVersion 和 ScreeningPlanVersion 已定义。
- 面试题生成依赖筛选结果或至少明确输入版本。
- 客户结算依赖账本和幂等基础完成。
- 真实 AI Platform 接入依赖 V1 Contract 和 Mock 契约测试。
- 真实简历试用依赖文件安全、PII 和租户隔离通过。

## 4. 每阶段统一完成标准

一个阶段不能仅以“接口写完”或“页面画完”为完成。至少满足：

- 产品规则和状态与基线一致。
- 前端拥有正常、加载、空、错误、权限、部分成功等相关状态。
- 服务端完成权限、事务、幂等、审计和迁移。
- OpenAPI/前端类型同步。
- AI 能力有可配置 Mock 场景。
- 单元、集成和关键 E2E/契约测试按风险完成。
- 日志可诊断且无 PII/Secret 泄露。
- 文档和待确认事项同步更新。

## 5. 不建议的开发顺序

- 不要先一次性写完所有数据库表，再开始验证业务闭环。
- 不要先完成所有页面静态稿，再统一接 API。
- 不要等待伙伴平台完成后才做业务开发。
- 不要让客户端直接接 Mock AI Platform，绕过业务服务。
- 不要在 JD/简历版本尚未稳定时先实现完整筛选。
- 不要在账本和幂等尚未完成时上线按量计费。
- 不要先建设 P1 自动工作流、Company 共享库、Company 共享额度、Department 层级、在线充值或完整面试题库。

## 6. 建议里程碑

| 里程碑 | 对应阶段 | 可演示成果 |
|---|---|---|
| M0 可持续开发 | Phase 0–1 | 工程、CI、Mock Task、基础架构 |
| M1 可登录产品 | Phase 2 | 注册、Company/Workspace、试用额度、应用框架 |
| M2 首个 AI 纵切 | Phase 3 | 需求对话、JD 确认、职位库 |
| M3 人才数据可用 | Phase 4 | 安全上传、解析、人才库 |
| M4 核心价值成立 | Phase 5 | 可解释筛选和正确结算 |
| M5 P0 闭环 | Phase 6 | 面试题、回看、概览、账单 |
| M6 候选发布 | Phase 7 | 安全、可靠、可观测的试用版本 |
| M7 真实 AI 联调 | Phase 8 | 伙伴能力逐项替换 Mock |

## 7. Phase 3—8 开发门禁与待确认项

### 7.1 已由 Phase 2 冻结，不再重复讨论

- 手机号验证码登录；Access Token 30分钟、Refresh Token 14天；支持多设备、单设备退出和全部退出。
- 不使用平台注册邀请码；邀请只用于加入已有 Company/Workspace。
- Company 是企业根租户，Workspace 是招聘业务、权限和账本隔离边界；不增加 Department。
- Company Owner/Admin 未加入 Workspace 时无招聘业务数据和账单明细权限。
- 企业业务记录保存一致的 `company_id + workspace_id`，个人记录 `company_id = NULL`。
- 人才库、职位库、任务和面试题归 Workspace；MVP 不做跨 Workspace 共享。
- 个人30元、企业100元试用额度分别进入指定 Workspace，均一次性且90天过期；企业其他 Workspace 不自动获得额度。
- 金额以分存储，收费任务使用不可变账本及预占、结算、释放生命周期；MVP 不接真实支付。

### 7.2 分阶段必须确认

| 最迟时间 | 必须冻结的事项 | 未确认时的处理 |
|---|---|---|
| Phase 3 开工前 | JD 计费单位/单价、一次“成功”的定义、用户取消与重试规则、JD 结构化 Schema | 可用非生产价格占位开发，不得作为真实客户收费依据 |
| Phase 4 开工前 | 文件类型/单文件与批量上限、对象存储、病毒扫描、OCR 范围、Workspace 内去重字段、PII Reveal 角色 | 未确认的文件类型和 Reveal 能力默认关闭 |
| Phase 5 开工前 | 筛选基础费/单份价格、成功单位、部分成功、重试收费、硬性项/敏感属性/缺失信息规则、语义召回责任方 | 不允许开启真实收费筛选 |
| Phase 6 开工前 | 面试题计费单位/单价、题目编辑后版本规则、复用是否收费、Workspace 概览 KPI 口径 | 使用明确标记的 Mock/非生产配置 |
| Phase 7 候选发布前 | 真实短信或封闭白名单、实名/企业认证方式、认证材料与简历保留期限、数据地域、协议/隐私文本、安全扫描与告警方案 | 不得开放真实客户或真实简历试用 |
| Phase 8 联调前 | 伙伴鉴权、Webhook 签名、SLA、限流、取消、批量/部分成功、文件保留删除、Usage 精度、Schema 兼容与版本策略 | 对应 Capability 保持 Mock/Stub，仅限测试环境 |

### 7.3 非阻塞但必须留痕

- 具体价格可先配置化，但每次报价和任务必须冻结 `pricing_version`，不能因后来改价重算历史账单。
- Phase 4 前未确定认证材料保留期限不阻塞 Phase 3；但 Phase 7 门禁前必须取得法务/隐私结论。
- 伙伴暂时不能开发不阻塞 Phase 3—7，前提是 Mock、OpenAPI 和 JSON Schema 持续执行同一组契约测试。
- 任何新增的 Company 共享库、共享额度、跨 Workspace 报表或 Department 需求均视为 P1 架构变更，不得顺带进入 P0。
