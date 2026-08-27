ALTER TABLE ai_runs
    ADD COLUMN input_payload JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE TABLE jd_run_events (
    event_id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES ai_runs(id) ON DELETE CASCADE,
    company_id UUID REFERENCES companies(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    recruitment_task_id UUID NOT NULL REFERENCES recruitment_tasks(id),
    event_type VARCHAR(32) NOT NULL,
    data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_jd_run_events_replay
    ON jd_run_events (workspace_id, recruitment_task_id, event_id);

CREATE TRIGGER trg_jd_run_events_scope BEFORE INSERT OR UPDATE ON jd_run_events
    FOR EACH ROW EXECUTE FUNCTION enforce_workspace_company_scope();

CREATE INDEX idx_jd_outbox_pending
    ON outbox_events (status, next_attempt_at, created_at)
    WHERE event_type = 'JD_RUN_REQUESTED';
