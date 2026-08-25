# Phase 0 / Phase 1 完成记录

版本：V0.1  
完成日期：2026-08-21  
状态：已完成，可进入 Phase 2

## 1. Phase 0 交付

- 已冻结首阶段可开发产品规则、临时默认值、双方边界和后续门禁。
- 已形成 AI Platform OpenAPI V0.1，以及 Task、Request Context、File Reference、Webhook Event、Error JSON Schema。
- 对尚未由伙伴确认的协议均保留为 `0.1.0` 草案，不视为对方已经接受。
- 浏览器只访问业务服务；业务服务只通过 `AIPlatformClient` Port 使用统一 AI 能力。

主要入口：

- `docs/decisions/phase-0-development-freeze.md`
- `contracts/ai-platform/openapi.yaml`
- `contracts/ai-platform/schemas/`

## 2. Phase 1 交付

### 前端

- Next.js App Router、React、严格 TypeScript、pnpm workspace。
- Tailwind/CSS Token、Radix UI、TanStack Query、React Hook Form、Zod、MSW 基础依赖。
- 企业招聘应用壳、首页骨架、Loading、全局错误边界、统一 API Client 和基础 Button。
- ESLint、TypeScript、Vitest、Testing Library；生产构建固定使用 webpack，避免当前 macOS 环境下 Turbopack 内部端口限制。

### 服务端

- Java 21 目标、Spring Boot、Maven Wrapper、模块化单体分包。
- Spring MVC、Security、Validation、JPA、jOOQ、Flyway、PostgreSQL、Redis、RabbitMQ、S3/MinIO。
- API 与 Worker 两个启动入口、统一错误格式、Request ID、健康检查。
- 幂等表、Outbox 表、异步探针链路，以及 `AIPlatformClient` Port 和 Mock Adapter。
- JUnit 5、ArchUnit、Testcontainers 依赖与基础测试。

### 工程基础

- PostgreSQL、Redis、RabbitMQ、MinIO 的 Docker Compose。
- 前后端及契约检查的 CI 工作流。
- 本地环境变量模板、Makefile、启动与验证说明。

## 3. 验证结果

| 检查项 | 结果 |
|---|---|
| 前端 ESLint | 通过 |
| 前端 TypeScript | 通过 |
| 前端 Vitest | 1/1 通过 |
| Next.js 生产构建 | 通过 |
| Maven 单元/架构测试 | 2/2 通过 |
| JSON Schema 语法 | 5/5 通过 |
| OpenAPI YAML 语法 | 通过 |
| Docker Compose 配置 | 通过 |
| Flyway V1 首次迁移及重复启动 | 通过 |
| API → PostgreSQL → RabbitMQ → Worker → 状态查询 | 通过，`queued` 最终变为 `completed` |
| Mock AI 幂等任务 | 通过 |

验证结束后已停止项目容器，Docker 数据卷保留。

## 4. 明确不属于 Phase 1 的内容

- 登录、组织、正式 RBAC、额度和账本业务在 Phase 2。
- JD、简历、筛选、面试题等正式业务流程在 Phase 3–6。
- 当前 Outbox 只建立表和架构基线；可靠发布器与业务事件接入在出现首个正式异步业务时完成。
- 当前 AI Adapter 为 Mock；伙伴确认 V0.1 后再实现 HTTP Adapter 并联调。
- 本机使用 JDK 25 执行了 `--release 21` 构建；CI 固定使用 JDK 21。

## 5. 进入 Phase 2 前仍需确认

- 身份认证供应方式，以及 Access Token/HttpOnly Cookie 的最终组合。
- 首个真实试用组织的创建和管理员初始化方式。
- 试用额度政策已在 Phase 2 决策中冻结为：每个新组织一次性 100 元，自发放起 90 天过期。
- 伙伴对 AI Contract V0.1 的评审窗口；不阻塞 Phase 2，但阻塞真实 Adapter 上线。
