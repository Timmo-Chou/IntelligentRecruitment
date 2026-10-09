ALTER TABLE resume_files ADD COLUMN IF NOT EXISTS execution_context JSONB;
CREATE TABLE recruitment_execution_maintenance (
 id INTEGER PRIMARY KEY CHECK(id=1),state VARCHAR(16) NOT NULL CHECK(state IN ('OPEN','DRAINING','VALIDATING')),
 cutover_id UUID,target_route_version VARCHAR(100),protocol_upgrade BOOLEAN NOT NULL DEFAULT FALSE,
 validation_limit INTEGER NOT NULL DEFAULT 10 CHECK(validation_limit BETWEEN 1 AND 100),validation_count INTEGER NOT NULL DEFAULT 0,
 version_matrix JSONB NOT NULL DEFAULT '{}'::jsonb,updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO recruitment_execution_maintenance(id,state) VALUES(1,'OPEN');
CREATE TABLE recruitment_maintenance_audits (
 id UUID PRIMARY KEY,cutover_id UUID,old_state VARCHAR(16),new_state VARCHAR(16) NOT NULL,
 target_route_version VARCHAR(100),version_matrix JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE recruitment_release_validation_runs (
 id UUID PRIMARY KEY,cutover_id UUID NOT NULL,tenant_id UUID NOT NULL,actor_id UUID NOT NULL,
 input JSONB NOT NULL,execution_context JSONB NOT NULL,status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
 ai_task_id VARCHAR(100),result_ciphertext TEXT,error_code VARCHAR(100),created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE recruitment_execution_instances (
 id UUID PRIMARY KEY,route_config_version VARCHAR(100) NOT NULL,release_sha VARCHAR(40) NOT NULL,last_seen_at TIMESTAMPTZ NOT NULL
);
