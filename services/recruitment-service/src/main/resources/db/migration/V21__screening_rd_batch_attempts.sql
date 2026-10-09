CREATE TABLE screening_execution_batches (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES screening_runs(id) ON DELETE CASCADE,
    batch_order INTEGER NOT NULL CHECK(batch_order>0),
    candidate_count INTEGER NOT NULL CHECK(candidate_count BETWEEN 1 AND 5),
    idempotency_key VARCHAR(220) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    provider_task_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(run_id,batch_order),UNIQUE(tenant_id,idempotency_key)
);
ALTER TABLE screening_run_items ADD COLUMN batch_id UUID REFERENCES screening_execution_batches(id);
ALTER TABLE screening_run_items ADD COLUMN batch_order INTEGER;
ALTER TABLE screening_run_items ADD CONSTRAINT ck_screening_item_batch_binding
    CHECK((batch_id IS NULL AND batch_order IS NULL) OR (batch_id IS NOT NULL AND batch_order BETWEEN 1 AND 5));
CREATE UNIQUE INDEX uk_screening_item_batch_order ON screening_run_items(batch_id,batch_order) WHERE batch_id IS NOT NULL;
CREATE INDEX idx_screening_run_items_batch ON screening_run_items(batch_id,batch_order);
