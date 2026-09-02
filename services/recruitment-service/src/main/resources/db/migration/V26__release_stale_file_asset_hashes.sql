-- Re-importing deleted candidates failed because file_assets kept the original sha256
-- under UNIQUE (workspace_id, sha256). Rotate hashes for assets no longer tied to active candidates.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

UPDATE file_assets f
SET sha256 = encode(digest(f.id::text || ':' || f.sha256, 'sha256'), 'hex')
WHERE NOT EXISTS (
  SELECT 1
  FROM resume_files rf
  JOIN candidates c ON c.id = rf.candidate_id
  WHERE rf.file_asset_id = f.id
    AND rf.workspace_id = f.workspace_id
    AND c.status <> 'DELETED'
);
