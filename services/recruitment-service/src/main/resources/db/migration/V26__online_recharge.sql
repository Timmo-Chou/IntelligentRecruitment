CREATE TABLE recharge_receiving_accounts (
    id UUID PRIMARY KEY,
    bank_name VARCHAR(200) NOT NULL,
    beneficiary_name VARCHAR(200) NOT NULL,
    account_number VARCHAR(100) NOT NULL,
    contact_phone VARCHAR(80),
    contact_email VARCHAR(200),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uk_active_recharge_receiving_account
    ON recharge_receiving_accounts (status) WHERE status = 'ACTIVE';

CREATE TABLE recharge_orders (
    id UUID PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    billing_account_id UUID NOT NULL REFERENCES billing_accounts(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    created_by UUID NOT NULL REFERENCES users(id),
    payer_name VARCHAR(200) NOT NULL,
    payment_method VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor >= 1000 AND amount_minor <= 500000),
    status VARCHAR(32) NOT NULL,
    provider_trade_no VARCHAR(128),
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_recharge_orders_workspace_created ON recharge_orders (workspace_id, created_at DESC);
