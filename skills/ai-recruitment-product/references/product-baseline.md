# P0 Product Baseline

## Product definition

An enterprise AI recruitment workspace for HR staff and recruitment consultants. It uses guided AI assistance to turn recruitment needs into confirmed job descriptions, screen selected talent, and generate candidate-specific interview kits.

The business service is the system of record. AI output is advice until a user confirms or adopts it.

## Canonical terminology

| Term | Meaning |
|---|---|
| Company | Verified enterprise or business entity used for ownership and governance |
| Workspace | Tenant that owns recruitment data, members, and the MVP billing account |
| Job | Stable position identity |
| JD version | Immutable version of structured and narrative job content |
| Candidate | Stable person identity inside one Workspace |
| Resume file | Original uploaded document |
| Resume parse version | Structured extraction produced from one resume file |
| Recruitment task | Product-visible unit of work and conversation context |
| Screening plan | Confirmed dimensions, weights, must-haves, exclusions, and missing-data rules |
| Screening run | One execution against immutable job, plan, and resume inputs |
| Screening result | Candidate-level score, evidence, gaps, risks, and recommendation |
| Interview kit | Versioned set of questions, reasons, follow-ups, and scoring guidance |
| AI run | Technical invocation record mapped to a business task |

Use “talent library” as the product module. “Resume” refers to a candidate file, not a separate library.

For MVP, Workspace directly represents a department, business line, headhunting industry group, project team, or personal recruiting space. Do not introduce a Department/OrganizationUnit hierarchy beneath Workspace.

## P0 scope

### Included

- Phone verification-code authentication and basic user profile.
- Personal and company workspaces with company/workspace membership and strict workspace isolation.
- One personal Workspace per user in MVP; only Company Owner/Admin can create enterprise Workspaces.
- Open phone registration without a platform invitation code; company/workspace member invitation links remain supported.
- Global shell, overview, and AI recruitment workspace.
- Guided JD generation, editing, confirmation, versioning, and job library.
- PDF/DOC/DOCX resume upload, parse status, structured preview, retry, and talent library.
- Screening-plan generation and human confirmation.
- Screening across an explicitly selected job version and candidate set.
- Explainable results with evidence and human-review language.
- Explicit candidate selection and interview-kit generation.
- Product-visible task/conversation history sufficient to resume or inspect a task.
- Trial balance, cost estimate, usage settlement, and ledger detail.
- Masked candidate identity, authorized reveal audit, basic settings, and error recovery.
- Mock AI Platform integration while the partner platform is unavailable.

### Deferred

- Fully automatic cross-agent recruitment workflow.
- Complete reusable interview-question library management.
- Advanced roles and shared/individual quota configuration.
- Online recharge, invoice, WeChat login, notification center, help desk.
- Job-board publishing, ATS integrations, mobile product, voice input.

## Primary actors

- Company Owner/Admin: governs enterprise identity, membership, and allowed enterprise-level settings.
- Workspace Owner/Admin: manages Workspace members, recruitment data, and Workspace billing visibility.
- Recruiter: creates jobs, imports candidates, runs screening, and generates interview kits.
- Read-only member: reserved for later; do not expose in P0 unless scope changes.

## Core flow

1. User enters a recruitment need in a new recruitment task.
2. AI asks for missing required information and returns a structured JD draft plus talent profile.
3. User edits and confirms; the system creates a JD version.
4. User uploads or selects resumes. Files are validated, stored, and parsed asynchronously.
5. User selects a JD version and eligible parsed resumes.
6. AI proposes a screening plan. User edits weights/rules and confirms it.
7. System shows candidate count and estimated customer charge; user confirms execution.
8. Screening runs asynchronously and may partially succeed.
9. User reviews explainable results and explicitly selects candidates.
10. System shows interview-generation estimate; user confirms.
11. AI generates and the business service stores interview kits linked to input versions.

## Business states

### Recruitment task

`draft -> active -> completed | cancelled`

An active task may contain waiting, executing, or failed AI operations without changing the task's overall identity.

### Long-running operation

`queued -> running -> waiting_for_input | partially_completed | completed | failed | cancelled`

Terminal states do not regress. Retrying creates a new attempt under the same business operation rather than erasing history.

### Job

`draft -> active -> archived`

AI generation creates a draft. Confirmation creates a version. Editing a confirmed version creates a new version rather than overwriting evidence used by prior screening.

### Resume parsing

`uploaded -> queued -> parsing -> parsed | failed | deleted`

## Screening result requirements

- Score from 0 to 100 plus textual level.
- Matched points and evidence references.
- Unmatched points and evidence references.
- Missing or ambiguous information.
- Negotiable points and risks.
- Human-review recommendation; never “system rejection.”
- Bound JD version, resume-parse version, screening-plan version, model/prompt metadata, and timestamp.

Default display bands may be `90-100 strong`, `80-89 matched`, `70-79 general`, `<70 weak`, but thresholds are configurable product policy and not hiring decisions.

## Billing rules

- Show an estimate before batch screening and candidate-level interview generation.
- Reserve customer funds before a billable asynchronous operation when pricing is determinable.
- Settle from actual product units after completion; release unused reservation on failure/cancellation according to policy.
- Supplier tokens/cost do not define customer price.
- All money movements are append-only ledger entries and idempotent.
- A verified personal identity receives one CNY 30 trial grant; a verified Company receives one CNY 100 trial grant after its first Workspace is created. Both are credited to a designated Workspace, expire exactly 90 days after issuance, and use earliest-expiring credit first.
- Company roles never grant recruitment-data access to a Workspace without an active Workspace membership.

## Decisions that remain open

- Exact JD-generation and interview-generation customer prices.
- Detailed trial-credit abuse controls beyond one idempotent grant per verified person/Company and duplicate-creation protection.
- Whether resume parsing is included in screening price or billed separately.
- Charge policy for partial success, provider failure, user cancellation, and retry.
- P0 upload limits, OCR support, and candidate deduplication rule.
- Default screening weights and allowed hard-exclusion rules.
- Candidate data retention period and authorized PII reveal roles.
- Whether semantic recall is performed by the business service or AI Platform.
