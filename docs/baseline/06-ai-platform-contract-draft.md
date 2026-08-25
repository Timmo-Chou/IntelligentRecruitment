# AI Platform 接口契约草案

版本：Draft V0.1  
状态：待伙伴评审，不代表最终实现

## 1. 目标

本草案定义 AI 招聘业务服务与伙伴 AI Platform 的稳定边界，使我方可以先通过 Mock 开发，伙伴后续按相同契约实现真实能力。

## 2. 基础约定

- Base Path：`/api/v1`（最终命名双方确认）。
- Content Type：`application/json`；文件使用受控引用。
- 时间：ISO 8601 UTC。
- 金额：最小货币单位整数，并携带币种。
- ID：双方均视对方 ID 为不透明字符串。
- 所有创建任务/计费用途请求要求幂等键。
- AI 输出使用版本化 JSON Schema。
- 服务鉴权、签名算法和密钥轮换待双方确认。

## 3. 公共请求上下文

```json
{
  "request_id": "req_01...",
  "trace_id": "trc_01...",
  "company_id": "cmp_01...",
  "workspace_id": "wsp_01...",
  "actor_id": "usr_01...",
  "business_task_id": "rt_01...",
  "idempotency_key": "workspace:operation:key",
  "locale": "zh-CN",
  "timezone": "Asia/Shanghai",
  "contract_version": "v1"
}
```

`workspace_id` 是业务隔离、审计和计费上下文；企业场景同时传 `company_id`，个人场景传 `null`。这些 ID 不表示 AI Platform 有权查询我方企业数据。

## 4. P0 能力目录

| 能力 | 建议接口 | 模式 | 结果 |
|---|---|---|---|
| 招聘需求对话 | `POST /conversations/stream` | 流式 | 文本 Delta、缺失字段、阶段 |
| JD 生成 | `POST /jd-generations` | 流式/异步 | JD 草稿、人才画像、警告 |
| 简历解析 | `POST /resume-parse-tasks` | 异步批量 | 文件级解析或错误 |
| 筛选方案生成 | `POST /screening-plan-generations` | 同步/短异步 | 维度、权重、规则、说明 |
| 候选人筛选 | `POST /screening-tasks` | 异步批量 | 候选人级解释结果 |
| 面试题生成 | `POST /interview-kit-tasks` | 同步/异步 | 候选人级题目包 |
| 任务查询 | `GET /tasks/{ai_task_id}` | 同步 | 状态、进度、错误 |
| 任务取消 | `POST /tasks/{ai_task_id}/cancel` | 同步命令 | 接受/冲突状态 |
| 任务重试 | 优先由我方创建新任务 | 异步 | 新 `ai_task_id` |

重试接口是否由平台提供可讨论，但历史 Attempt 必须保留。

## 5. 文件引用

```json
{
  "file_ref": "file_01...",
  "download_url": "https://short-lived-signed-url",
  "expires_at": "2026-08-21T03:00:00Z",
  "mime_type": "application/pdf",
  "size_bytes": 235000,
  "sha256": "...",
  "handling_policy": {
    "contains_pii": true,
    "retention": "ephemeral",
    "log_content": false
  }
}
```

- URL 只用于本次任务并短时有效。
- 平台不得在日志和错误中回显 URL 或文件正文。
- 平台需明确临时副本的最长保留期和删除确认机制。

## 6. 异步任务

### 6.1 创建响应

```json
{
  "ai_task_id": "ait_01...",
  "business_task_id": "rt_01...",
  "capability": "candidate_screening",
  "status": "queued",
  "progress": {
    "completed": 0,
    "total": 20,
    "percent": 0
  },
  "accepted_at": "2026-08-21T02:00:00Z"
}
```

### 6.2 状态

```text
queued
running
waiting_for_input
partially_completed
completed
failed
cancelled
```

规则：

- 终态不回退。
- `partially_completed` 为终态，必须包含成功与失败项目。
- 取消请求不等于立即取消；平台返回接受、已完成或不可取消。
- 重试创建新 Attempt/Task，并关联原任务。

## 7. Webhook 回调

### 7.1 事件

- `task.started`
- `task.progress`
- `task.waiting_for_input`
- `task.item_completed`
- `task.item_failed`
- `task.partially_completed`
- `task.completed`
- `task.failed`
- `task.cancelled`
- `usage.reported`

### 7.2 Envelope

```json
{
  "event_id": "evt_01...",
  "event_type": "task.progress",
  "occurred_at": "2026-08-21T02:01:00Z",
  "sequence": 3,
  "ai_task_id": "ait_01...",
  "business_task_id": "rt_01...",
  "payload": {}
}
```

### 7.3 可靠性要求

- 回调包含时间戳、Key ID 和原始 Body 签名。
- 我方验证时间窗口、防重放和 `event_id` 幂等。
- 伙伴在非 2xx 时按退避策略重试。
- 双方约定最大重试周期和死信处理方式。
- 我方快速应答，业务处理进入内部队列。
- `sequence` 乱序时不得回退业务状态。
- 我方可通过任务查询接口进行对账/补偿。

## 8. JD 生成结果草案

