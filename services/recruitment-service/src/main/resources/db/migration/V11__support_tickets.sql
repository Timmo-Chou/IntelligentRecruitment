-- 工单表
CREATE TABLE support_tickets (
    id UUID PRIMARY KEY,
    ticket_number VARCHAR(20) NOT NULL UNIQUE,   -- 如 TK-20260826-0001
    creator_user_id UUID REFERENCES users(id),   -- 可为 null（平台内部创建）
    creator_name VARCHAR(80) NOT NULL,
    company_id UUID REFERENCES companies(id),    -- 关联企业（可选）
    title VARCHAR(200) NOT NULL,
    category VARCHAR(50) NOT NULL,               -- BILLING, TECH_SUPPORT, ACCOUNT, FEEDBACK, OTHER
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL', -- LOW, NORMAL, HIGH, URGENT
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',  -- OPEN, IN_PROGRESS, WAITING_USER, RESOLVED, CLOSED
    assigned_to_id UUID REFERENCES platform_admins(id),
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_tickets_status ON support_tickets (status, created_at);
CREATE INDEX idx_tickets_creator ON support_tickets (creator_user_id, created_at);

-- 工单消息表
CREATE TABLE support_ticket_messages (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES support_tickets(id),
    sender_type VARCHAR(20) NOT NULL,            -- USER, PLATFORM_ADMIN
    sender_id UUID,                              -- user_id 或 platform_admin_id
    sender_name VARCHAR(80) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_ticket_messages ON support_ticket_messages (ticket_id, created_at);