ALTER TABLE idempotency_records RENAME COLUMN organization_id TO workspace_id;
ALTER TABLE idempotency_records ALTER COLUMN workspace_id TYPE UUID USING workspace_id::UUID;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    phone_hash VARCHAR(64) NOT NULL UNIQUE,
    phone_last_four VARCHAR(4) NOT NULL,
    display_name VARCHAR(80),
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE verification_challenges (
    id UUID PRIMARY KEY,
    phone_hash VARCHAR(64) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_verification_challenges_phone ON verification_challenges (phone_hash, created_at DESC);

CREATE TABLE access_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE refresh_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    device_info VARCHAR(300),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    rotated_from_id UUID REFERENCES refresh_sessions(id),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_refresh_sessions_user ON refresh_sessions (user_id, revoked_at, expires_at);

CREATE TABLE personal_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    identity_hash VARCHAR(64) NOT NULL UNIQUE,
    real_name_masked VARCHAR(80) NOT NULL,
    verification_status VARCHAR(24) NOT NULL,
    reviewed_by VARCHAR(100),
    reviewed_at TIMESTAMPTZ,
    rejection_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE companies (
    id UUID PRIMARY KEY,
    legal_name VARCHAR(200) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    credit_code_hash VARCHAR(64) NOT NULL UNIQUE,
    credit_code_masked VARCHAR(32) NOT NULL,
    verification_status VARCHAR(24) NOT NULL,
    management_status VARCHAR(24) NOT NULL,
    owner_user_id UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE company_verification_requests (
    id UUID PRIMARY KEY,
    applicant_user_id UUID NOT NULL REFERENCES users(id),
    company_id UUID REFERENCES companies(id),
    request_type VARCHAR(24) NOT NULL DEFAULT 'CREATE',
    legal_name VARCHAR(200) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    credit_code_hash VARCHAR(64) NOT NULL,
    credit_code_masked VARCHAR(32) NOT NULL,
    license_reference VARCHAR(500) NOT NULL,
    first_workspace_name VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL,
    reviewed_by VARCHAR(100),
    reviewed_at TIMESTAMPTZ,
    rejection_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_company_verifications_status ON company_verification_requests (status, created_at);
CREATE UNIQUE INDEX uk_company_pending_claim ON company_verification_requests (company_id)
    WHERE request_type = 'CLAIM' AND status = 'PENDING';

CREATE TABLE company_memberships (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    UNIQUE (company_id, user_id)
);

CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    company_id UUID REFERENCES companies(id),
    type VARCHAR(24) NOT NULL,
    name VARCHAR(120) NOT NULL,
    owner_user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(24) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_workspace_company CHECK (
      (type = 'PERSONAL' AND company_id IS NULL) OR
      (type = 'COMPANY' AND company_id IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uk_personal_workspace_user ON workspaces (owner_user_id) WHERE type = 'PERSONAL' AND status = 'ACTIVE';
CREATE INDEX idx_workspaces_company ON workspaces (company_id, status);

CREATE TABLE workspace_memberships (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    UNIQUE (workspace_id, user_id)
);

CREATE TABLE membership_invitations (
    id UUID PRIMARY KEY,
    target_type VARCHAR(24) NOT NULL,
    target_id UUID NOT NULL,
    phone_hash VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    accepted_by UUID REFERENCES users(id),
    accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE membership_applications (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    applicant_user_id UUID NOT NULL REFERENCES users(id),
    evidence VARCHAR(500) NOT NULL,
    status VARCHAR(24) NOT NULL,
    reviewed_by_platform_user VARCHAR(100),
    reviewed_at TIMESTAMPTZ,
    review_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uk_pending_company_application ON membership_applications (company_id, applicant_user_id) WHERE status = 'PENDING';

CREATE TABLE billing_accounts (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL UNIQUE REFERENCES workspaces(id),
    currency VARCHAR(3) NOT NULL,
    available_amount_minor BIGINT NOT NULL DEFAULT 0,
    reserved_amount_minor BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE credit_lots (
    id UUID PRIMARY KEY,
    billing_account_id UUID NOT NULL REFERENCES billing_accounts(id),
    source_type VARCHAR(32) NOT NULL,
    original_amount_minor BIGINT NOT NULL,
    available_amount_minor BIGINT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL
);

CREATE TABLE billing_ledger_entries (
    id UUID PRIMARY KEY,
    billing_account_id UUID NOT NULL REFERENCES billing_accounts(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    credit_lot_id UUID REFERENCES credit_lots(id),
    entry_type VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    business_reference VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    operator_user_id UUID REFERENCES users(id),
    reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (billing_account_id, idempotency_key)
);
CREATE INDEX idx_billing_ledger_account_created ON billing_ledger_entries (billing_account_id, created_at DESC);

CREATE TABLE billing_reservations (
    id UUID PRIMARY KEY,
    billing_account_id UUID NOT NULL REFERENCES billing_accounts(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    business_reference VARCHAR(160) NOT NULL,
    reserved_amount_minor BIGINT NOT NULL,
    settled_amount_minor BIGINT NOT NULL DEFAULT 0,
    released_amount_minor BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (billing_account_id, business_reference)
);

CREATE TABLE billing_reservation_allocations (
    id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL REFERENCES billing_reservations(id),
    credit_lot_id UUID NOT NULL REFERENCES credit_lots(id),
    reserved_amount_minor BIGINT NOT NULL,
    settled_amount_minor BIGINT NOT NULL DEFAULT 0,
    released_amount_minor BIGINT NOT NULL DEFAULT 0,
    UNIQUE (reservation_id, credit_lot_id)
);

CREATE TABLE trial_eligibilities (
    id UUID PRIMARY KEY,
    subject_type VARCHAR(32) NOT NULL,
    subject_id UUID NOT NULL,
    policy_code VARCHAR(64) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    UNIQUE (subject_type, subject_id, policy_code)
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    actor_user_id UUID,
    company_id UUID,
    workspace_id UUID,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(100) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_audit_scope ON audit_logs (company_id, workspace_id, created_at DESC);

ALTER TABLE idempotency_records
    ADD CONSTRAINT fk_idempotency_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
