-- Candidate PII is encrypted at rest. These deterministic HMAC tokens permit
-- exact name/phone lookups without retaining a plaintext PII search index.
ALTER TABLE candidates
    ADD COLUMN IF NOT EXISTS full_name_search_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS phone_search_hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_candidates_workspace_full_name_hash
    ON candidates (workspace_id, full_name_search_hash)
    WHERE full_name_search_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_candidates_workspace_phone_hash
    ON candidates (workspace_id, phone_search_hash)
    WHERE phone_search_hash IS NOT NULL;
