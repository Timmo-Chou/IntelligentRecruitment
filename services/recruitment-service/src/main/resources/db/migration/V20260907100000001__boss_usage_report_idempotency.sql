-- Stable timestamps make usage retries safe across Recruitment process restarts.
CREATE TABLE IF NOT EXISTS boss_usage_reports (
    usage_event_id VARCHAR(160) PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
