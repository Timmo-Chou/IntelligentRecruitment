# Agent 编排契约 V1

状态：**Draft V1**  
范围：P0 合同先行；不改变现有业务 API 或运行时实现。

## 1. 决策

采用两层协作，而不是让 AI Agent 直接执行业务命令：

```text
Web
  → 招聘业务服务：Flow Coordinator / Policy Gate
  → AI Platform：单入口 Orchestration Agent / Skill Runtime / Tool Runtime
  → 模型、MCP、供应商能力
```

- 招聘业务服务拥有权限、租户范围、业务状态机、版本、报价、用户确认、账本和正式数据写入。
- AI Platform 拥有自由对话的意图路由、Skill 选择、Prompt/模型策略和受控 Tool 执行。
- RouteDecision 是建议，永远不是授权。任何收费、异步、写正式结果的能力都必须携带业务服务生成的 `PolicyDecision(decision=allow)`。

## 2. 五个核心对象

| 对象 | 事实源 | 用途 | 不得承担的职责 |
|---|---|---|---|
| `RouteDecision` | AI Platform | 将自由文本路由为能力建议、缺失信息和下一步建议 | 授权、扣费、启动任务、扩大数据访问范围 |
| `ExecutionContext` | 业务服务 | 为一次已获准执行冻结最小上下文、版本引用和数据处理策略 | 携带浏览器 Token、任意数据库查询权限、长期文件 URL |
| `SkillManifest` | AI Platform | 注册 Skill 的版本、输入输出 Schema、Prompt/模型策略、工具白名单 | 替代产品规则或用户授权 |
| `PolicyDecision` | 业务服务 | 记录确定性授权/阻断/待确认结论 | 由模型生成或由 AI Platform 修改 |
| `StructuredResult` | AI Platform | 返回可验证的能力结果、证据、告警、缺失信息和生成来源 | 直接写业务数据库或直接结算客户账单 |

机器可读定义见 `contracts/ai-platform/schemas/`：

- `route-decision.schema.json`
- `route-decision-v2.schema.json`（临时 AI Platform 的当前路由契约，新增二级意图与 `operation`）
- `execution-context.schema.json`
- `skill-manifest.schema.json`
- `policy-decision.schema.json`
- `structured-result.schema.json`

## 3. P0 交互与授权状态机

```text
用户文本 / 显式能力入口
  → RouteDecision（显式入口可跳过意图识别）
  → 业务服务校验前置条件
  → PolicyDecision: deny | require_user_confirmation | allow
  → [待确认时] 用户确认筛选方案、候选人范围或报价
  → 新 PolicyDecision: allow
  → ExecutionContext
  → AI Platform Skill 执行
  → StructuredResult
  → 业务服务校验、持久化、结算或释放
```

业务校验不是用户确认。只有 `require_user_confirmation` 时才展示确认界面。

| 场景 | 自动校验 | 用户必须确认 |
|---|---|---|
| JD 生成 | 工作空间权限、固定价格、余额 | 本次固定价生成；生成后确认 JD 版本 |
| 简历解析 | 文件授权、格式/安全状态、解析范围 | 上传和解析授权 |
| 筛选方案 | 已确认 JD、规则合法性、敏感属性 | 方案维度、权重、必须/排除项与缺失规则 |
| 候选人筛选 | 已确认 JD/方案、候选版本、余额 | 候选范围与不可变报价 |
| 面试题 | 显式候选人、关联版本、余额 | 候选人范围与不可变报价 |

## 4. 路由规则

1. 明确按钮或 API Capability 优先，直接进入业务校验；不调用意图识别。
2. 只有自由对话才调用 `/agent-routes`。
3. `RouteDecision.kind=route` 后，业务服务重新从当前 Workspace 查询资源；不得信任模型返回的任意资源 ID。
4. `confidence` 仅影响是否追问，不能绕过确认或策略校验。
5. 不支持的意图返回 `unsupported`；信息不全返回 `clarify`，不得猜测候选人范围或岗位版本。

