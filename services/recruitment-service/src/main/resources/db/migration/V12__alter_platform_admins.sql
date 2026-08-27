-- 平台管理员表调整：user_id 改为可空，新增 key_hash 字段
ALTER TABLE platform_admins ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE platform_admins DROP CONSTRAINT IF EXISTS platform_admins_user_id_key;
ALTER TABLE platform_admins ADD COLUMN IF NOT EXISTS key_hash VARCHAR(64);