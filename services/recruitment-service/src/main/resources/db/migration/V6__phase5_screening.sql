CREATE TABLE screening_plans (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    job_id UUID NOT NULL REFERENCES jobs(id),
    current_version_id UUID,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE screening_plan_versions (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    plan_id UUID NOT NULL REFERENCES screening_plans(id),
    version_number INTEGER NOT NULL,
    rules_snapshot JSONB NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (plan_id, version_number)
);
ALTER TABLE screening_plans ADD CONSTRAINT fk_screening_plan_version
    FOREIGN KEY (current_version_id) REFERENCES screening_plan_versions(id);

CREATE TABLE screening_runs (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    job_id UUID NOT NULL REFERENCES jobs(id),
    job_version_id UUID NOT NULL REFERENCES job_versions(id),
    plan_version_id UUID NOT NULL REFERENCES screening_plan_versions(id),
    provider_task_id VARCHAR(200),
    status VARCHAR(32) NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    scenario VARCHAR(32) NOT NULL,
    pricing_version VARCHAR(64) NOT NULL,
    unit_price_minor BIGINT NOT NULL,
    estimated_amount_minor BIGINT NOT NULL,
    settled_amount_minor BIGINT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (workspace_id, idempotency_key)
);
CREATE INDEX idx_screening_runs_workspace_created ON screening_runs (workspace_id, created_at DESC);

CREATE TABLE screening_run_items (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    run_id UUID NOT NULL REFERENCES screening_runs(id),
    candidate_id UUID NOT NULL REFERENCES candidates(id),
    parse_version_id UUID NOT NULL REFERENCES resume_parse_versions(id),
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(100),
    attempt_number INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (run_id, candidate_id, attempt_number)
);

CREATE TABLE screening_results (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    run_item_id UUID NOT NULL UNIQUE REFERENCES screening_run_items(id),
    score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
    level VARCHAR(32) NOT NULL,
    matched_points JSONB NOT NULL,
    unmatched_points JSONB NOT NULL,
    negotiable_points JSONB NOT NULL,
    missing_information JSONB NOT NULL,
    risks JSONB NOT NULL,
    evidence JSONB NOT NULL,
    result_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TRIGGER trg_screening_plans_scope BEFORE INSERT OR UPDATE ON screening_plans
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_screening_plan_versions_scope BEFORE INSERT OR UPDATE ON screening_plan_versions
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_screening_runs_scope BEFORE INSERT OR UPDATE ON screening_runs
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_screening_run_items_scope BEFORE INSERT OR UPDATE ON screening_run_items
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
CREATE TRIGGER trg_screening_results_scope BEFORE INSERT OR UPDATE ON screening_results
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
