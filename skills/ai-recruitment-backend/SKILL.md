---
name: ai-recruitment-backend
description: Design, implement, or review the AI recruitment business service, including domain modules, APIs, persistence, tenancy, files, asynchronous business tasks, billing ledger, and orchestration of partner AI capabilities.
---

# AI Recruitment Backend

Build a modular business service that owns recruitment data and business decisions. Read [references/backend-baseline.md](references/backend-baseline.md) before changing module boundaries, persistence, tasks, billing, or authorization.

## Ownership

This service is the source of truth for users, companies, workspaces, jobs, candidates, resume files, recruitment tasks, conversations, screening plans/results, interview kits, customer usage, and billing ledger entries.

The partner AI Platform owns model/tool execution, AI task telemetry, prompt/model configuration, and supplier usage. It must not directly mutate business records or customer balances.

## Implementation workflow

1. Put behavior in the owning domain module; keep transport, persistence, and provider adapters at boundaries.
2. Define Company governance and Workspace authorization scopes before reading or mutating records.
3. Use explicit commands and state transitions for long-running or billable operations.
4. Commit business state and outbox/event intent atomically where cross-system delivery follows.
5. Validate partner AI output before converting it into versioned domain records.
6. Make retries, callbacks, and billing idempotent.
7. Add migrations and tests with every persistent model or invariant change.

## Non-negotiable invariants

- Every recruitment record carries `workspace_id`; Company is a governance grouping and never replaces the Workspace authorization scope.
- JD, parsed resume, screening plan, screening result, and interview kit are versioned or bind to immutable input versions.
- Money uses integer minor units or exact decimal types, never binary floating point.
- Ledger history is append-only; corrections use compensating entries.
- Original resumes live in controlled object storage; database records hold metadata and object references.
- Logs never contain resume bodies, raw contact information, credentials, signed URLs, or complete AI prompts containing PII.
- Deletion and PII reveal actions are authorized and audited.
- Callback arrival order is not trusted.

## Architecture posture

Start as a modular monolith plus workers. Do not split domain microservices before load, ownership, or independent deployment requires it. Depend on an internal `AIPlatformClient` port rather than partner-specific HTTP calls in domain code.
