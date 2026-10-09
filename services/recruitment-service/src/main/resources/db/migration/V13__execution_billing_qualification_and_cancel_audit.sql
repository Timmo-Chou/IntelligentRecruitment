ALTER TABLE ai_execution_records
 ADD COLUMN result_validity VARCHAR(40),ADD COLUMN billable_unit_count INTEGER,
 ADD COLUMN billing_reason_code VARCHAR(100),ADD COLUMN cancellation_result_ciphertext TEXT,ADD COLUMN platform_status VARCHAR(40),ADD COLUMN result_ciphertext TEXT;
