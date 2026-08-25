# Backend Baseline

## Selected stack

- Java 21 LTS with Spring Boot.
- Spring MVC for transactional business APIs; use Spring WebClient for AI Platform HTTP and streaming calls without converting the whole service to reactive WebFlux.
- Spring Security and Jakarta Bean Validation.
- Spring Data JPA for aggregate persistence and ordinary queries; use jOOQ for complex search, reporting, and ledger reconciliation queries.
- Flyway for database migrations.
- PostgreSQL; enable pgvector only when semantic retrieval ownership is confirmed.
- Redis for cache, rate control, and short-lived coordination.
- RabbitMQ plus Spring consumers for reliable background commands, retries, and dead letters.
- S3-compatible object storage in every environment; MinIO is acceptable locally.
- JUnit 5, Spring Boot Test, Testcontainers, and ArchUnit.

Use the Maven Wrapper for repeatable builds. Pin Spring Boot and dependency versions through a reviewed BOM at project bootstrap.

## Modular monolith structure

```text
com.company.recruitment/
├── identity/
├── tenancy/                  companies, workspaces, memberships
├── job/
├── candidate/
├── recruitmenttask/
├── conversation/
├── screening/
├── interview/
├── billing/
├── audit/
├── aiplatform/              port, mock adapter, HTTP adapter, contract DTOs
└── shared/                  deliberately small cross-cutting infrastructure
```

Package by business capability, then separate API, application, domain, and infrastructure concerns inside the capability where useful. Avoid global `controller`, `service`, `repository`, `entity`, or `util` packages that mix every domain. Use ArchUnit to enforce the intended dependency direction.

The API process and worker process may use separate Spring Boot entry points/profiles while sharing the same domain and application modules. Do not duplicate business logic between them.

## Data model baseline

### Identity and tenancy

- `users`
- `personal_identities`
- `companies` and `company_verification_requests`
- `company_memberships`
- `workspaces` and `workspace_memberships`
- `membership_invitations` and `membership_applications`; platform registration invite codes are not part of MVP
- role enums for platform, company, and workspace scopes
- `refresh_sessions`

### Recruitment

- `jobs`
- `job_versions`
- `candidates`
- `resume_files`
- `resume_parse_versions`
- `recruitment_tasks`
- `conversations`
- `messages`
- `screening_plans`
- `screening_plan_versions`
- `screening_runs`
- `screening_run_items`
- `screening_results`
- `interview_kits`
- `interview_questions`

### AI integration, billing, and governance

- `ai_runs`: maps business operation to partner task and contract metadata.
- `usage_records`: supplier units/cost reported by AI Platform.
- `billing_accounts`: workspace-level balance projection for MVP, with future company allocation support.
- `credit_lots`: expiring grant/recharge batches used by reservations and settlements.
- `billing_ledger_entries`: immutable reservation, settlement, release, grant, adjustment.
- `idempotency_records`: request scope and stored outcome.
- `outbox_events`: reliable cross-system event delivery.
- `audit_logs`: sensitive business actions.
- `file_assets`: object metadata and authorization scope.

Prefer UUID/ULID-style opaque identifiers. Store timestamps in UTC and render in the user's timezone. Every recruitment-domain table includes `workspace_id`; uniqueness constraints include it where needed. A `company_id` may support governance/reporting but never replaces Workspace authorization.

## API conventions

- Version business APIs under `/api/v1`.
- Return typed JSON envelopes only when they add consistent metadata; do not wrap inconsistently.
- Use cursor pagination for growing activity/ledger streams and conventional pagination for bounded admin lists.
- Use stable machine-readable error codes with safe user-facing messages.
- Require idempotency keys on task creation, AI execution confirmation, and billable commands.
- Use ETags or explicit version fields for concurrent edits to JD and screening plans.
- Prefer task resources over HTTP requests held open for long operations.

## Transaction and task rules

- Create the business operation, fund reservation, and outbox event in one database transaction.
- Workers claim work idempotently and record attempts without overwriting history.
- Callback handlers verify signature/replay window, persist the raw safe envelope or hash, and return quickly.
- Process callbacks through monotonic transitions; ignore acknowledged duplicates and stale regressions.
- Partial success stores item-level outcomes and settles according to product policy.
- Cancellation is a requested state until the external platform acknowledges or the business policy resolves the race.

## File and privacy rules

- Store original files outside the database under non-guessable object keys.
- Issue short-lived URLs after server authorization; never persist signed URLs.
- Validate extension, MIME, magic bytes, size, batch count, and malware-scan state.
- Encrypt sensitive fields and secrets with managed keys; hash searchable identifiers where suitable.
- PII reveal, download, export, and deletion produce audit records.
- Apply retention/deletion to original file, parse data, derived AI results, and partner copies according to policy.

## Billing rules

- Keep supplier usage separate from customer price.
- Use minor currency units or fixed decimal columns.
- Represent grant, reservation, settlement, release, refund, and adjustment as append-only entries.
- Enforce unique business idempotency references at the database level.
- Derive displayed balance from ledger/projection with reconciliation, not ad hoc decrements.
