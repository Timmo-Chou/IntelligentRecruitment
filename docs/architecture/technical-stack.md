# 第一阶段技术栈设计

状态：待项目确认后采用  
范围：① Web 客户端、② AI 招聘业务服务  
不包含：伙伴负责的 AI Platform、模型网关、MCP/Provider 接入实现

## 1. 选型目标

第一阶段优先满足：快速构建 P0、清晰的前后端边界、可靠处理简历文件和异步任务、可用 Mock 独立开发、未来低成本接入伙伴 AI Platform。

采用“前后端分离的模块化单体 + 异步 Worker”，暂不拆业务微服务。

```text
Web Client
    ↓ REST / SSE
AI Recruitment Business Service
    ├── PostgreSQL
    ├── Redis
    ├── RabbitMQ / Spring Worker
    ├── S3-compatible Object Storage
    └── AIPlatformClient
            ├── Mock adapter（第一阶段）
            └── Partner HTTP adapter（联调阶段）
```

## 2. 客户端

| 类别 | 选择 | 用途 |
|---|---|---|
| Web 框架 | Next.js App Router | 路由、布局、服务端能力和生产构建 |
| UI 框架 | React + TypeScript strict | 类型安全的组件开发 |
| 样式 | Tailwind CSS + CSS Variables | 落地蓝绿视觉 Token 和响应式布局 |
| 无障碍原语 | Radix UI | Dialog、Dropdown、Popover、Tabs 等交互基础 |
| 组件策略 | 项目内维护的 shadcn 风格组件 | 保有源码控制，不绑定黑盒组件库 |
| 服务端状态 | TanStack Query | 查询、缓存、失效、轮询和 Mutation 生命周期 |
| 表单 | React Hook Form + Zod | 高性能表单和边界校验 |
| 本地状态 | React state；必要时 Zustand | 仅保存临时 UI 状态，不复制服务端业务对象 |
| 流式输出 | `fetch` streaming 或 SSE | AI 对话和生成过程展示 |
| 单元/组件测试 | Vitest + React Testing Library + MSW | 组件行为和 API 场景 |
| 端到端测试 | Playwright | P0 用户闭环 |

### 客户端原则

- 浏览器只调用招聘业务服务，不直连 AI Platform。
- API 类型优先由业务服务 OpenAPI 生成。
- AI 流式文本是临时展示，服务端持久化的结构化结果才是业务事实。
- 长任务状态来自服务端，刷新页面后必须能恢复。
- 不在 Local Storage、URL、埋点和错误监控中保存候选人敏感信息。

## 3. AI 招聘业务服务

| 类别 | 选择 | 用途 |
|---|---|---|
| 语言 | Java 21 LTS | 企业业务、事务、并发和长期维护 |
| 应用框架 | Spring Boot | REST、配置、事务、可观测性和生产运行 |
| Web | Spring MVC | 事务型业务 API；不为 SSE 强制全链路响应式 |
| 外部 HTTP/流式 | Spring WebClient | 调用 AI Platform、消费 SSE/流式响应 |
| 认证授权 | Spring Security | 登录态、组织权限、方法和资源级授权 |
| 数据校验 | Jakarta Bean Validation + JSON Schema | 业务 API 与 AI 契约边界校验 |
| ORM | Spring Data JPA | 聚合持久化和常规业务查询 |
| 复杂查询 | jOOQ | 人才检索、统计、账本对账等复杂 SQL |
| 数据迁移 | Flyway | 版本化 Schema 迁移 |
| 数据库 | PostgreSQL | 业务事实、版本、账本、审计 |
| 向量能力 | pgvector（按需启用） | 若最终确认由业务侧持有语义召回 |
| 缓存 | Redis | 缓存、速率控制和短期协调，不作事实源 |
| 消息队列 | RabbitMQ | 可靠投递、重试、死信和异步任务协调 |
| 异步 Worker | Spring Boot Worker + RabbitMQ Consumer | AI 任务投递、回调后处理和补偿任务 |
| 文件存储 | S3-compatible Object Storage | 原始简历和受控下载；本地可用 MinIO |
| API 文档 | springdoc-openapi | 生成业务服务 OpenAPI |
| 测试 | JUnit 5 + Spring Boot Test + Testcontainers | 领域、API、数据库、队列和契约测试 |
| 架构约束 | ArchUnit | 防止模块和分层依赖漂移 |
| 构建管理 | Maven Wrapper | 可重复构建和依赖锁定 |

### 服务端原则

- 模块化单体，不以 Agent 名称拆微服务。
- 招聘业务服务拥有用户、企业、职位、候选人、任务、结果和客户账单。
- 对 AI Platform 只依赖内部 `AIPlatformClient` 接口。
- Mock 和真实 HTTP Adapter 可通过配置切换，领域代码无条件分支。
- 企业招聘业务数据同时包含 `company_id + workspace_id`；个人业务数据使用 `workspace_id` 且 `company_id = NULL`。
- JD、简历解析、筛选方案、筛选结果和面试题均绑定版本。
- 金额使用最小货币单位整数或精确 Decimal，账本只追加不覆盖。
- 原始简历不进入数据库大字段，使用对象存储及短效授权地址。

