ALTER TABLE screening_results
    ALTER COLUMN score TYPE NUMERIC USING score::NUMERIC;

ALTER TABLE ai_webhook_events
    ALTER COLUMN sequence TYPE BIGINT;

DROP INDEX IF EXISTS ux_ai_webhook_task_sequence;
CREATE INDEX IF NOT EXISTS ix_ai_webhook_task_sequence
    ON ai_webhook_events(ai_task_id, sequence);

ALTER TABLE ai_webhook_events
    ADD COLUMN IF NOT EXISTS processing_status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    ADD COLUMN IF NOT EXISTS processing_error_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS payload_sha256 VARCHAR(64);

CREATE TABLE IF NOT EXISTS ai_webhook_event_conflicts (
    event_id VARCHAR(200) NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    event_body JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, payload_sha256)
);
