---
name: ai-recruitment-frontend
description: Design, implement, or review the P0 web client for the AI recruitment product, including feature architecture, UI system, AI workspace interactions, file uploads, asynchronous task states, and business-service API consumption.
---

# AI Recruitment Frontend

Build the web client as an enterprise recruitment application, not as a generic chat shell. Read [references/frontend-baseline.md](references/frontend-baseline.md) before making architectural, page, or shared-component changes.

## Boundaries

- The browser calls only the AI recruitment business service. It never calls the partner AI Platform or model providers directly.
- Server data belongs in the server-state layer. Keep local stores for transient UI state only.
- Domain behavior comes from `$ai-recruitment-product`; do not encode new product rules in presentation components.
- Treat streamed text as provisional. Persisted structured results from the business API are authoritative.

## Implementation workflow

1. Locate the owning feature and its route, API query/mutation, state views, and reusable components.
2. Model loading, empty, error, partial-success, permission-denied, and completed states before polishing the happy path.
3. Reuse design tokens and shared components; create a business component only when the concept recurs or carries domain semantics.
4. Make long-running work recoverable after refresh by rendering server task state, not an in-memory spinner.
5. Validate forms and API payloads at their boundary. Display actionable business errors without exposing provider or internal stack details.
6. Add proportionate component or end-to-end coverage for changed critical flows.

## Visual implementation rules

- Follow the enterprise workbench direction frozen in `references/frontend-baseline.md`: airy blue-white surfaces, navy information hierarchy, restrained cyan gradients, and green confirmation/primary business actions.
- Keep the global header, sidebar navigation, content toolbar, metric cards, dense data table, and optional detail panel visually consistent across routes.
- Treat a supplied screen as a visual reference unless the user explicitly requests an information-architecture change. Never replace `/` overview with a job, candidate, or other feature page merely because the reference image shows that feature.
- Preserve the route baseline and active-navigation mapping. A feature mockup belongs on its owning route; shared visual language belongs in tokens and shared layout components.
- Demo data must be clearly implementation-only and must not imply that an unfinished backend workflow is available.

## UX invariants

- Desktop-first at 1280, 1440, and 1600 widths; the AI panel is collapsible and must not starve the result workspace.
- Every AI invocation shows agent/capability, status, estimated or settled charge, and a human confirmation point where applicable.
- Matching uses text plus color; do not communicate score or status by color alone.
- Candidate PII is masked by default, including cached and optimistic views.
- File upload shows per-file validation, progress, parse state, retry, and partial failure.
- Do not use flashing balance warnings, dark cyberpunk styling, or decorative AI visuals in dense business screens.
- Avoid oversized dashboard cards, excessive shadows, saturated full-page gradients, and chat-first layouts. Dense recruitment pages should remain calm and scannable.

## Completion check

Confirm route behavior, API contract use, permission handling, refresh recovery, keyboard/focus behavior, responsive layout, sensitive-data display, and tests relevant to the change.
