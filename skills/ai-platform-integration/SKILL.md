---
name: ai-platform-integration
description: Define, mock, implement, or review the contract between the AI recruitment business service and a separately developed AI Platform, including structured capabilities, streaming, asynchronous tasks, callbacks, errors, idempotency, and usage data.
---

# AI Platform Integration

Use this skill for the boundary between the recruitment business service and the partner-owned AI Platform. Read [references/integration-contract.md](references/integration-contract.md) before creating endpoints, adapters, mocks, callback handlers, or contract tests.

## System boundary

The approved call path is:

`web client -> recruitment business service -> AI Platform -> providers/tools`

Never expose provider credentials or AI Platform endpoints to the browser. The business service owns business authorization, records, customer billing, and final state. The AI Platform owns AI execution and supplier telemetry.

## Contract-first workflow

1. Define the capability in a versioned OpenAPI/JSON Schema contract before relying on it.
2. Classify it as synchronous, streamed, or asynchronous based on observable duration and recovery needs.
3. Include correlation, tenancy, idempotency, input-version, and usage fields.
4. Implement the business-facing port with both mock and HTTP adapters.
5. Validate every response, stream event, and callback at the boundary.
6. Test timeout, retry, duplicate request, duplicate callback, out-of-order callback, partial success, cancellation race, and invalid payload.
7. Replace mock capabilities individually; do not switch every AI capability at once.

## Required invariants

- Keep `business_task_id` and `ai_task_id` distinct and mapped.
- Use an idempotency key for every task-creating or billable request.
- Callbacks are signed, replay-protected, acknowledged quickly, and processed idempotently.
- Status is monotonic according to the agreed transition model; stale events cannot regress business state.
- Natural-language content supplements, but never replaces, structured output.
- Usage reports supplier units/cost; the business service calculates customer price.
- Files are shared through short-lived authorized references unless an explicitly approved data-transfer method is required.
- The adapter must not leak partner-specific types into domain models.

## Mock standard

The mock supports deterministic success data, configurable delay, streaming, progress, per-item failure, partial completion, timeout, malformed payload, duplicate callback, and callback reordering. A fixed happy-path JSON response is insufficient.
