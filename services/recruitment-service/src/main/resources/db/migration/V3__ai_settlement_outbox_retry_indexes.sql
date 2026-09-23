CREATE INDEX IF NOT EXISTS idx_ai_settlement_outbox_claim
    ON ai_settlement_outbox(status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_execution_records_idempotency
    ON ai_execution_records(tenant_id, idempotency_key);
ALTER TABLE ai_execution_records ADD COLUMN IF NOT EXISTS authorization_expires_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_ai_execution_records_expiry
    ON ai_execution_records(status, authorization_expires_at);
