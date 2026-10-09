ALTER TABLE candidates ALTER COLUMN full_name_ciphertext DROP NOT NULL;
ALTER TABLE resume_parse_drafts ADD COLUMN structured_result_ciphertext TEXT;
ALTER TABLE resume_parse_versions ALTER COLUMN highest_education DROP NOT NULL,ALTER COLUMN headline DROP NOT NULL,ALTER COLUMN raw_text DROP NOT NULL;
