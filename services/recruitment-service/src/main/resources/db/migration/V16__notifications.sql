CREATE TABLE notifications (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(40) NOT NULL, title VARCHAR(200) NOT NULL, content VARCHAR(1000) NOT NULL,
    link VARCHAR(300), read_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_notifications_user_created ON notifications(user_id, created_at DESC);