### 4.1 RouteDecision V2：意图操作与文件前置条件

`route-decision.schema.json` 保留为兼容旧调用方的 V1 契约。临时 AI Platform 的
`/agent-routes` 已使用 `route-decision-v2.schema.json`：它在保留 V1 字段的基础上，
增加二级意图与动作语义。V2 仍只是路由建议，不能直接触发业务执行。

V2 只补充两个语义，不扩大 AI Platform 的授权边界：

| 字段/枚举 | 定义 | 约束 |
|---|---|---|
| `secondary_intent` | 一级能力下的具体业务场景，例如 `create_another_jd`、`create_screening_run`、`publish_to_pincaimao` | 仅 `kind=route` 时必填，必须命中 AI Platform 注册的意图目录。 |
| `operation` | 用户希望进行的业务操作：`create`、`revise`、`continue`、`inspect`、`confirm`、`cancel`、`retry` | 仅 `kind=route` 时必填；它是意图建议，不是执行指令。业务服务仍须独立决定是否允许创建、修改、执行或收费。 |
| `missing_inputs.resume_file` | 当前请求需要用户提供原始简历文件，但尚未提供可解析文件 | 与 `resume_parse_version` 区分：前者表示“尚未上传文件”，后者表示“已上传但缺少可用解析版本”。 |

`operation` 的推荐适用范围：

- `jd_generation`：`create` 用于新职位 JD，`revise` 用于当前 JD 修改，`continue` 用于继续收集岗位需求。
- `screening_plan_generation`：`create` 或 `revise`。
- `resume_parsing`、`candidate_screening`、`interview_kit_generation`：通常为 `create`。
- `requirement_chat`：通常为 `continue`；`task_assistance`：通常为 `inspect`。
- 对外部招聘动作：`candidate_sourcing`、`job_distribution`、`candidate_outreach` 可以被识别，
  但只能在业务服务生成 `PolicyDecision=allow` 和 `ExecutionContext` 后调用 MCP 工具。

临时 AI Platform 的完整路由注册表实现位于：

`services/recruitment-service/src/main/java/com/intelligentrecruitment/aiplatform/application/RecruitmentAssistantIntentCatalog.java`

该注册表是当前表格的机器可执行版本，包含二级意图、处理类型、Skill ID、聘才猫 MCP
工具建议名、是否要求用户确认和缺失输入。聘才猫工具仅是白名单元数据；当前实现不保存
聘才猫凭据，也不会从自由对话直接调用外部服务。

`suggested_next_action` 在 V2 中保持 V1 枚举，以避免扩大本次变更范围。当
`missing_inputs` 包含 `resume_file` 时，当前统一返回 `collect_requirement`；后续如需
专门的上传界面，再以独立 V3 增加 `upload_resume`，不得复用或改变既有枚举含义。

示例：

```json
{
  "decision_id": "rd_01",
  "kind": "route",
  "capability": "jd_generation",
  "operation": "create",
  "confidence": 0.96,
  "requires_policy_check": true,
  "missing_inputs": [],
  "clarification": null,
  "suggested_next_action": "collect_requirement",
  "created_at": "2026-09-02T00:00:00Z"
}
```

```json
{
  "decision_id": "rd_02",
  "kind": "clarify",
  "capability": null,
  "operation": null,
  "confidence": 0.41,
  "requires_policy_check": true,
  "missing_inputs": ["resume_file", "user_clarification"],
  "clarification": "请先上传需要解析的简历，并说明是否需要关联某个职位。",
  "suggested_next_action": "collect_requirement",
  "created_at": "2026-09-02T00:00:00Z"
}
```

## 5. ExecutionContext 与数据最小化

`ExecutionContext` 是 AI Platform 可使用数据的全部边界：

