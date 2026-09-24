---
name: ai-platform-integration
description: Define, implement, or review the BOSS-authorized contract between the AI recruitment business service and AIAgentPlatform, including structured capabilities, asynchronous tasks, errors, idempotency, and usage data.
---

# AI Platform Integration

Use this skill for the boundary between the recruitment business service and AIAgentPlatform. Read [references/integration-contract.md](references/integration-contract.md) before creating endpoints, adapters, callback handlers, or contract tests.

## System boundary

The approved call path is:

`web client -> recruitment business service -> BOSS authorization -> AIAgentPlatform -> providers/tools`

Never expose provider credentials or AI Platform endpoints to the browser. BOSS owns authorization and customer credits; the recruitment business service owns recruitment records and usage/cancellation Outbox delivery; AIAgentPlatform owns only AI execution and task telemetry.

## Contract-first workflow

1. Define the capability in a versioned OpenAPI/JSON Schema contract before relying on it.
2. Classify it as synchronous, streamed, or asynchronous based on observable duration and recovery needs.
3. Include correlation, tenancy, idempotency, input-version, and usage fields.
4. Implement the business-facing port only with the formal HTTP contract; do not add a runtime mock or local fallback adapter.
5. Validate every response, stream event, and callback at the boundary.
6. Test timeout, retry, duplicate request, duplicate callback, out-of-order callback, partial success, cancellation race, and invalid payload.
7. Reject absent, expired, invalid, or mismatched BOSS Grant data before Agent execution.

## Required invariants

- Keep `business_task_id` and `ai_task_id` distinct and mapped.
- Use an idempotency key for every task-creating or billable request.
- Callbacks are signed, replay-protected, acknowledged quickly, and processed idempotently.
- Status is monotonic according to the agreed transition model; stale events cannot regress business state.
- Natural-language content supplements, but never replaces, structured output.
- Agent returns execution usage; IR delivers it idempotently to BOSS, which is the sole customer-credit settlement authority.
- Files are shared through short-lived authorized references unless an explicitly approved data-transfer method is required.
- The adapter must not leak partner-specific types into domain models.

## Test doubles

Test-only HTTP stubs may exercise timeout, malformed payload, duplicate callback, cancellation race, and out-of-order events. They must not be compiled into or selectable from the production runtime path.
