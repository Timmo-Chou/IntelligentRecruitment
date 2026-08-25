# Intelligent Recruitment

AI 智能招聘产品第一阶段工程。当前完成 Phase 0、Phase 1，并已实现 Phase 2 的身份、Company/Workspace 租户权限、试用额度、账本和正式产品框架。JD、简历和筛选等招聘业务将在后续 Phase 接入。

## 目录

```text
apps/web/                         Next.js 客户端
services/recruitment-service/     Java 21 / Spring Boot 业务服务
contracts/ai-platform/            AI Platform OpenAPI 与 JSON Schema
infra/                            本地基础设施
docs/                             产品、架构和开发阶段
skills/                           项目核心 Skill
```

## 本地工具

- Node.js 22+ 和 pnpm 11。
- Java 21+；项目以 Java 21 为编译目标。
- Docker 与 Docker Compose。

## 启动基础设施

```bash
make infra-up
```

本地端口：

- PostgreSQL `5432`
- Redis `6379`
- RabbitMQ `5672`，管理台 `15672`
- MinIO `9000`，控制台 `9001`

本地凭证只用于开发，见 [.env.example](.env.example)。

## 启动服务端

```bash
cd services/recruitment-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

- 健康检查：`GET http://localhost:8080/actuator/health`
- 系统探针：`GET http://localhost:8080/api/v1/system/ping`
- 本地异步链路探针：`POST /api/v1/internal/foundation/probes`
- Phase 2 本地 Mock 验证码：`123456`（仅 `local` Profile 会在验证码响应中返回）。
- Phase 2 本地平台审核 Key：`phase2-local-admin`，通过 `X-Platform-Admin-Key` 请求头传递。

异步链路探针只在 `local/test` Profile 开放，用于验证 PostgreSQL → RabbitMQ → Worker → PostgreSQL。

## 启动客户端

```bash
pnpm install
pnpm dev:web
```

访问 `http://localhost:3000`。

主要页面：

- `/login`：手机号验证码登录/注册。
- `/onboarding`：创建个人 Workspace、企业认证、认领已有企业或加入企业。
- `/settings`：企业与 Workspace 治理、实名认证、成员邀请和全部设备退出。
- `/billing`：Workspace 余额、90天额度批次和不可变账本。

账本服务已包含幂等试用发放、最早到期额度优先冻结、全部/部分结算、失败释放、到期处理和平台人工调整。结算与人工调整只开放受保护的平台/内部接口，浏览器不能自行提交实际结算金额。

平台审核暂不提供运营后台，Phase 2 使用受保护 API：

- `POST /api/v1/platform/personal-verifications/{userId}/approve`
- `POST /api/v1/platform/company-verifications/{requestId}/approve`
- `POST /api/v1/platform/company-verifications/{requestId}/reject`
- `POST /api/v1/platform/company-membership-applications/{applicationId}/approve`
- `POST /api/v1/platform/company-membership-applications/{applicationId}/reject`

## 校验

```bash
make check
```

详细阶段计划见 [开发阶段文档](docs/baseline/09-development-phases.md)，Phase 2 权限与数据边界见 [Phase 2 完整方案](docs/architecture/phase-2-identity-tenancy-and-isolation.md)。
