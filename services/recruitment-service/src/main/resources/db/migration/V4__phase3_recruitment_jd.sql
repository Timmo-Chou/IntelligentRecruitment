CREATE TABLE recruitment_tasks (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    title VARCHAR(200) NOT NULL,
    initial_requirement TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (workspace_id, idempotency_key)
);
CREATE INDEX idx_recruitment_tasks_workspace_updated
    ON recruitment_tasks (workspace_id, updated_at DESC);

CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    recruitment_task_id UUID NOT NULL UNIQUE REFERENCES recruitment_tasks(id),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    role VARCHAR(24) NOT NULL,
    content TEXT NOT NULL,
    capability VARCHAR(64),
    sequence_number INTEGER NOT NULL,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (conversation_id, sequence_number)
);
CREATE INDEX idx_messages_conversation_sequence
    ON messages (conversation_id, sequence_number);

CREATE TABLE ai_runs (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    recruitment_task_id UUID NOT NULL REFERENCES recruitment_tasks(id),
    capability VARCHAR(64) NOT NULL,
    provider_task_id VARCHAR(200),
    status VARCHAR(32) NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    attempt_number INTEGER NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    pricing_version VARCHAR(64) NOT NULL,
    estimated_amount_minor BIGINT NOT NULL,
    settled_amount_minor BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(100),
    error_message VARCHAR(500),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (workspace_id, idempotency_key)
);
CREATE INDEX idx_ai_runs_task_created ON ai_runs (recruitment_task_id, created_at DESC);

CREATE TABLE jd_drafts (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    recruitment_task_id UUID NOT NULL UNIQUE REFERENCES recruitment_tasks(id),
    source_ai_run_id UUID REFERENCES ai_runs(id),
    revision INTEGER NOT NULL,
    title VARCHAR(200) NOT NULL,
    company_name VARCHAR(200) NOT NULL,
    location VARCHAR(200) NOT NULL DEFAULT '',
    experience_level VARCHAR(80) NOT NULL DEFAULT '',
    education VARCHAR(80) NOT NULL DEFAULT '',
    job_type VARCHAR(50) NOT NULL DEFAULT '全职',
    responsibilities TEXT NOT NULL DEFAULT '',
    requirements TEXT NOT NULL DEFAULT '',
    skills TEXT NOT NULL DEFAULT '',
    talent_profile TEXT NOT NULL DEFAULT '',
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    updated_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE jobs ADD COLUMN company_id UUID REFERENCES companies(id);
ALTER TABLE jobs ADD COLUMN current_version_id UUID;
ALTER TABLE jobs ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE jobs ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE jobs ADD COLUMN recruitment_task_id UUID REFERENCES recruitment_tasks(id);
ALTER TABLE jobs ADD COLUMN talent_profile TEXT NOT NULL DEFAULT '';
ALTER TABLE jobs ADD COLUMN warnings JSONB NOT NULL DEFAULT '[]'::jsonb;
UPDATE jobs j SET company_id = w.company_id FROM workspaces w WHERE w.id = j.workspace_id;
CREATE UNIQUE INDEX uk_jobs_recruitment_task ON jobs (recruitment_task_id)
    WHERE recruitment_task_id IS NOT NULL;
CREATE INDEX idx_jobs_company_workspace ON jobs (company_id, workspace_id);

ALTER TABLE job_versions ADD COLUMN company_id UUID REFERENCES companies(id);
ALTER TABLE job_versions ADD COLUMN workspace_id UUID REFERENCES workspaces(id);
ALTER TABLE job_versions ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED';
ALTER TABLE job_versions ADD COLUMN source_ai_run_id UUID REFERENCES ai_runs(id);
ALTER TABLE job_versions ADD COLUMN confirmed_at TIMESTAMPTZ;
UPDATE job_versions v
SET company_id = j.company_id, workspace_id = j.workspace_id, confirmed_at = v.created_at
FROM jobs j WHERE j.id = v.job_id;
ALTER TABLE job_versions ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX idx_job_versions_workspace_job
    ON job_versions (workspace_id, job_id, version_number DESC);

UPDATE jobs j SET current_version_id = latest.id
FROM (
    SELECT DISTINCT ON (job_id) id, job_id
    FROM job_versions ORDER BY job_id, version_number DESC
) latest WHERE latest.job_id = j.id;
ALTER TABLE jobs ADD CONSTRAINT fk_jobs_current_version
    FOREIGN KEY (current_version_id) REFERENCES job_versions(id);

CREATE OR REPLACE FUNCTION enforce_workspace_company_scope()
RETURNS trigger AS $$
DECLARE
    expected_company_id UUID;
BEGIN
    SELECT company_id INTO expected_company_id FROM workspaces WHERE id = NEW.workspace_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'workspace does not exist';
    END IF;
    IF NEW.company_id IS DISTINCT FROM expected_company_id THEN
        RAISE EXCEPTION 'company_id does not match workspace scope';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_recruitment_tasks_scope BEFORE INSERT OR UPDATE ON recruitment_tasks
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_conversations_scope BEFORE INSERT OR UPDATE ON conversations
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_messages_scope BEFORE INSERT OR UPDATE ON messages
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_ai_runs_scope BEFORE INSERT OR UPDATE ON ai_runs
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_jd_drafts_scope BEFORE INSERT OR UPDATE ON jd_drafts
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_jobs_scope BEFORE INSERT OR UPDATE ON jobs
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_job_versions_scope BEFORE INSERT OR UPDATE ON job_versions
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
