-- 定价配置表（管理端可配置，用户端实时查价）
CREATE TABLE pricing_items (
    id UUID PRIMARY KEY,
    code VARCHAR(64) UNIQUE NOT NULL,          -- 业务代码，如 JD_GENERATION / RESUME_PARSING / SCREENING
    name VARCHAR(120) NOT NULL,                 -- 中文功能名
    description VARCHAR(400),                   -- 功能说明/计费说明
    billing_unit VARCHAR(32) NOT NULL,          -- 计费单位: PER_USE / PER_ITEM / PER_CANDIDATE
    unit_price_minor BIGINT NOT NULL,           -- 单价（分）
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE=启用 / DISABLED=停用
    sort_order INT NOT NULL DEFAULT 0,          -- 展示顺序（越小越靠前）
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_pricing_items_status ON pricing_items (status);

-- 初始化 3 个计费项，默认价格与现有 @Value 配置保持一致（80 分）
INSERT INTO pricing_items (id, code, name, description, billing_unit, unit_price_minor, currency, status, sort_order, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000001', 'JD_GENERATION', 'JD 智能生成', 'AI 根据招聘需求自动生成完整 JD 草稿（含职责、任职要求、待确认项）', 'PER_USE', 80, 'CNY', 'ACTIVE', 10, NOW(), NOW()),
    ('00000000-0000-0000-0000-000000000002', 'RESUME_PARSING', '简历 AI 解析', 'AI 解析候选人简历，提取结构化信息（工作经历、教育背景、技能等）', 'PER_ITEM', 80, 'CNY', 'ACTIVE', 20, NOW(), NOW()),
    ('00000000-0000-0000-0000-000000000003', 'SCREENING', 'AI 简历筛选', 'AI 按招聘方案对候选人简历进行智能匹配评分，每筛选一位候选人计费', 'PER_CANDIDATE', 80, 'CNY', 'ACTIVE', 30, NOW(), NOW());
