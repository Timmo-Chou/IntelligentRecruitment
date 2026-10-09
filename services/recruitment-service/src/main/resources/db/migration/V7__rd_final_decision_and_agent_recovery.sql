ALTER TABLE ai_execution_records
    ADD COLUMN IF NOT EXISTS agent_id VARCHAR(80),
    ADD COLUMN IF NOT EXISTS operation VARCHAR(40),
    ADD COLUMN IF NOT EXISTS attempt_id UUID,
    ADD COLUMN IF NOT EXISTS route_config_version VARCHAR(80),
    ADD COLUMN IF NOT EXISTS input_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS pricing_snapshot JSONB,
    ADD COLUMN IF NOT EXISTS agent_constraints JSONB,
    ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reconciliation_deadline TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cancellation_intent_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS final_decision VARCHAR(16),
    ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE ai_execution_records ALTER COLUMN model_id DROP NOT NULL;
ALTER TABLE ai_execution_records ALTER COLUMN tokenizer_id DROP NOT NULL;

ALTER TABLE ai_settlement_outbox DROP CONSTRAINT IF EXISTS ai_settlement_outbox_effect_type_check;
-- Drop the legacy CHECK before rewriting its old value; otherwise PostgreSQL
-- rejects the UPDATE because RELEASE is not yet part of the old constraint.
UPDATE ai_settlement_outbox SET effect_type='RELEASE' WHERE effect_type='CANCELLATION';
ALTER TABLE ai_settlement_outbox ADD CONSTRAINT ai_settlement_outbox_effect_type_check CHECK (effect_type IN ('USAGE','RELEASE'));

CREATE TABLE IF NOT EXISTS ai_execution_reconciliation_events (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES ai_execution_records(id),
    event_type VARCHAR(40) NOT NULL,
    evidence_reference VARCHAR(500),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
