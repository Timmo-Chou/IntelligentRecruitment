# 第一阶段系统架构

## 1. 系统上下文

```text
招聘用户
   ↓
Web Client
   ↓ REST / SSE
AI Recruitment Business Service
   ├── PostgreSQL
   ├── Redis
   ├── RabbitMQ
   ├── Spring Boot Worker
   ├── Object Storage
   └── AIPlatformClient
         ├── Mock Adapter（我方第一阶段）
         └── HTTP Adapter（伙伴联调）
                 ↓
           Partner AI Platform
                 ↓
       LLM / MCP / API / 自研能力
```

## 2. 客户端架构

### 2.1 主要层次

- App/Route：页面和布局。
- Feature：招聘业务模块及其查询、表单和组件。
- Shared UI：无业务含义的通用组件。
- Business Components：跨页面复用的招聘语义组件。
- API Client：只访问业务服务。
- Server State：TanStack Query。
- UI State：组件状态、URL，必要时少量 Zustand。

### 2.2 前端边界

- 不保存业务真相。
- 不计算最终余额或业务价格。
- 不决定用户是否有权查看 PII。
- 不直接重放付费请求。
- 不信任客户端文件校验作为最终结果。

## 3. 业务服务架构

采用模块化单体：

```text
Identity
Organizations
Jobs
Candidates
Recruitment Tasks
Conversations
Screening
Interviews
Billing
Audit
```

每个模块内部可包含：

- API/Transport。
- Application Service/Command Handler。
- Domain Model/Policy。
- Repository/Persistence。
- Schema 和测试。

跨模块使用明确的应用接口或领域事件，不通过随意读取对方内部表实现业务逻辑。

## 4. AI Platform Adapter

业务服务定义中立接口：

```text
AIPlatformClient
├── stream_requirement_chat
├── generate_jd
├── parse_resumes
├── generate_screening_plan
├── start_screening
├── generate_interview_kits
├── get_task
├── cancel_task
└── retry_task
```

实现：

- `MockAIPlatformClient`：确定性数据、延迟、流、进度、失败、部分成功、非法响应、重复/乱序回调。
- `HttpAIPlatformClient`：服务鉴权、超时、重试、Schema、伙伴 DTO 转换。

领域层不得依赖伙伴 DTO、URL 或供应商错误格式。

## 5. 同步、流式和异步选择

| 场景 | 模式 |
|---|---|
| 普通业务 CRUD | REST 同步 |
| AI 对话和 JD 逐步生成 | SSE/流式 HTTP |
| 短筛选方案生成 | 同步或短异步，按伙伴 SLA 确认 |
| 批量简历解析 | 异步任务 |
| 批量人岗匹配 | 异步任务 |
| 批量面试题生成 | 异步任务；单候选人可短异步 |
| AI Platform 状态通知 | 签名 Webhook，轮询兜底 |

客户端第一阶段通过业务服务轮询普通异步任务；只有确有实时价值的内容使用流式连接。

## 6. 可靠性架构

### 6.1 Outbox

创建业务任务、费用预占和待发送 AI 命令时，在同一 PostgreSQL 事务中写入 Outbox。Worker 可靠投递，避免数据库提交成功但 AI 调用丢失。

### 6.2 幂等

- 客户端关键命令带 `Idempotency-Key`。
- 业务服务按组织、用户、操作类型和 Key 去重。
- 业务服务到 AI Platform 的创建请求携带稳定幂等键。
- 回调按 `event_id` 去重。
- 账本按唯一业务引用防重复预占和结算。

### 6.3 状态单调

终态不回退。回调携带序号或发生时间；迟到进度不得覆盖完成/取消。取消与完成竞态按照服务器记录的规则处理，不能依赖客户端最后看到的状态。

### 6.4 部分成功

批量任务拥有父级状态和项目级状态。成功项目持久化，失败项目可重试，不使用“全有或全无”的数据库事务包住外部 AI 工作。

## 7. 数据存储

### PostgreSQL

业务事实、版本、任务、结果、账本、幂等、Outbox 和审计。

### Redis

缓存、限流和短期协调，不作为最终任务状态或余额事实源。

### RabbitMQ

承载 Outbox 投递后的异步命令、重试和死信。消费者由 Spring Boot Worker 提供；消息队列不是业务状态事实源。

### 对象存储

原始简历和受控导出。对象 Key 不包含姓名/手机号；签名 URL 不持久化。

### pgvector

仅在确认语义召回由我方负责后启用。若由 AI Platform 负责，则业务服务仍控制候选人范围和业务数据所有权。

## 8. 安全架构

- 服务端执行最终认证、授权和组织隔离。
- 敏感字段加密或使用受控密钥服务。
- PII 默认脱敏；Reveal、下载、导出、删除进入审计。
- 对外 AI 数据按能力、供应商和用途最小化。
- 服务间使用独立认证，不转发浏览器 Token 作为伙伴最终授权。
- Webhook 校验原始 Body、签名、时间窗口和事件去重。
- 日志不包含简历正文、完整联系方式、Secret、签名 URL 和含 PII 的完整 Prompt。

## 9. 可观测性

贯穿字段：

- `request_id`
- `trace_id`
- `company_id`（个人场景为空）
- `workspace_id`
- `actor_id`
- `business_task_id`
- `business_operation_id`
- `ai_task_id`
- `ledger_reference`

核心指标：API 延迟/错误率、队列积压、任务成功率/耗时、回调验签失败、解析失败率、筛选项目失败率、账本对账异常和对象存储错误。

## 10. 部署基线

开发环境：

- Web、API、Worker 本地运行。
- Docker Compose 提供 PostgreSQL、Redis、RabbitMQ、MinIO。
- 默认使用 Mock AI Platform。

生产初期：

- Web、API、Worker 独立进程/容器。
- 托管 PostgreSQL、Redis、RabbitMQ 和对象存储。
- AI Adapter 通过配置逐能力切换伙伴环境。
- 暂不要求 Kubernetes；根据实际规模和运维平台决定。
