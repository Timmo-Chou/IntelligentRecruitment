---
name: ai-recruitment-product
description: Define, refine, or review the AI recruitment product's MVP scope, terminology, business rules, user flows, and acceptance criteria. Use for product decisions and for implementation work whose behavior depends on recruitment-domain rules.
---

# AI Recruitment Product

Use this skill as the business source of truth for the P0 product. Preserve explicit user decisions; when a required rule is unresolved, label it as a decision instead of inventing behavior.

## Product boundary

The product is an enterprise AI recruitment workspace. Its P0 value loop is:

`recruitment need -> JD draft -> human confirmation -> resume import -> screening plan confirmation -> matching -> candidate selection -> interview-kit generation`

AI generates, analyzes, and recommends. A human confirms business records and recruitment decisions. Do not present AI output as an automatic hiring or rejection decision.

## Working method

1. Identify the actor, business object, entry condition, result, state change, and cost trigger.
2. Check the requested behavior against `docs/architecture/recruitment-saas-boss-integration.md` and `docs/architecture/agent-orchestration-contract-v1.md`. [references/product-baseline.md](references/product-baseline.md) is a historical archive and must not be used as a current source of truth.
3. Reuse canonical terminology and states. Do not create parallel concepts such as both "resume library" and "talent library".
4. Keep P1/P2 capabilities out of P0 unless the user explicitly changes scope.
5. For unresolved rules, expose the decision and its impact on UX, API, data, and billing.
6. Define acceptance criteria in observable business terms, not implementation details.

## Non-negotiable product rules

- Tenant is the sole recruitment isolation scope. BOSS is the authority for identity, permissions, packages, entitlements, and credits; IR does not implement a local package or credit ledger.
- Every billable AI operation follows `IR -> BOSS authorization -> AIAgentPlatform execution -> IR settlement Outbox -> BOSS`. Runtime Mock, local result fallback, or local settlement cannot replace this flow.
- Generated JD content begins as a draft; confirmation creates or updates a business version.
- Screening binds to a specific JD version and resume-parse version.
- Screening results include evidence, matched points, gaps, missing information, risks, and a human-review recommendation—not only a score.
- Generating interview questions for candidates requires explicit candidate selection and a visible cost estimate.
- Product-visible conversations and tasks belong to the recruitment business service.
- AI supplier cost and customer-facing billing are different concepts.
- P0 must retain generated interview kits even if full interview-library management is deferred.
- Candidate personal information is masked by default and revealed only through authorized business actions.

## Outputs

Depending on the request, produce or update only the relevant artifact: scope, flow, rule table, state model, acceptance criteria, or decision record. Avoid rewriting unrelated product areas.
