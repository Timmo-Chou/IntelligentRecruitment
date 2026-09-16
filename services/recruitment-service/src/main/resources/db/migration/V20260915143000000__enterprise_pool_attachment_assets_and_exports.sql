CREATE TABLE enterprise_pool_attachment_assets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    talent_pool_copy_id UUID NOT NULL REFERENCES enterprise_talent_pool_copies(id),
    source_file_asset_id UUID NOT NULL,
    object_key VARCHAR(500) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, talent_pool_copy_id, source_file_asset_id)
);
CREATE INDEX idx_enterprise_pool_attachment_copy ON enterprise_pool_attachment_assets(talent_pool_copy_id, lifecycle_status);

CREATE TABLE enterprise_pool_export_logs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    pool_type VARCHAR(32) NOT NULL CHECK (pool_type IN ('TALENT','JOB')),
    actor_user_id UUID NOT NULL,
    filter_criteria JSONB NOT NULL,
    fields JSONB NOT NULL,
    record_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_enterprise_pool_export_logs_tenant ON enterprise_pool_export_logs(tenant_id, created_at DESC);
