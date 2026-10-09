CREATE TABLE ai_execution_close_outbox (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL UNIQUE REFERENCES ai_execution_records(id),
    closure_id UUID NOT NULL UNIQUE,
    agent_task_id VARCHAR(255) NOT NULL,
    tenant_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    agent_id VARCHAR(80) NOT NULL,
    operation VARCHAR(40) NOT NULL,
    route_config_version VARCHAR(80) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    expected_state_version BIGINT NOT NULL CHECK(expected_state_version>=0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PROCESSING','DELIVERED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until TIMESTAMPTZ,
    last_error VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ai_execution_close_outbox_claim ON ai_execution_close_outbox(status,next_attempt_at,locked_until);
