ALTER TABLE screening_run_items
    ADD COLUMN provider_task_id VARCHAR(200);

CREATE INDEX idx_screening_run_items_pending
    ON screening_run_items (run_id, status, created_at)
    WHERE status IN ('PENDING', 'PROCESSING');
