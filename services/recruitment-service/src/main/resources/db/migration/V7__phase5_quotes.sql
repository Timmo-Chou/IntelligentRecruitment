CREATE TABLE screening_quotes (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    plan_version_id UUID NOT NULL REFERENCES screening_plan_versions(id),
    candidate_ids_hash VARCHAR(64) NOT NULL,
    candidate_count INTEGER NOT NULL CHECK (candidate_count > 0),
    pricing_version VARCHAR(80) NOT NULL,
    unit_price_minor BIGINT NOT NULL CHECK (unit_price_minor >= 0),
    estimated_amount_minor BIGINT NOT NULL CHECK (estimated_amount_minor >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_screening_quotes_scope_expiry
    ON screening_quotes (workspace_id, expires_at DESC);

CREATE TRIGGER trg_screening_quotes_scope
BEFORE INSERT OR UPDATE ON screening_quotes
FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();
