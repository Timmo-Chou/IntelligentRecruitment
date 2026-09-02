-- V23: AI简历解析功能 — 任务类型/职位关联、简历源文件、解析草稿

-- 1. recruitment_tasks 增加功能类型与关联职位（职位非必选，可用于解析匹配分析）
ALTER TABLE recruitment_tasks
    ADD COLUMN feature_type VARCHAR(32),
    ADD COLUMN linked_job_id UUID REFERENCES jobs(id) ON DELETE SET NULL;

CREATE INDEX idx_recruitment_tasks_feature ON recruitment_tasks (workspace_id, feature_type, updated_at DESC);

-- 2. 简历源文件：用户上传的原始简历（PDF/DOCX等），与 recruitment_task 关联
CREATE TABLE resume_source_files (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    recruitment_task_id UUID NOT NULL REFERENCES recruitment_tasks(id) ON DELETE CASCADE,
    file_asset_id UUID NOT NULL REFERENCES file_assets(id),
    filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL DEFAULT '',
    size_bytes BIGINT NOT NULL DEFAULT 0,
    extracted_text TEXT NOT NULL DEFAULT '',
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (recruitment_task_id, file_asset_id)
);
CREATE INDEX idx_resume_source_files_task ON resume_source_files (workspace_id, recruitment_task_id, created_at);
CREATE TRIGGER trg_resume_source_files_scope BEFORE INSERT OR UPDATE ON resume_source_files
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();

-- 3. 简历解析草稿：AI输出的解析结果（大文本形式），支持人工编辑保存与确认
CREATE TABLE resume_parse_drafts (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    recruitment_task_id UUID NOT NULL REFERENCES recruitment_tasks(id) ON DELETE CASCADE,
    source_ai_run_id UUID REFERENCES ai_runs(id) ON DELETE SET NULL,
    resume_source_file_id UUID REFERENCES resume_source_files(id) ON DELETE SET NULL,
    content TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    revision INTEGER NOT NULL DEFAULT 1,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (recruitment_task_id, revision)
);
CREATE INDEX idx_resume_parse_drafts_task ON resume_parse_drafts (workspace_id, recruitment_task_id, revision DESC);
CREATE TRIGGER trg_resume_parse_drafts_scope BEFORE INSERT OR UPDATE ON resume_parse_drafts
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
