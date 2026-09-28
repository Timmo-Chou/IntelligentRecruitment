ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(200);

CREATE UNIQUE INDEX IF NOT EXISTS ux_notifications_user_dedupe_key
    ON notifications(user_id, dedupe_key)
    WHERE dedupe_key IS NOT NULL;
