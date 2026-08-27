ALTER TABLE users
    ADD COLUMN password_hash VARCHAR(100),
    ADD COLUMN password_set_at TIMESTAMPTZ;