- `request_context.workspace_id`、`company_id`、`actor_id`、`business_task_id` 用于关联和审计，不授予查询我方数据的能力。
- `input_versions` 只能列出已冻结的业务引用和内容哈希；具体内容由业务服务按 Skill 数据策略投影。
- PII、简历正文与短效文件引用仅在该次执行需要时提供；`data_handling.log_content=false` 是强制规则。
- `PolicyDecision` 必须为 `allow`，且其中 `workspace_id`、`company_id`、`actor_id` 与 `request_context` 一致。

## 6. SkillManifest 与 Tool 约束

每个活跃 Skill 都必须有一个不可变 Manifest 版本，并声明：

- Capability、运行模式（同步、流式、异步）。
- 输入/输出 Schema 引用。
- Prompt 版本与模型策略版本。
- 可调用 Tool ID 白名单、超时和最大尝试次数。
- 数据策略版本。

MCP 是 Tool Runtime 内的连接协议，不是业务授权机制。任何 Tool 均不得直接读写招聘业务数据库、客户账本或用浏览器 Token 调用。

## 7. StructuredResult 的写入规则

业务服务在写入业务事实前必须：

1. 验证 Result Envelope 和 Capability 专属输出 Schema。
2. 验证 `execution_id`、能力、输入版本和本地 AI Task 映射。
3. 验证分数范围、枚举、必填解释和 `evidence.source_ref` 属于本次 `ExecutionContext.input_versions`。
4. 拒绝跨 Workspace、未知引用、缺少证据或版本不匹配的结果。
5. 将 `skill/prompt/model policy` 版本作为可审计元数据保存；不保存完整 PII Prompt 或 Tool 输入日志。

供应商用量属于 `StructuredResult.usage`，仅供技术对账；客户收费仍由业务服务按报价与成功业务单位计算。

## 8. OpenAPI 入口

- `POST /agent-routes`：自由对话路由，只返回 `RouteDecision`。
- `POST /capability-executions`：业务服务以 `ExecutionContext` 请求已授权的能力执行。
- `GET /tasks/{aiTaskId}/result`：获取最终 `StructuredResult`；异步任务也可在已验签的回调中携带同一对象。

现有 JD、解析、筛选、面试题的能力接口可在兼容期内保留；其 V1 请求在业务服务内部转换为 `ExecutionContext`。不能由浏览器直接调用上述 AI Platform 入口。

## 9. 验收门槛

- 所有五种对象能通过 JSON Schema 校验，且外部引用可解析。
- `RouteDecision` 无法作为执行请求；`PolicyDecision` 非 `allow` 时 `ExecutionContext` 不通过 Schema。
- 任意 Result 的证据引用、Workspace Scope 或输入版本不匹配时，业务服务拒绝落库。
- 每个 Capability 至少有一个活跃 `SkillManifest`；新增/变更 Skill、Prompt、模型策略或 Tool 白名单必须创建新版本。
- 对话路由、业务授权、AI 执行和业务结算均可用 `request_id`、`business_task_id`、`execution_id` 和 `ai_task_id` 关联追踪。

## 10. 临时 DeepSeek 验证适配器

开发环境默认使用 `AI_PLATFORM_MODE=mock`。在数据处理获批准的封闭测试中，可以启用临时 DeepSeek 适配器：

```text
AI_PLATFORM_MODE=deepseek
DEEPSEEK_API_KEY=由部署环境注入
DEEPSEEK_ALLOW_EXTERNAL_DATA=true
DEEPSEEK_MODEL=deepseek-v4-flash
```

- API Key 只能由运行环境注入，不能写入仓库、前端代码、日志或任务输入。
- 适配器当前仅支持自由文本路由与 JD 生成；简历解析、筛选和面试题仍保持 Mock，避免将候选人 PII 在未完成数据合规评审前发送给外部模型。
- `DEEPSEEK_ALLOW_EXTERNAL_DATA` 默认为 `false`；未显式开启时适配器拒绝所有外部调用。
- DeepSeek 输出必须使用 JSON 模式，并仍经 `StructuredResult` 和业务服务校验。正式 AI Platform 接入后，按 Capability 切换 Adapter，不以 DeepSeek 结果冒充正式平台结果。
