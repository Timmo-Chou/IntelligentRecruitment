-- 平台管理员表
CREATE TABLE platform_admins (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    display_name VARCHAR(80) NOT NULL,
    role VARCHAR(24) NOT NULL DEFAULT 'PLATFORM_OPERATOR',  -- SUPER_ADMIN / PLATFORM_OPERATOR
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',            -- ACTIVE / DISABLED
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- 平台菜单配置表
CREATE TABLE platform_menus (
    id UUID PRIMARY KEY,
    parent_id UUID REFERENCES platform_menus(id),      -- 父菜单，null 表示一级菜单
    code VARCHAR(50) NOT NULL UNIQUE,                   -- 唯一标识，如 dashboard, users, reviews
    display_name VARCHAR(80) NOT NULL,                  -- 菜单显示名
    icon VARCHAR(50),                                   -- 图标名称
    path VARCHAR(200),                                  -- 前端路由路径
    permission_code VARCHAR(80),                        -- 对应权限 code
    sort_order INT NOT NULL DEFAULT 0,                  -- 排序
    is_visible BOOLEAN NOT NULL DEFAULT true,           -- 是否显示
    visible_to_operator BOOLEAN NOT NULL DEFAULT true,  -- 对平台运营是否可见
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- 预置菜单数据
INSERT INTO platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES
(gen_random_uuid(), NULL, 'dashboard', '首页', 'LayoutDashboard', '/', NULL, 0, true, true, now(), now()),
(gen_random_uuid(), NULL, 'users', '用户管理', 'Users', '/users', 'user:read', 1, true, true, now(), now()),
(gen_random_uuid(), NULL, 'companies', '企业管理', 'Building2', '/companies', 'company:read', 2, true, true, now(), now());

WITH review_parent AS (
    INSERT INTO platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at)
    VALUES (gen_random_uuid(), NULL, 'reviews', '审核中心', 'FileCheck', NULL, NULL, 3, true, true, now(), now())
    RETURNING id
)
INSERT INTO platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES
(gen_random_uuid(), (SELECT id FROM review_parent), 'reviews_personal', '个人认证', NULL, '/reviews/personal', 'verification:review', 0, true, true, now(), now()),
(gen_random_uuid(), (SELECT id FROM review_parent), 'reviews_company', '企业认证', NULL, '/reviews/company', 'verification:review', 1, true, true, now(), now()),
(gen_random_uuid(), (SELECT id FROM review_parent), 'reviews_membership', '成员申请', NULL, '/reviews/membership', 'membership:review', 2, true, true, now(), now());

INSERT INTO platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES
(gen_random_uuid(), NULL, 'tickets', '工单管理', 'MessageSquare', '/tickets', 'ticket:read', 4, true, true, now(), now()),
(gen_random_uuid(), NULL, 'billing', '账本管理', 'Wallet', '/billing', 'billing:read', 5, true, true, now(), now());

WITH settings_parent AS (
    INSERT INTO platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at)
    VALUES (gen_random_uuid(), NULL, 'settings', '系统设置', 'Settings', NULL, NULL, 6, true, false, now(), now())
    RETURNING id
)
INSERT INTO platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, is_visible, visible_to_operator, created_at, updated_at) VALUES
(gen_random_uuid(), (SELECT id FROM settings_parent), 'settings_admins', '管理员管理', NULL, '/settings/admins', 'admin:manage', 0, true, false, now(), now()),
(gen_random_uuid(), (SELECT id FROM settings_parent), 'settings_menus', '菜单管理', NULL, '/settings/menus', 'menu:manage', 1, true, false, now(), now());