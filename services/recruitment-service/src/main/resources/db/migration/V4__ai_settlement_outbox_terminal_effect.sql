-- An execution chooses exactly one terminal BOSS settlement effect.  Retries
-- update the same row; a late usage/cancellation must not create a second row.
ALTER TABLE ai_settlement_outbox
    ADD CONSTRAINT uk_ai_settlement_outbox_execution UNIQUE (execution_id);
