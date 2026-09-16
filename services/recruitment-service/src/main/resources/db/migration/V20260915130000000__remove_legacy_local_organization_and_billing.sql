-- Recruitment Tenant identity, membership, plans and credits are owned exclusively by BOSS.
-- This development-only baseline cleanup deliberately removes every local Company/workspace
-- projection and monetary ledger instead of preserving a compatibility or dual-write path.
DROP FUNCTION IF EXISTS public.fn_enforce_recruitment_task_linked_candidate_scope() CASCADE;
DROP FUNCTION IF EXISTS public.enforce_workspace_tenant_scope() CASCADE;

DROP TABLE IF EXISTS public.billing_reservation_allocations CASCADE;
DROP TABLE IF EXISTS public.billing_ledger_entries CASCADE;
DROP TABLE IF EXISTS public.billing_reservations CASCADE;
DROP TABLE IF EXISTS public.credit_lots CASCADE;
DROP TABLE IF EXISTS public.recharge_orders CASCADE;
DROP TABLE IF EXISTS public.billing_accounts CASCADE;

DROP TABLE IF EXISTS public.enterprise_registration_projections CASCADE;
DROP TABLE IF EXISTS public.tenant_membership_projections CASCADE;
DROP TABLE IF EXISTS public.tenant_projections CASCADE;
DROP TABLE IF EXISTS public.workspaces CASCADE;
