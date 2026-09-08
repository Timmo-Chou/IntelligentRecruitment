-- BOSS is authoritative for identity, company membership and lifecycle. These are rebuildable
-- Recruitment projections only; they must never be used to grant access.
CREATE TABLE IF NOT EXISTS boss_company_projections (
    company_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    legal_name VARCHAR(200) NOT NULL,
    entity_type VARCHAR(24) NOT NULL,
    company_status VARCHAR(24) NOT NULL,
    tenant_status VARCHAR(24) NOT NULL,
    synchronized_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS boss_event_inbox (
    event_id VARCHAR(128) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id UUID,
    payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,
    failure_reason VARCHAR(500)
);

-- Existing recruitment tables still physically reference workspace_id. New BOSS-scoped records
-- use Company ID as that temporary partition key while APIs move to /companies/{companyId}.
CREATE TABLE IF NOT EXISTS boss_legacy_workspace_links (
    legacy_workspace_id UUID PRIMARY KEY,
    company_id UUID NOT NULL UNIQUE,
    tenant_id UUID NOT NULL,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    migration_source VARCHAR(40) NOT NULL
);
