# Backend Baseline

## Selected stack

- Java 21 and Spring Boot.
- Spring MVC, Spring Security and Jakarta Bean Validation.
- JdbcTemplate for the existing recruitment schema and transactional operations.
- Flyway and PostgreSQL.
- Redis, RabbitMQ and S3-compatible object storage for local infrastructure.
- JUnit 5, Spring Boot Test and contract tests.

## Service boundaries

- BOSS is the authority for identity, sessions, Tenant, membership, permissions, review, products, entitlements, orders and credits.
- IntelligentRecruitment owns recruitment tasks, jobs, candidates, resumes, screening, interviews and support tickets.
- AIAgentPlatform executes only BOSS-authorized AI tasks.

## Data model

Recruitment business tables use tenant_id and store BOSS user UUIDs for actor and creator fields. Local tables do not become an authority for identity, membership or billing.

The support_tickets.creator_name field is a display snapshot; creator_user_id is the authenticated BOSS user UUID. Tenant names and authorization decisions come from BOSS.

## API and task rules

- APIs are versioned under /api/v1.
- Billable AI commands require BOSS authorization, idempotency and execution context.
- Workers claim work idempotently and retain attempt history.
- File and PII operations are authorized and audited.
- Cross-service failures must return stable machine-readable error codes.

## Authentication

IR user authentication is a BOSS-backed BFF flow. IR does not issue a second user identity, administrator identity or local session. Platform Admin authentication is handled by BOSS and the Admin application.

## Current exclusions

Personal identity verification submission is not exposed by the recruitment service in the current release. It must not be represented by a local placeholder endpoint or synthetic status.
