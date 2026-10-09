CREATE TABLE ai_file_download_grant_audits (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    ai_task_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    file_asset_id UUID NOT NULL REFERENCES file_assets(id),
    purpose VARCHAR(32) NOT NULL CHECK (purpose IN ('RESUME_ANALYSIS','CANDIDATE_MATCH')),
    sha256 CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ai_file_download_grant_task ON ai_file_download_grant_audits(ai_task_id,created_at DESC);
