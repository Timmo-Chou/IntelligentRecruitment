# Intelligent Recruitment

AI 智能招聘 MVP。当前已完成工程、身份、Company/Workspace 租户权限、账本，以及 JD、人才库、简历筛选、简历解析和面试题库的业务闭环。JD 与简历解析通过事务性 Outbox 进入 Worker，前端使用可恢复的 SSE/轮询展示进度。运行时 AI 能力统一调用 DeepSeek；模型不可用或输出不合约时任务明确失败并支持重试，详见 [AI 运行现状与 Mock 退役方案](docs/architecture/ai-runtime-and-mock-retirement.md)。

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

### 阿里云短信验证码

默认使用本地通道：`VERIFICATION_CODE_PROVIDER=local`，验证码为 `123456`，且仅在 `EXPOSE_MOCK_CODE=true` 时随挑战接口响应返回。

正式环境设为 `VERIFICATION_CODE_PROVIDER=aliyun`，并通过环境变量注入 `ALIYUN_SMS_ACCESS_KEY_ID`、`ALIYUN_SMS_ACCESS_KEY_SECRET`、`ALIYUN_SMS_SIGN_NAME`、`ALIYUN_SMS_TEMPLATE_CODE`。模板必须使用 `code` 变量，例如“您的验证码是${code}，5分钟内有效”。正式通道由后端随机生成六码验证码，调用阿里云国内短信 `SendSms`，不会向浏览器返回明文验证码。

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
- `/recruitment`：招聘任务、异步 JD 生成、SSE 进度、草稿编辑确认。
- `/jobs`：Workspace 职位库和不可变 JD 版本。
- `/candidates`：简历上传、基础规则解析、人才库与 PII Reveal。
- `/screening`：筛选方案、费用确认、异步 AI 匹配、部分结算和失败重试。
- `/interviews`：已发布面试题包的 JD、人才、胜任力、匹配总结与题目结果查看。

账本服务已包含幂等试用发放、最早到期额度优先冻结、全部/部分结算、失败释放、到期处理和平台人工调整。结算与人工调整只开放受保护的平台/内部接口，浏览器不能自行提交实际结算金额。

平台运营功能位于 `apps/admin`，本地默认访问 `http://localhost:3001`。平台审核也保留以下受保护 API：

- `POST /api/v1/platform/personal-verifications/{userId}/approve`
- `POST /api/v1/platform/company-verifications/{requestId}/approve`
- `POST /api/v1/platform/company-verifications/{requestId}/reject`
- `POST /api/v1/platform/company-membership-applications/{applicationId}/approve`
- `POST /api/v1/platform/company-membership-applications/{applicationId}/reject`

## 校验

```bash
make check
```

JD 生成采用异步提交：`POST .../jd-runs` 成功只表示已预占固定费用并进入队列；Worker 完成后写入 JD 草稿。浏览器通过 `GET .../jd-runs/events` 获取 SSE 事件，并使用 `Last-Event-ID` 在断线或刷新后补发遗漏的 Delta。MVP 不创建独立 JD 报价单；按钮会明确显示固定单价，“确认生成”即表示用户确认预占，失败时全额释放。

详细阶段计划见 [开发阶段文档](docs/baseline/09-development-phases.md)，Phase 2 权限与数据边界见 [Phase 2 完整方案](docs/architecture/phase-2-identity-tenancy-and-isolation.md)。
