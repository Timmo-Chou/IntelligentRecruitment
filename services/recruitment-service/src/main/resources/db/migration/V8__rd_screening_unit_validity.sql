ALTER TABLE screening_run_items
    ADD COLUMN IF NOT EXISTS result_validity VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED_RESULT',
    ADD COLUMN IF NOT EXISTS billable_unit_count SMALLINT,
    ADD COLUMN IF NOT EXISTS billing_reason_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS billing_evidence_ref VARCHAR(500);

ALTER TABLE screening_run_items
    ADD CONSTRAINT screening_run_items_billable_unit_count_check
    CHECK (billable_unit_count IS NULL OR billable_unit_count IN (0, 1));

ALTER TABLE screening_results ALTER COLUMN score DROP NOT NULL;
ALTER TABLE screening_results ALTER COLUMN score TYPE NUMERIC(5,2) USING score::NUMERIC(5,2);
ALTER TABLE screening_results ALTER COLUMN level DROP NOT NULL;
ALTER TABLE screening_results
    ADD COLUMN IF NOT EXISTS evaluation_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS eligibility VARCHAR(32),
    ADD COLUMN IF NOT EXISTS recommendation VARCHAR(40);
