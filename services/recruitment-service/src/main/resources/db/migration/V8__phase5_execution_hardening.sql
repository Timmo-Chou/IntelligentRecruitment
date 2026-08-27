ALTER TABLE screening_quotes
    ADD COLUMN job_version_id UUID REFERENCES job_versions(id),
    ADD COLUMN candidate_versions_hash VARCHAR(64),
    ADD COLUMN consumed_at TIMESTAMPTZ,
    ADD COLUMN consumed_by_run_id UUID;

ALTER TABLE screening_runs
    ADD COLUMN quote_id UUID REFERENCES screening_quotes(id),
    ADD COLUMN parent_run_id UUID REFERENCES screening_runs(id),
    ADD COLUMN root_run_id UUID REFERENCES screening_runs(id);

ALTER TABLE screening_run_items
    ADD COLUMN source_run_item_id UUID REFERENCES screening_run_items(id);

ALTER TABLE screening_quotes
    ADD CONSTRAINT fk_screening_quote_consumed_run
        FOREIGN KEY (consumed_by_run_id) REFERENCES screening_runs(id);

CREATE UNIQUE INDEX uk_screening_run_quote
    ON screening_runs (quote_id)
    WHERE quote_id IS NOT NULL;

CREATE INDEX idx_screening_runs_root
    ON screening_runs (root_run_id, created_at);

CREATE INDEX idx_screening_outbox_pending
    ON outbox_events (event_type, status, next_attempt_at, created_at)
    WHERE event_type = 'SCREENING_RUN_REQUESTED';
