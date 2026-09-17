ALTER TABLE resume_files
    ADD COLUMN IF NOT EXISTS provider_task_id UUID,
    ADD COLUMN IF NOT EXISTS parse_idempotency_key VARCHAR(160),
    ADD COLUMN IF NOT EXISTS parse_attempts INTEGER NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS ux_resume_files_parse_idempotency
    ON resume_files(tenant_id, parse_idempotency_key)
    WHERE parse_idempotency_key IS NOT NULL;
