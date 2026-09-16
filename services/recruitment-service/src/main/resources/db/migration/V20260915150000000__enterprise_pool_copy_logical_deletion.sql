ALTER TABLE enterprise_talent_pool_copies ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE enterprise_job_pool_copies ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_enterprise_talent_pool_copies_active ON enterprise_talent_pool_copies(tenant_id, synchronized_at DESC) WHERE lifecycle_status='ACTIVE';
CREATE INDEX IF NOT EXISTS idx_enterprise_job_pool_copies_active ON enterprise_job_pool_copies(tenant_id, synchronized_at DESC) WHERE lifecycle_status='ACTIVE';
