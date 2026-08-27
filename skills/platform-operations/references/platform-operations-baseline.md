# Platform Operations Baseline

## Admin roles

| Role | Code | Permissions |
|---|---|---|
| Super Admin | `SUPER_ADMIN` | All permissions including admin management and menu configuration |
| Platform Operator | `PLATFORM_OPERATOR` | User/company viewing, review workflows, ticket management, billing operations |

## Permissions (hardcoded)

| Code | Description | SUPER_ADMIN | PLATFORM_OPERATOR |
|---|---|---|---|
| `admin:manage` | Admin management | ✅ | - |
| `menu:manage` | Menu configuration | ✅ | - |
| `user:read` | View users | ✅ | ✅ |
| `user:write` | Enable/disable users | ✅ | ✅ |
| `company:read` | View companies | ✅ | ✅ |
| `company:write` | Edit companies | ✅ | ✅ |
| `verification:review` | Review verifications | ✅ | ✅ |
| `membership:review` | Review membership applications | ✅ | ✅ |
| `ticket:read` | View tickets | ✅ | ✅ |
| `ticket:write` | Reply/close tickets | ✅ | ✅ |
| `billing:read` | View ledgers | ✅ | ✅ |
| `billing:adjust` | Adjust balances/settle | ✅ | ✅ |

## Authentication flow

```
Request → PlatformAdminFilter (extract token) → platform_admins table → role → PlatformAdminGuard.require(permission)
```

- Platform endpoints under `/api/v1/platform/**` are excluded from `BearerTokenFilter` in `SecurityConfiguration`.
- Platform admin authentication is independent from user JWT authentication.

## API endpoints

### Admin management
| Method | Path | Permission |
|---|---|---|
| GET | `/platform/admins` | `admin:manage` |
| GET | `/platform/admins/{adminId}` | `admin:manage` |
| POST | `/platform/admins` | `admin:manage` |
| PUT | `/platform/admins/{adminId}` | `admin:manage` |
| POST | `/platform/admins/{adminId}/disable` | `admin:manage` |

### Menu management
| Method | Path | Permission |
|---|---|---|
| GET | `/platform/menus` | `menu:manage` |
| GET | `/platform/menus/{menuId}` | `menu:manage` |
| POST | `/platform/menus` | `menu:manage` |
| PUT | `/platform/menus/{menuId}` | `menu:manage` |
| DELETE | `/platform/menus/{menuId}` | `menu:manage` |
| PUT | `/platform/menus/{menuId}/sort` | `menu:manage` |
| GET | `/platform/me/menus` | (authenticated) |

### User management
| Method | Path | Permission |
|---|---|---|
| GET | `/platform/users` | `user:read` |
| GET | `/platform/users/{userId}` | `user:read` |
| POST | `/platform/users/{userId}/disable` | `user:write` |
| POST | `/platform/users/{userId}/enable` | `user:write` |

Query params for user list: `q`, `status`, `verification`, `page`, `size`

### Company management
| Method | Path | Permission |
|---|---|---|
| GET | `/platform/companies` | `company:read` |
| GET | `/platform/companies/{companyId}` | `company:read` |
| POST | `/platform/companies/{companyId}/status` | `company:write` |

Query params for company list: `q`, `verification_status`, `management_status`, `page`, `size`

### Review queries
| Method | Path | Permission |
|---|---|---|
| GET | `/platform/reviews/personal` | `verification:review` |
| GET | `/platform/reviews/company-verifications` | `verification:review` |
| GET | `/platform/reviews/membership-applications` | `membership:review` |
| GET | `/platform/reviews/personal/{userId}` | `verification:review` |
| GET | `/platform/reviews/company-verifications/{requestId}` | `verification:review` |
| GET | `/platform/reviews/membership-applications/{applicationId}` | `membership:review` |

Approval/rejection actions reuse existing `PlatformReviewController` endpoints.

### Ticket management (platform)
| Method | Path | Permission |
|---|---|---|
| GET | `/platform/tickets` | `ticket:read` |
| GET | `/platform/tickets/{ticketId}` | `ticket:read` |
| POST | `/platform/tickets` | `ticket:write` |
| POST | `/platform/tickets/{ticketId}/messages` | `ticket:write` |
| POST | `/platform/tickets/{ticketId}/assign` | `ticket:write` |
| POST | `/platform/tickets/{ticketId}/status` | `ticket:write` |
| POST | `/platform/tickets/{ticketId}/close` | `ticket:write` |

