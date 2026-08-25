# Quality Baseline

## Test layers

| Layer | Primary purpose |
|---|---|
| Unit | Domain invariants, validation, state transitions, pricing calculations |
| Component | Frontend interaction, accessibility, error/empty/partial states |
| API integration | Authorization, Company/Workspace scope, database transactions, file access |
| Contract | AI requests/responses, stream events, callbacks, schemas, errors |
| Worker/integration | Retries, idempotency, partial success, cancellation races |
| End to end | User-visible P0 value loop and billing/audit outcome |

## P0 end-to-end path

`register -> receive trial credit -> create task -> generate/confirm JD -> upload/parse resumes -> confirm screening plan/estimate -> screen -> select candidate -> generate interview kit -> inspect ledger`

Test refresh/recovery during parsing and screening, plus one partial-failure path.

## Privacy and tenant isolation

- Attempt cross-Workspace access for every tenant resource endpoint, nested resource, file URL, export, and callback mapping; also test unauthorized Company-level governance operations.
- Verify list/search counts and error differences do not leak other Companies or Workspaces.
- Verify masked identity is the default representation.
- Verify reveal/download/export/delete require server authorization and create audit events.
- Verify PII is absent from URLs, analytics, logs, traces, fixtures, snapshots, and browser persistence.
- Verify external AI payloads contain only allowed fields and respect the selected masking policy.

## AI result quality and safety

- Reject invalid score ranges, unknown references, missing required explanation fields, and malformed evidence.
- Preserve JD, resume-parse, screening-plan, prompt, and model-version metadata.
- Ensure protected/sensitive attributes are not default ranking inputs.
- Render recommendations as assistance requiring human review.
- Test missing, contradictory, and low-confidence resume information.

## Files

- Validate extension, MIME, magic bytes, size, count, archive/bomb behavior where applicable, and malware-scan result.
- Test unauthorized object lookup, expired signed URLs, deleted records, and renamed executable content.
- Verify successful files survive failures of other items in the batch.
- Verify retention/deletion reaches original, parsed, derived, and partner-held data according to policy.

## Async and integration resilience

- Duplicate task-creation request.
- Provider timeout before and after accepting a task.
- Duplicate callback.
- Out-of-order sequence.
- Progress after cancellation request.
- Completion racing cancellation.
- Partial candidate failure.
- Retry creates a new attempt without duplicate business output.
- Callback signature failure and replay-window expiry.
- Invalid structured result and supplier usage.
- Network interruption followed by UI recovery.

## Billing

- Duplicate confirmation does not duplicate reservation.
- Duplicate completion does not duplicate settlement.
- Failure/cancellation releases the correct amount exactly once.
- Partial success follows documented unit and rounding policy.
- Retry policy is explicit and tested.
- Balance projection reconciles to append-only ledger entries.
- Supplier usage does not directly change customer balance.

## Observability

Correlate `request_id`, `trace_id`, `company_id` when applicable, `workspace_id`, `business_task_id`, `ai_task_id`, and ledger reference without logging sensitive payloads. Alert on sustained task failures, callback verification failures, queue backlog, billing reconciliation failure, and storage/malware-scan failure.

## Release blockers

- Cross-tenant access or unauthorized PII exposure.
- Duplicate charge or unreconcilable ledger.
- Task state corruption that requires manual database editing.
- AI screening result without required explanation/version binding.
- Executable or known-malicious file accepted as a resume.
- Contract incompatibility on a P0 capability.
