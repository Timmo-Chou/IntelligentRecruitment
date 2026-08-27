ALTER TABLE membership_applications
    ADD COLUMN reviewed_by_user_id UUID REFERENCES users(id);

CREATE INDEX ix_membership_applications_company_status
    ON membership_applications (company_id, status, created_at);