## 4. API 与集成协议

### 客户端到业务服务

- REST JSON：业务 CRUD、任务命令、列表和结果。
- SSE/流式 HTTP：需要逐步显示的 AI 内容。
- 第一阶段任务进度使用 TanStack Query 轮询；业务量和实时性证明需要后再考虑 WebSocket。
- `/api/v1` 版本前缀。
- 任务创建、付费确认和重试要求 `Idempotency-Key`。

### 业务服务到 AI Platform

- 版本化 REST/JSON Schema。
- 短生成可同步或流式；简历解析、批量筛选等使用异步任务。
- AI Platform 通过签名 Webhook 回调；业务服务保留轮询兜底接口。
- 文件通过短效签名 URL 或双方确认的受控文件接口传递。
- AI Platform 返回供应商用量；业务服务独立完成用户定价和账本结算。

## 5. 数据与基础设施

### PostgreSQL

保存：

- 用户、企业、成员关系
- 职位与 JD 版本
- 候选人、简历元数据与解析版本
- 招聘任务、对话、消息
- 筛选方案、运行和结果
- 面试题包与问题
- AI 运行映射和用量记录
- 钱包投影、不可变账本、幂等记录
- Outbox 和审计日志

### Redis 与 RabbitMQ

Redis 用于缓存、限流和短期协调；RabbitMQ 用于可恢复的异步投递、重试和死信。两者都不作为业务事实源，任务最终状态必须落 PostgreSQL。

### 对象存储

- 开发环境：MinIO 或兼容服务。
- 生产环境：云厂商 S3-compatible 存储。
- 使用不可猜测 Object Key、服务端鉴权和短效签名链接。
- 上传后经过格式、Magic Bytes、大小和恶意文件检测再进入解析。

## 6. 工程组织

建议 Monorepo：

```text
IntelligentRecruitment/
├── apps/
│   └── web/                 Next.js
├── services/
│   └── recruitment-service/ Java/Spring Boot 模块化单体
│       ├── src/main/java/   按业务 Feature 分包
│       ├── src/main/resources/
│       └── src/test/java/
├── packages/
│   ├── design-tokens/
│   └── api-contracts/
├── database/
│   └── migrations/
├── tests/
│   ├── contract/
│   └── e2e/
├── docs/
├── skills/
└── infra/
```

- JavaScript 工作区使用 pnpm。
- Java 服务使用项目内 Maven Wrapper，CI 和本地不依赖全局 Maven 版本。
- API 与 Worker 复用同一业务代码和领域模块，通过独立启动入口/Profile 部署；不要复制两套业务实现。
- 不为了统一命令而引入复杂 Monorepo 编排器；先使用根目录任务命令或 Makefile。
- 本地依赖使用 Docker Compose；前端和 Java 服务可直接在宿主机运行以提升调试体验。

## 7. CI/CD 与可观测性

第一阶段 CI 至少包括：

- TypeScript 类型检查、Lint、组件测试和生产构建。
- Java 编译、Checkstyle/Spotless、静态分析、JUnit、ArchUnit 和 Flyway 迁移检查。
- OpenAPI/AI Contract 变更检查。
- Testcontainers 驱动的 PostgreSQL、Redis、RabbitMQ、Object Storage 集成测试。
- P0 Playwright 冒烟流程。
- Secret、依赖和容器基础扫描。

日志采用结构化 JSON，并贯穿：

`request_id`、`trace_id`、`company_id`、`workspace_id`、`business_task_id`、`ai_task_id`、账本业务引用。

禁止记录简历正文、完整联系方式、凭证、签名 URL 和包含 PII 的完整 Prompt。

## 8. 暂不采用

- 业务微服务：P0 的组织和运维成本高于收益。
- GraphQL：当前 REST 资源和命令边界更清晰。
- Kubernetes：第一阶段没有足够部署复杂度支撑它。
- 浏览器直连 AI Platform：会破坏权限、数据、计费和审计边界。
- 将 AI Platform SDK/DTO 直接散落在业务模块中：必须经过 Adapter。
- 自建通用 Agent Runtime、Prompt Registry、MCP Gateway：属于伙伴团队范围。
- 默认使用 Elasticsearch/OpenSearch：PostgreSQL 搜索和可选 pgvector 足以验证 P0；达到明确规模和召回需求后再评估。

## 9. 开发前必须确认的技术决策

1. 生产部署云和对象存储供应商。
2. 手机验证码服务商和认证 Session/Token 方案。
3. 语义召回归业务侧还是 AI Platform。
4. 简历 OCR、恶意文件检测由谁提供。
5. AI Platform 的服务鉴权、Webhook 签名和 SLA。
6. P0 文件大小、批量数量及任务并发限制。
7. 用户计费的失败、取消、部分成功和重试规则。
8. 候选人数据保留期、删除范围和 PII 查看角色。
9. 是否需要中国境内部署及外部模型数据传输限制。
10. 前后端与 AI 契约的版本兼容和弃用周期。
