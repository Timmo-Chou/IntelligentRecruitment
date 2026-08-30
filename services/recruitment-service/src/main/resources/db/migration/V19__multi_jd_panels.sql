-- A JD-generation task may produce several independent positions.
ALTER TABLE jd_drafts DROP CONSTRAINT IF EXISTS jd_drafts_recruitment_task_id_key;
CREATE INDEX IF NOT EXISTS idx_jd_drafts_task_updated ON jd_drafts (recruitment_task_id, updated_at);

ALTER TABLE jobs ADD COLUMN IF NOT EXISTS jd_draft_id UUID REFERENCES jd_drafts(id);
UPDATE jobs j SET jd_draft_id = d.id
FROM jd_drafts d
WHERE d.recruitment_task_id = j.recruitment_task_id AND j.jd_draft_id IS NULL;
DROP INDEX IF EXISTS uk_jobs_recruitment_task;
CREATE UNIQUE INDEX IF NOT EXISTS uk_jobs_jd_draft ON jobs (jd_draft_id) WHERE jd_draft_id IS NOT NULL;
