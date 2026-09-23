CREATE TABLE ai_execution_records (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL UNIQUE,
    tenant_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    business_task_id VARCHAR(255) NOT NULL,
    agent_task_id VARCHAR(255),
    authorization_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    grant_id UUID NOT NULL,
    capability VARCHAR(100) NOT NULL,
    product_domain VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    model_id VARCHAR(200) NOT NULL,
    tokenizer_id VARCHAR(200) NOT NULL,
    authorization_expires_at TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0,
    result_reference VARCHAR(500),
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, idempotency_key)
);
CREATE INDEX idx_ai_execution_records_agent_task ON ai_execution_records(agent_task_id);
CREATE TABLE ai_settlement_outbox (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES ai_execution_records(id),
    effect_type VARCHAR(24) NOT NULL CHECK (effect_type IN ('USAGE','CANCELLATION')),
    dedupe_key VARCHAR(255) NOT NULL UNIQUE,
    payload_json JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);
