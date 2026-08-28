ALTER TABLE ai_runs
    ADD COLUMN policy_decision JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN execution_context JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE screening_runs
    ADD COLUMN policy_decision JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN execution_context JSONB NOT NULL DEFAULT '{}'::jsonb;
