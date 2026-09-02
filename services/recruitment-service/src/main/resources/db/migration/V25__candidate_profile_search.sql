-- Talent profile enrichment for search / filters / manual create
ALTER TABLE candidates
    ADD COLUMN IF NOT EXISTS profile JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS search_text TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_candidates_profile_gin
    ON candidates USING GIN (profile);

CREATE INDEX IF NOT EXISTS idx_candidates_search_text
    ON candidates USING GIN (to_tsvector('simple', coalesce(search_text, '')));
