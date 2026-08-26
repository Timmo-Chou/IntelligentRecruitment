CREATE TABLE file_assets (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    object_key VARCHAR(500) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    scan_status VARCHAR(32) NOT NULL,
    lifecycle_status VARCHAR(32) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (workspace_id, sha256)
);

CREATE TABLE candidates (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    display_name_masked VARCHAR(120) NOT NULL,
    full_name_ciphertext TEXT NOT NULL,
    email_ciphertext TEXT,
    phone_ciphertext TEXT,
    current_parse_version_id UUID,
    status VARCHAR(32) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_candidates_workspace_updated ON candidates (workspace_id, updated_at DESC);

CREATE TABLE resume_files (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    candidate_id UUID NOT NULL REFERENCES candidates(id),
    file_asset_id UUID NOT NULL REFERENCES file_assets(id),
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(100),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (workspace_id, file_asset_id)
);

CREATE TABLE resume_parse_versions (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    candidate_id UUID NOT NULL REFERENCES candidates(id),
    resume_file_id UUID NOT NULL REFERENCES resume_files(id),
    version_number INTEGER NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    headline VARCHAR(300) NOT NULL DEFAULT '',
    years_experience INTEGER NOT NULL DEFAULT 0,
    highest_education VARCHAR(100) NOT NULL DEFAULT '',
    skills JSONB NOT NULL DEFAULT '[]'::jsonb,
    work_experience JSONB NOT NULL DEFAULT '[]'::jsonb,
    education_experience JSONB NOT NULL DEFAULT '[]'::jsonb,
    summary TEXT NOT NULL DEFAULT '',
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    raw_text TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (candidate_id, version_number)
);

ALTER TABLE candidates ADD CONSTRAINT fk_candidates_current_parse
    FOREIGN KEY (current_parse_version_id) REFERENCES resume_parse_versions(id);
CREATE INDEX idx_parse_versions_workspace_candidate
    ON resume_parse_versions (workspace_id, candidate_id, version_number DESC);

CREATE TRIGGER trg_file_assets_scope BEFORE INSERT OR UPDATE ON file_assets
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_candidates_scope BEFORE INSERT OR UPDATE ON candidates
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_resume_files_scope BEFORE INSERT OR UPDATE ON resume_files
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_resume_parse_versions_scope BEFORE INSERT OR UPDATE ON resume_parse_versions
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
