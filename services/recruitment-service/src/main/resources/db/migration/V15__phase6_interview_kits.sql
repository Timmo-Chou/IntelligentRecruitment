CREATE TABLE interview_kits (
    id UUID PRIMARY KEY, company_id UUID REFERENCES companies(id), workspace_id UUID NOT NULL REFERENCES workspaces(id),
    job_version_id UUID, candidate_id UUID NOT NULL REFERENCES candidates(id), screening_result_id UUID,
    status VARCHAR(24) NOT NULL, created_by UUID NOT NULL REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE interview_kit_versions (
    id UUID PRIMARY KEY, company_id UUID REFERENCES companies(id), workspace_id UUID NOT NULL REFERENCES workspaces(id),
    kit_id UUID NOT NULL REFERENCES interview_kits(id), screening_result_id UUID, version_no INT NOT NULL, status VARCHAR(24) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL, UNIQUE(kit_id, version_no)
);
CREATE TABLE interview_questions (
    id UUID PRIMARY KEY, company_id UUID REFERENCES companies(id), workspace_id UUID NOT NULL REFERENCES workspaces(id),
    kit_version_id UUID NOT NULL REFERENCES interview_kit_versions(id) ON DELETE CASCADE, category VARCHAR(24) NOT NULL,
    content TEXT NOT NULL, rationale TEXT, focus_points TEXT, scoring_points TEXT, evidence_refs TEXT, sort_order INT NOT NULL
);
CREATE INDEX ix_interview_kits_workspace ON interview_kits(workspace_id, created_at DESC);