Query params for ticket list: `status`, `category`, `priority`, `assigned_to`, `q`, `page`, `size`

### Ticket management (user-facing)
| Method | Path |
|---|---|
| GET | `/api/v1/me/tickets` |
| GET | `/api/v1/me/tickets/{ticketId}` |
| POST | `/api/v1/me/tickets` |
| POST | `/api/v1/me/tickets/{ticketId}/messages` |

## Database tables

### platform_admins
- `id` UUID PK, `user_id` UUID FK→users, `display_name` VARCHAR(80), `role` VARCHAR(24), `status` VARCHAR(24), timestamps

### platform_menus
- `id` UUID PK, `parent_id` UUID FK→self, `code` VARCHAR(50) UNIQUE, `display_name` VARCHAR(80), `icon` VARCHAR(50), `path` VARCHAR(200), `permission_code` VARCHAR(80), `sort_order` INT, `is_visible` BOOLEAN, `visible_to_operator` BOOLEAN, timestamps

### support_tickets
- `id` UUID PK, `ticket_number` VARCHAR(20) UNIQUE, `creator_user_id` UUID FK→users, `creator_name` VARCHAR(80), `company_id` UUID FK→companies, `title` VARCHAR(200), `category` VARCHAR(50), `priority` VARCHAR(20), `status` VARCHAR(24), `assigned_to_id` UUID FK→platform_admins, `closed_at` TIMESTAMPTZ, timestamps

### support_ticket_messages
- `id` UUID PK, `ticket_id` UUID FK→support_tickets, `sender_type` VARCHAR(20), `sender_id` UUID, `sender_name` VARCHAR(80), `body` TEXT, `created_at` TIMESTAMPTZ

## Preset menu data

| Parent | Name | Path | Permission | Operator visible |
|---|---|---|---|---|
| - | Dashboard | `/` | - | Yes |
| - | Users | `/users` | `user:read` | Yes |
| - | Companies | `/companies` | `company:read` | Yes |
| Reviews | Personal | `/reviews/personal` | `verification:review` | Yes |
| Reviews | Company | `/reviews/company` | `verification:review` | Yes |
| Reviews | Membership | `/reviews/membership` | `membership:review` | Yes |
| - | Tickets | `/tickets` | `ticket:read` | Yes |
| - | Billing | `/billing` | `billing:read` | Yes |
| Settings | Admins | `/settings/admins` | `admin:manage` | No |
| Settings | Menus | `/settings/menus` | `menu:manage` | No |

## Backend module structure

```
com.intelligentrecruitment.platform/
├── admin/
│   ├── api/PlatformAdminController.java
│   └── application/PlatformAdminService.java
├── menu/
│   ├── api/PlatformMenuController.java
│   └── application/MenuService.java
├── review/
│   ├── api/PlatformReviewQueryController.java
│   └── application/ReviewQueryService.java
├── ticket/
│   ├── api/PlatformTicketController.java
│   ├── api/UserTicketController.java
│   └── application/TicketService.java
└── shared/
    └── security/PlatformAdminGuard.java
```

## Frontend app structure

```
apps/admin/
├── src/
│   ├── app/
│   │   ├── layout.tsx, page.tsx, login/page.tsx
│   │   ├── users/[userId]/page.tsx
│   │   ├── companies/[companyId]/page.tsx
│   │   ├── reviews/[type]/[id]/page.tsx
│   │   ├── tickets/[ticketId]/page.tsx, tickets/new/page.tsx
│   │   ├── billing/page.tsx
│   │   └── settings/admins/[adminId]/page.tsx, settings/menus/page.tsx
│   ├── components/layout/admin-shell.tsx, permission-guard.tsx
│   └── lib/admin-api-client.ts, admin-auth.tsx
```

## Migration plan

| Version | Content |
|---|---|
| V3__platform_admin.sql | platform_admins, platform_menus tables |
| V4__support_tickets.sql | support_tickets, support_ticket_messages tables |

## Implementation priority

| Priority | Module | Reason |
|---|---|---|
| P0 | Admin auth + permission model | Foundation for all platform features |
| P0 | Review query APIs | Existing approval endpoints need list views |
| P1 | User/company management | Required for review context |
| P1 | Ticket system | Minimal MVP (create, reply, close) |
| P1 | Menu management | Control operator-visible navigation |
| P2 | Dashboard | Statistics cards, nice-to-have |