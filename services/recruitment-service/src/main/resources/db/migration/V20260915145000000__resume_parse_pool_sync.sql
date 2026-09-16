ALTER TABLE resume_files
  ADD COLUMN IF NOT EXISTS enterprise_pool_sync_enabled BOOLEAN NOT NULL DEFAULT FALSE;
