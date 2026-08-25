# Frontend Baseline

## Selected stack

- Next.js App Router, React, and strict TypeScript.
- Tailwind CSS with CSS custom-property design tokens.
- Radix UI primitives with project-owned components; shadcn-style source ownership is acceptable.
- TanStack Query for server state and mutation lifecycle.
- React Hook Form plus Zod for form state and boundary validation.
- Zustand only for small cross-route UI state when URL/local component state is insufficient.
- Native `fetch` streaming or SSE for AI output; ordinary task progress comes from business API polling initially.
- Vitest, React Testing Library, Mock Service Worker, and Playwright.

Pin compatible versions when bootstrapping; do not rely on unbounded ranges.

## Feature structure

```text
src/
├── app/                 routes and layouts
├── features/
│   ├── auth/
│   ├── dashboard/
│   ├── recruitment/
│   ├── jobs/
│   ├── candidates/
│   ├── screening/
│   ├── interviews/
│   ├── billing/
│   └── settings/
├── components/
│   ├── ui/              generic primitives
│   ├── business/        shared recruitment concepts
│   └── layout/
├── lib/                 API, auth, validation, formatting
└── styles/              tokens and global styles
```

Keep feature-specific components, queries, schemas, and tests inside the feature. Promote code to shared folders only after a real shared role exists.

## Route baseline

| Route | P0 purpose |
|---|---|
| `/login` | Verification-code authentication |
| `/` | Overview and pending work |
| `/recruitment` | New and current recruitment task workspace |
| `/recruitment/[taskId]` | Recoverable task, conversation, and results |
| `/jobs` | Job library |
| `/jobs/[jobId]` | Job and JD-version detail |
| `/candidates` | Talent library |
| `/candidates/[candidateId]` | Structured resume, source file, matches, interview kits |
| `/billing` | Trial balance and ledger |
| `/settings` | Profile and basic preferences |

## Visual system

### Tokens

- Brand blue `#2F6BFF` for AI primary actions and active process steps.
- AI/success green `#16B87A` for completion, confirmation, and positive status.
- Teal `#14B8A6`, warning `#F59E0B`, danger `#EF4444`, workflow purple `#8B5CF6`.
- Page background `#F7FAFC`, surface `#FFFFFF`, soft surface `#FAFCFF`.
- Text: `#0F172A`, `#334155`, `#64748B`, `#94A3B8`.
- Border `#E2E8F0`; 8px spacing grid; card radius 12–16px.

Typography prefers PingFang SC, Microsoft YaHei, Noto Sans CJK SC, then system sans; use Inter/SF Pro for Latin text and numbers when available.

### Layout

- Top navigation 64–72px.
- Sidebar 220–240px.
- Main padding 24–32px.
- Workspace: flexible result pane plus collapsible/resizable AI pane, normally 420–520px.
- At 1280px, allow the AI pane to overlay or collapse rather than compress core results below usability.

### Frozen enterprise-workbench direction

The current visual reference establishes the following reusable language. It is a style baseline, not permission to change product routes or page ownership.

- Global header: 64–68px, white-to-pale-blue/cyan restrained gradient, thin blue-gray bottom border.
- Brand: compact blue/cyan mark with a dark navy Chinese product name; no large decorative logo block.
- Sidebar: approximately 200–220px in dense desktop layouts, white surface, navy labels, 18–20px line icons, pale mint active item with green text.
- Main canvas: very pale blue-white background, 16–24px page padding, compact page title and supporting description on one line when space permits.
- Toolbars: white inputs and outlined filters with blue-gray borders; green is reserved for the principal create/confirm action.
- Metric cards: pale cyan/blue surfaces, small circular icon badge, navy numeric emphasis, restrained green trend annotation.
- Data areas: white cards, 10–12px radius, thin cool-gray borders, subtle or nearly absent shadows, compact 12–14px table typography.
- Master-detail pages: flexible table/list on the left and a 380–440px detail card on wide screens; stack the detail panel below content when the viewport cannot sustain both.
- Status: green for active/success, blue-gray for closed/inactive, with explicit text and not color alone.
- Detail typography: navy headings, blue-gray body copy, compact outlined skill tags, clear section grouping without heavy separators.
- Header utilities: compact balance, billing explanation, notification, and user identity controls; do not let them overpower the page title or core workflow.

### Route and visual separation

- `/` always remains the overview and pending-work dashboard.
- `/jobs` owns the job library list and job detail composition.
- `/candidates` owns the talent library; `/recruitment` owns AI-assisted recruitment work.
- When a reference image depicts one feature, reproduce it on that route and propagate only shared tokens/layout patterns globally.
- Visual refactoring must not silently add, remove, rename, or repurpose navigation destinations.

### Authentication-screen direction

- Login is a dedicated route without the authenticated application shell.
- On desktop, use a focused white form card on the left and a light blue/cyan product-value panel on the right; collapse to the form card on smaller screens.
- Adapt the visual reference to the confirmed authentication behavior. Phase 2 defaults to phone verification-code login, so password fields must not be presented as an available primary path.
- Use one blue-to-green gradient only for the primary login action and restrained illustration accents; keep form labels, validation, agreement links, keyboard focus, and disabled states explicit.
- Product-value illustrations may be abstract/CSS-based, but cannot reduce form readability or imply unimplemented product functions are available.

### Shared business components

`AgentBadge`, `AIResultCard`, `AIDisclaimer`, `UsageCostBadge`, `MatchScore`, `EvidenceList`, `RiskIndicator`, `TaskProgress`, `ResumeUploader`, `ResumeParseStatus`, `ScreeningPlanEditor`, `HumanReviewBar`, and `PIIMask`.

Do not put billing or permission logic inside these visual components. They render server-authorized state and invoke explicit feature actions.

## Data and state rules

- Generate API types from the business service OpenAPI contract when practical.
- Use query keys scoped by Company/Workspace context and resource identity; recruitment data must always include Workspace scope.
- Do not permanently cache unmasked PII in browser storage.
- URL state holds shareable filters and selected records; server state holds business objects; component state holds temporary interaction.
- Optimistic updates are allowed only for reversible, non-billable actions with clear rollback.
- Do not optimistically mark AI or billing operations completed.

## AI and task UX

- Stream provisional assistant prose while a generation call is active.
- Render structured, persisted JD/results/interview kits from business APIs after validation and save.
- Reconnect or fall back to task polling after refresh/network interruption.
- Show queued, running, progress, partial success, failure, retry availability, cancelled, and completed states.
- A retry is a server command; never replay a billable request solely from client state.

## File upload

- Validate extension, MIME type, size, and batch count before upload, then trust server validation as authoritative.
- Show one row per file with upload and parse states.
- Support retry for individual failures and preserve successful items.
- Use pre-signed upload only when issued by the business service.

## Accessibility and privacy

- Meet WCAG AA contrast for text and controls.
- All interactive controls require visible focus, keyboard operation, accessible names, and non-color status text.
- Mask candidate identity by default. PII reveal calls a server-authorized, audited action.
- Avoid candidate PII in analytics events, URLs, local storage, error reports, and test fixtures.
