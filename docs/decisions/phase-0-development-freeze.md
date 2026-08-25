# Phase 0 可开发基线冻结记录

版本：Development Baseline V0.1  
日期：2026-08-21  
状态：我方开发冻结；商业规则和伙伴接口仍需最终批准

## 1. 冻结目的

本记录把仍未获得外部确认、但会阻塞 Phase 1 工程建设的事项分成三类：已采用、临时默认、后续 Gate。开发可以依据临时默认前进，但不得把它们写死在不可配置的领域逻辑中。

## 2. 已采用的产品和架构决策

- P0 按可能处理真实简历的标准设计，不以纯演示安全标准降级。
- P0 用户以企业 HR 为主，兼容招聘顾问；第一阶段只启用单组织管理员使用路径。
- P0 使用引导式 `JD → 筛选 → 面试题`，不实现完整自动招聘工作流。
- 面试题结果保存并可回看，但不建设完整独立面试题库。
- 对话历史收敛为产品级招聘任务历史。
- 浏览器只调用 AI 招聘业务服务。
- 业务服务是招聘数据和客户账单事实源；AI Platform 是 AI 执行和供应商用量事实源。
- 采用 Next.js/TypeScript 客户端和 Java 21/Spring Boot 模块化业务服务。
- 使用 PostgreSQL、Redis、RabbitMQ 和 S3-compatible Object Storage。
- 第一阶段基于 Mock AI Platform 开发；真实平台逐能力替换。
- JD、简历解析、筛选方案、筛选结果和面试题绑定不可变输入版本。
- AI 只提供建议；最终业务记录和招聘决策需要人工确认。

## 3. 可开发临时默认

| 决策 | Phase 1 默认 | 实现约束 |
|---|---|---|
| 试用额度 | 100 CNY，配置化 | 使用账本 Grant，不在页面或领域中散落常量 |
| JD 计费 | 按次 | 金额配置化，Phase 3 前确认 |
| 简历筛选计费 | 按成功处理的简历份数 | Phase 5 前确认单价与基础费 |
| 面试题计费 | 按候选人题目包 | Phase 6 前确认单价 |
| 部分成功 | 只结算成功业务单位 | 账本保留补偿能力 |
| 失败/取消 | 未产生成功业务结果时释放预占 | Provider 基础费政策后续确认 |
| 缺失信息 | 标记 Unknown，不自动判不符合 | 筛选规则可配置 |
| 高分自动出题 | 禁止 | 必须选择候选人并确认费用 |
| 上传格式 | PDF、DOC、DOCX | 服务端以 MIME/Magic Bytes 为准 |
| 单文件/批量上限 | 20 MiB / 50 files | 配置化，Phase 4 前压测确认 |
| OCR | P0 先识别扫描件并提示；OCR Provider 后定 | 不把 OCR 写进 Java 领域层 |
| 重复候选人 | 提示但不自动合并 | Phase 4 前确认去重字段 |
| PII Reveal | 仅当前组织管理员 | 服务端授权并审计 |
| 语义召回 | 暂不在 Phase 1 实现 | Phase 5/伙伴联调前确定归属 |
| 登录态 | 短时 Access + HttpOnly Refresh/Session | Phase 2 前冻结具体方案 |
| AI Webhook | 时间戳 + Key ID + Body HMAC | 伙伴联调前冻结算法和轮换 |

## 4. Phase 1 接口冻结范围

- 业务 API 使用 `/api/v1`。
- 统一错误至少包含 `code`、安全 `message`、`request_id`。
- 关键创建和付费命令要求 `Idempotency-Key`。
- AI Contract Draft V0.1 的公共上下文、Task 状态、Webhook Envelope、Usage 和错误码作为 Mock 依据。
- `business_task_id` 与 `ai_task_id` 永远分开并映射。
- Mock 和真实 Adapter 共享同一业务 Port 和契约校验。

## 5. 后续阶段 Gate

- Phase 2 前：验证码服务、登录态和试用额度最终政策。
- Phase 4 前：文件限制、安全扫描、OCR、候选人去重、数据保留。
- Phase 5 前：筛选维度约束、单价、部分成功和重试收费。
- Phase 6 前：面试题价格和编辑/版本规则。
- Phase 8 前：伙伴鉴权、Webhook、SLA、批量、文件保留、Usage 和版本兼容。

## 6. Phase 0 完成判定

- MVP、双方边界、核心流程、数据模型、技术栈和质量门槛已有文档。
- AI Platform OpenAPI/JSON Schema 初稿进入本地项目。
- 未决事项均有临时默认或后续 Gate，不由开发者隐式决定。
- Phase 1 可以在不等待伙伴实现的情况下启动。

