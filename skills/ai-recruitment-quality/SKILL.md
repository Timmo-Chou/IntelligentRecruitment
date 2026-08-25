---
name: ai-recruitment-quality
description: Test, review, harden, or assess the AI recruitment client and business service for privacy, security, tenant isolation, AI explainability, contract resilience, billing correctness, observability, and release readiness.
---

# AI Recruitment Quality

Use this skill for implementation reviews, test design, security/privacy work, and release gates. Read [references/quality-baseline.md](references/quality-baseline.md) and select checks proportional to the affected risk; do not turn every small UI edit into a full-system audit.

## Risk priorities

1. Candidate privacy and tenant isolation.
2. Incorrect hiring signals or unexplained AI recommendations.
3. Duplicate, missing, or incorrect customer charges.
4. Lost, duplicated, or regressed asynchronous tasks.
5. Unsafe file handling and unauthorized downloads.
6. Contract drift between business service and AI Platform.

## Review method

1. Identify changed data, actors, trust boundaries, money movement, and asynchronous transitions.
2. Select the relevant matrix from the quality baseline.
3. Test observable invariants and failure behavior, not only successful HTTP responses.
4. Verify logs and traces can diagnose the operation without exposing PII or secrets.
5. Report findings by impact and provide a reproducible failure condition.

## Cross-cutting requirements

- Enforce Company governance and Workspace authorization scope on the server for every tenant record and file.
- Mask PII by default and audit reveal, export, and deletion actions.
- Do not use protected or sensitive personal attributes as default ranking signals.
- Preserve evidence and model/input versions for AI-assisted screening.
- Contract-test schemas, errors, task transitions, stream events, and callbacks.
- Exercise duplicate requests, duplicate/out-of-order callbacks, timeouts, retries, cancellation races, and partial success.
- Prove ledger idempotency and compensation behavior; never assert billing correctness from displayed balance alone.
- Keep secrets, resume content, contact details, raw signed URLs, and sensitive prompts out of logs and fixtures.

## Release posture

Block release for cross-tenant access, unauthorized PII exposure, unexplained or corrupted screening results, duplicate charges, unrecoverable task-state corruption, or executable/malicious file acceptance. Track lower-risk visual and resilience issues according to the agreed release threshold.
