ALTER TABLE jd_drafts
    ADD COLUMN salary_range VARCHAR(200) NOT NULL DEFAULT '',
    ADD COLUMN benefits TEXT NOT NULL DEFAULT '',
    ADD COLUMN nice_to_haves TEXT NOT NULL DEFAULT '';

ALTER TABLE jobs
    ADD COLUMN salary_range VARCHAR(200) NOT NULL DEFAULT '',
    ADD COLUMN benefits TEXT NOT NULL DEFAULT '',
    ADD COLUMN nice_to_haves TEXT NOT NULL DEFAULT '';

CREATE TABLE jd_source_files (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    recruitment_task_id UUID NOT NULL REFERENCES recruitment_tasks(id) ON DELETE CASCADE,
    file_asset_id UUID NOT NULL REFERENCES file_assets(id),
    extracted_text TEXT NOT NULL DEFAULT '',
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (recruitment_task_id, file_asset_id)
);

CREATE INDEX idx_jd_source_files_task ON jd_source_files (workspace_id, recruitment_task_id, created_at);
CREATE TRIGGER trg_jd_source_files_scope BEFORE INSERT OR UPDATE ON jd_source_files
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
