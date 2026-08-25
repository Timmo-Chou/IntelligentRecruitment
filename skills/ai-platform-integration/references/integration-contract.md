# AI Platform Integration Contract Baseline

## Ownership

| Concern | Recruitment business service | Partner AI Platform |
|---|---:|---:|
| Authentication and organization membership | Owns | Receives trusted service context |
| Jobs, candidates, files, results | Owns | Receives bounded inputs/references |
| Product task/conversation history | Owns | Returns AI events/output |
| Customer price and balance | Owns | Does not mutate |
| Agent/model/tool execution | Calls | Owns |
| Prompt/model/provider configuration | Does not own | Owns |
| Supplier tokens and cost | Records reported data | Calculates and reports |
| PII release policy | Owns | Enforces supplied handling policy |

## Initial capabilities

| Capability | Mode | P0 output |
|---|---|---|
| Recruitment conversation / requirement clarification | Stream | Assistant deltas plus structured missing fields |
| JD and talent-profile generation | Stream or async | Structured JD draft, talent profile, warnings |
| Resume parsing | Async batch | Per-file structured parse or typed failure |
| Screening-plan generation | Sync/short async | Dimensions, weights, must-haves, exclusions, explanations |
| Candidate screening | Async batch | Progress and per-candidate explainable result |
| Interview-kit generation | Sync/async | Questions, reasons, follow-ups, scoring guidance |

Full cross-agent workflow is P1.

## Common request context

```json
{
  "request_id": "req_...",
  "trace_id": "trc_...",
  "organization_id": "org_...",
  "actor_id": "usr_...",
  "business_task_id": "rt_...",
  "idempotency_key": "...",
  "locale": "zh-CN",
  "timezone": "Asia/Shanghai",
  "contract_version": "v1"
}
```

Pass only necessary identifiers and data. The AI Platform treats business identifiers as opaque correlation values.

## Async task resource

```json
{
  "ai_task_id": "ait_...",
  "business_task_id": "rt_...",
  "capability": "candidate_screening",
  "status": "queued",
  "progress": {"completed": 0, "total": 20, "percent": 0},
  "accepted_at": "2026-08-21T02:00:00Z"
}
```

Statuses: `queued`, `running`, `waiting_for_input`, `partially_completed`, `completed`, `failed`, `cancelled`.

Terminal status does not regress. A retry creates a new attempt/task ID linked to the prior task.

## Callback envelope

```json
{
  "event_id": "evt_...",
  "event_type": "task.progress",
  "occurred_at": "2026-08-21T02:01:00Z",
  "sequence": 3,
  "ai_task_id": "ait_...",
  "business_task_id": "rt_...",
  "payload": {}
}
```

Initial events: `task.started`, `task.progress`, `task.waiting_for_input`, `task.item_completed`, `task.partially_completed`, `task.completed`, `task.failed`, `task.cancelled`, and `usage.reported`.

Sign callbacks with timestamp, key identifier, and body digest. The business service enforces a replay window, verifies against the raw body, and deduplicates `event_id`.

## Structured screening result

At minimum:

```json
{
  "candidate_ref": "cand_...",
  "job_version_ref": "jv_...",
  "resume_parse_version_ref": "rpv_...",
  "screening_plan_version_ref": "spv_...",
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
    "model_family": "provider-neutral-name",
    "prompt_version": "screening-v1"
  }
}
```

Evidence should point to supplied structured fields or bounded source excerpts. The business service validates references and does not accept scores outside 0–100.

## Usage

```json
{
  "input_tokens": 1200,
  "output_tokens": 600,
  "provider_units": [{"type": "resume", "quantity": 20}],
  "supplier_cost_minor": 35,
  "currency": "CNY"
}
```

This is supplier telemetry. Customer settlement is calculated only by the recruitment business service.

## Error envelope

```json
{
  "code": "AI_PROVIDER_UNAVAILABLE",
  "message": "AI capability is temporarily unavailable",
  "retryable": true,
  "request_id": "req_...",
  "details": {}
}
```

Initial codes: `AI_AUTH_FAILED`, `AI_QUOTA_EXCEEDED`, `AI_RATE_LIMITED`, `AI_PROVIDER_UNAVAILABLE`, `AI_TIMEOUT`, `AI_INVALID_INPUT`, `AI_FILE_UNSUPPORTED`, `AI_PARSE_FAILED`, `AI_CONTENT_REJECTED`, `AI_TASK_NOT_FOUND`, `AI_TASK_CONFLICT`, `AI_CONTRACT_INVALID`, and `AI_INTERNAL_ERROR`.

Do not expose raw provider errors, credentials, prompts, or candidate PII in `message` or `details`.

## Adapter boundary

The business service defines a provider-neutral `AIPlatformClient` with operations for requirement chat, JD generation, resume parsing, screening-plan generation, screening, interview-kit generation, task lookup, cancellation, and retry.

Provide:

- Deterministic mock adapter for product development and contract failure cases.
- HTTP adapter for the partner platform.
- Contract tests shared or run by both teams.

Partner-specific DTOs are converted inside the HTTP adapter and do not enter domain services.

## Open decisions

- Service authentication mechanism and key rotation.
- Callback delivery/retry SLA and polling fallback.
- Stream protocol and reconnect cursor.
- Maximum batch sizes and file-reference lifetime.
- Semantic recall ownership and result contract.
- Supplier-cost currency precision.
- Partner retention and deletion acknowledgement.
- Version compatibility and deprecation window.

