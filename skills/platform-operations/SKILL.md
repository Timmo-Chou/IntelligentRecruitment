---
name: platform-operations
description: Design, implement, or review the platform operations system including admin authentication, RBAC, user/company management, review workflows, support ticketing, and menu configuration for the AI recruitment platform.
---

# Platform Operations

Use this skill to design and implement the platform operations (管理后台) module. It provides the admin-facing capabilities that operate on top of the existing recruitment system. Read [references/platform-operations-baseline.md](references/platform-operations-baseline.md) before making changes to platform APIs, admin authentication, or permission models.

## Scope

The platform operations system provides:
- Platform admin authentication and authorization (two roles: SUPER_ADMIN, PLATFORM_OPERATOR)
- Registered user and enterprise management (list, search, view details, enable/disable)
- Review workflows for personal identity verification, company verification, and membership applications
- Support ticket system (create, reply, assign, close)
- Menu management (configure sidebar navigation and role visibility)

## Relationship with existing system

- Backend: same Spring Boot service as the recruitment system. Platform endpoints live under `/api/v1/platform/**` with independent authentication via `PlatformAdminFilter`.
- Frontend: separate `apps/admin` application, independent from `apps/web`.
- Authentication: platform admin identity is stored in `platform_admins` table, completely separate from user-side `company_memberships` / `workspace_memberships`.

## Implementation workflow

1. Read [references/platform-operations-baseline.md](references/platform-operations-baseline.md) for the full design specification.
2. Check the existing system architecture in [docs/architecture/platform-operations-design.md](../../docs/architecture/platform-operations-design.md).
3. Platform APIs must be placed under `/api/v1/platform/**` and use `PlatformAdminGuard` for authorization.
4. Platform admin authentication is separate from user JWT — ensure `/api/v1/platform/**` is excluded from `BearerTokenFilter` in `SecurityConfiguration`.
5. User-facing ticket endpoints go under `/api/v1/me/tickets` and use regular user JWT authentication.
6. All platform operations must be audited via the existing `audit_logs` table.
7. Menu data is loaded from `platform_menus` table and filtered by admin role at runtime.

## Non-negotiable invariants

- Platform admin identity and user identity are separate tables and authentication flows — never mix them.
- Platform APIs must not bypass workspace isolation when reading user/company data.
- Every platform admin action must be auditable (who did what, when).
- Menu configuration is database-driven and role-filtered — do not hardcode menus in frontend.
- Ticket messages are append-only; never edit or delete existing messages.
- The `PlatformAdminGuard` must validate both authentication (who) and authorization (permission code).

## Key design decisions

- Only two roles for MVP: SUPER_ADMIN and PLATFORM_OPERATOR. Extend via the `role` enum later if needed.
- Permissions are hardcoded in code, not stored in a database table. This avoids complexity for MVP.
- Menu visibility is controlled by `visible_to_operator` flag in the database, configurable by SUPER_ADMIN.
- Support tickets can be created by users (via `/me/tickets`) or by platform admins on behalf of users.