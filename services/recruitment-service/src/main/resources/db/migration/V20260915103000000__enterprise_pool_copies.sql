-- Enterprise pools are immutable copies of a member's own source records.  They never replace source ownership.
CREATE TABLE enterprise_talent_pool_copies (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    source_candidate_id UUID NOT NULL,
    source_owner_user_id UUID NOT NULL,
    snapshot JSONB NOT NULL,
    source_updated_at TIMESTAMPTZ NOT NULL,
    synchronized_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, source_candidate_id)
);
CREATE INDEX idx_enterprise_talent_pool_tenant ON enterprise_talent_pool_copies(tenant_id, synchronized_at DESC);
CREATE TABLE enterprise_job_pool_copies (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    source_job_id UUID NOT NULL,
    source_owner_user_id UUID NOT NULL,
    snapshot JSONB NOT NULL,
    source_updated_at TIMESTAMPTZ NOT NULL,
    synchronized_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, source_job_id)
);
CREATE INDEX idx_enterprise_job_pool_tenant ON enterprise_job_pool_copies(tenant_id, synchronized_at DESC);