```json
{
  "job": {
    "title": "高级工艺工程师",
    "department": "研发部",
    "locations": ["上海"],
    "employment_type": "full_time",
    "headcount": 1,
    "salary": {"min": 20000, "max": 30000, "period": "month", "currency": "CNY"},
    "responsibilities": [],
    "must_have_requirements": [],
    "preferred_requirements": [],
    "education_requirements": [],
    "experience_requirements": [],
    "skill_tags": []
  },
  "talent_profile": {
    "skills": [],
    "experience": [],
    "traits": [],
    "motivators": [],
    "risks": []
  },
  "missing_information": [],
  "warnings": [],
  "generation": {
    "prompt_version": "jd-v1",
    "model_family": "provider-neutral"
  }
}
```

字段枚举、长度、薪资单位和必填项需在 OpenAPI/JSON Schema 中进一步冻结。

## 9. 简历解析结果草案

```json
{
  "file_ref": "file_01...",
  "status": "parsed",
  "candidate": {
    "name": "张某",
    "contacts": {},
    "location": "上海",
    "education": [],
    "work_experiences": [],
    "skills": [],
    "projects": [],
    "certifications": [],
    "languages": []
  },
  "confidence": {},
  "missing_fields": [],
  "warnings": [],
  "schema_version": "resume-v1"
}
```

业务服务根据数据策略决定是否向客户端返回未脱敏字段。

## 10. 筛选方案草案

```json
{
  "job_version_ref": "jv_01...",
  "dimensions": [
    {
      "code": "professional_skills",
      "name": "专业技能",
      "weight": 30,
      "description": "评估与岗位核心技能的匹配程度"
    }
  ],
  "must_have_rules": [],
  "exclusion_rules": [],
  "missing_information_policy": "mark_unknown",
  "warnings": []
}
```

业务服务验证权重总和、合法字段和敏感属性规则。

## 11. 筛选结果草案

```json
{
  "candidate_ref": "cand_01...",
  "job_version_ref": "jv_01...",
  "resume_parse_version_ref": "rpv_01...",
  "screening_plan_version_ref": "spv_01...",
  "score": 86,
  "level": "matched",
  "matched_points": [],
  "unmatched_points": [],
  "negotiable_points": [],
  "missing_information": [],
  "risks": [],
  "evidence": [],
  "recommendation": "manual_review",
  "generation": {
    "prompt_version": "screening-v1",
    "model_family": "provider-neutral"
  }
}
```

证据必须引用提供给平台的结构化字段或受限原文片段。业务服务验证引用、分数范围和必填解释项。

## 12. 面试题包草案

```json
{
  "candidate_ref": "cand_01...",
  "job_version_ref": "jv_01...",
  "resume_parse_version_ref": "rpv_01...",
  "screening_result_ref": "sr_01...",
  "questions": [
    {
      "category": "professional",
      "question": "请介绍……",
      "reason": "验证候选人在……方面的实际经验",
      "competency": "工艺优化",
      "follow_ups": [],
      "scoring_guide": [],
      "evidence_refs": []
    }
  ],
  "generation": {
    "prompt_version": "interview-v1",
    "model_family": "provider-neutral"
  }
}
```

## 13. Usage

```json
{
  "input_tokens": 1200,
  "output_tokens": 600,
  "provider_units": [
    {"type": "resume", "quantity": 20}
  ],
  "supplier_cost_minor": 35,
  "currency": "CNY"
}
```

Usage 只代表平台/供应商技术用量。我方按产品规则计算客户费用。

## 14. 错误协议

```json
{
  "code": "AI_PROVIDER_UNAVAILABLE",
  "message": "AI capability is temporarily unavailable",
  "retryable": true,
  "request_id": "req_01...",
  "details": {}
}
```

初始错误码：

- `AI_AUTH_FAILED`
- `AI_QUOTA_EXCEEDED`
- `AI_RATE_LIMITED`
- `AI_PROVIDER_UNAVAILABLE`
- `AI_TIMEOUT`
- `AI_INVALID_INPUT`
- `AI_FILE_UNSUPPORTED`
- `AI_PARSE_FAILED`
- `AI_CONTENT_REJECTED`
- `AI_TASK_NOT_FOUND`
- `AI_TASK_CONFLICT`
- `AI_CONTRACT_INVALID`
- `AI_INTERNAL_ERROR`

不得在错误中返回 Provider 原始错误、密钥、完整 Prompt、文件地址或候选人 PII。

## 15. Mock 验收场景

Mock 必须可配置：

- 正常同步结果。
- 流式 Delta 和结束事件。
- 可控延迟和异步进度。
- 单文件/单候选人失败。
- 部分成功。
- 超时和可重试/不可重试错误。
- 非法分数、缺失字段和错误 Schema。
- 重复回调、乱序回调和完成/取消竞态。
- Usage 重复上报。

## 16. 伙伴评审必须确认

- 能力模式、最大批量和性能目标。
- 服务鉴权、Webhook 签名和密钥轮换。
- 流式协议和断线重连。
- 回调重试周期和查询兜底。
- 文件下载方式、过期时间、数据地域、保留和删除。
- 简历解析和筛选 Schema 的可实现性。
- 语义召回职责。
- Usage 精度、币种和对账方式。
- Contract 版本兼容和弃用周期。
