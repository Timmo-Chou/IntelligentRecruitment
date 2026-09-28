CREATE TABLE IF NOT EXISTS ai_webhook_events (
    event_id VARCHAR(200) PRIMARY KEY,
    ai_task_id VARCHAR(200) NOT NULL,
    business_task_id VARCHAR(200) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence >= 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ai_webhook_task_sequence
    ON ai_webhook_events(ai_task_id, sequence);
CREATE INDEX IF NOT EXISTS ix_ai_webhook_task_received
    ON ai_webhook_events(ai_task_id, received_at DESC);
