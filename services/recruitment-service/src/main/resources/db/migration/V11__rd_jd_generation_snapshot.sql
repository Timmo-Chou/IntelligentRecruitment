ALTER TABLE jd_drafts
    ADD COLUMN IF NOT EXISTS generation_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS generation_source JSONB NOT NULL DEFAULT '{}'::jsonb;
