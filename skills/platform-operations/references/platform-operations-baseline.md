# Platform Operations Baseline

## Ownership

| Capability | Authority |
|---|---|
| User identity and sessions | BOSS |
| Tenant, membership and permissions | BOSS |
| Platform admin authentication and RBAC | BOSS |
| Product, entitlement, order and credits | BOSS |
| Enterprise and membership review | BOSS |
| Recruitment business records | IntelligentRecruitment |
| Recruitment tickets and messages | IntelligentRecruitment |
| Authorized model execution | AIAgentPlatform |

## Admin integration

Admin uses the BOSS platform-admin endpoints for bootstrap, registration, login, refresh, administrator management, menus, products, entitlements, orders and review queries. IntelligentRecruitment has no platform-admin controller, local admin guard, admin table or review-query BFF.

## User-facing ticket API

| Method | Path |
|---|---|
| GET | /api/v1/tenants/{tenantId}/tickets |
| GET | /api/v1/tenants/{tenantId}/tickets/{ticketId} |
| POST | /api/v1/tenants/{tenantId}/tickets |
| POST | /api/v1/tenants/{tenantId}/tickets/{ticketId}/messages |

The authenticated BOSS user UUID is used as creator_user_id. The service checks Tenant access before reading or writing a ticket.

## Internal ticket API

BOSS calls the recruitment-service internal ticket API with the configured service client credentials. It may list, create, reply to, assign, update, or close tickets. The internal API never trusts browser-provided platform credentials.

## Recruitment ticket tables

### support_tickets

- creator_user_id: BOSS user UUID; no local identity foreign key.
- creator_name: display-name snapshot captured when the ticket is created.
- tenant_id: BOSS Tenant UUID.
- assigned_to_id: BOSS administrator UUID; no local administrator foreign key.
- title, category, priority, status and timestamps.

### support_ticket_messages

- ticket ID, sender type, sender UUID, sender display name, body and timestamp.
- Messages are append-only.

## No local platform tables

The recruitment-service baseline contains no local platform-admin, platform-menu, pricing, recharge, identity-review or local billing tables. Those capabilities remain in BOSS.

## Validation

- Flyway starts from the clean development baseline.
- The six removed legacy identity/admin tables are absent.
- No runtime code references old platform-admin authentication or local review storage.
- Ticket ownership is verified using the authenticated BOSS user UUID and Tenant access.
