-- 职位表：归属于某个 Workspace，禁止跨 Workspace 查询
CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    title VARCHAR(200) NOT NULL,
    company_name VARCHAR(200) NOT NULL,
    location VARCHAR(200) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    requirements TEXT NOT NULL DEFAULT '',
    skills TEXT NOT NULL DEFAULT '',
    experience_level VARCHAR(50) NOT NULL DEFAULT '',
    education VARCHAR(50) NOT NULL DEFAULT '',
    job_type VARCHAR(50) NOT NULL DEFAULT '全职',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_jobs_workspace_status ON jobs (workspace_id, status);
CREATE INDEX idx_jobs_workspace_created ON jobs (workspace_id, created_at DESC);

-- 职位版本快照：记录每次修改
CREATE TABLE job_versions (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    snapshot JSONB NOT NULL,
    change_summary VARCHAR(500) NOT NULL DEFAULT '',
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (job_id, version_number)
);

CREATE INDEX idx_job_versions_job ON job_versions (job_id, version_number DESC);