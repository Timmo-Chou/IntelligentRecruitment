ALTER TABLE ai_execution_records ADD COLUMN result_processing_status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED'
    CHECK(result_processing_status IN ('NOT_STARTED','PROCESSING','PROCESSING_SUCCEEDED','PROCESSING_FAILED'));
ALTER TABLE ai_execution_records ADD COLUMN result_processing_error_code VARCHAR(100);
CREATE INDEX idx_ai_execution_records_result_processing ON ai_execution_records(result_processing_status,updated_at);
