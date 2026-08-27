# 平台运营功能设计方案（MVP）

版本：V1.0
状态：设计阶段，待开发
适用对象：平台超级管理员、平台运营人员

## 1. 概述

### 1.1 目标

为平台管理端提供运营功能，包括：
- 平台管理员权限体系（两个角色）
- 注册用户和企业管理
- 企业注册申请审核
- 工单系统
- 菜单管理

### 1.2 系统关系

```
apps/web ──────┐
（招聘用户端）   │  /api/v1/**（BearerTokenFilter 鉴权）
                ├──→  recruitment-service ──→  PostgreSQL
apps/admin ────┘  /api/v1/platform/**（PlatformAdminFilter 鉴权）
（平台管理端）
```

- 后端是同一个 Spring Boot 服务，平台端 API 使用独立鉴权机制
- 前端是两个独立部署的应用，用户群体不同，互不影响

---

## 2. 权限模型

### 2.1 两个角色

| 角色 | code | 权限范围 |
|---|---|---|
| 超级管理员 | `SUPER_ADMIN` | 全部权限（含管理员管理、菜单配置） |
| 平台运营 | `PLATFORM_OPERATOR` | 用户/企业查看、认证审核、成员申请审核、工单查看回复、余额调整、结算、账本记录查看 |

### 2.2 权限定义（硬编码）

| 权限 code | 说明 | SUPER_ADMIN | PLATFORM_OPERATOR |
|---|---|---|---|
| `admin:manage` | 管理员管理 | ✅ | - |
| `menu:manage` | 菜单管理 | ✅ | - |
| `user:read` | 用户查看 | ✅ | ✅ |
| `user:write` | 用户禁用/启用 | ✅ | ✅ |
| `company:read` | 企业查看 | ✅ | ✅ |
| `company:write` | 企业编辑 | ✅ | ✅ |
| `verification:review` | 认证审核 | ✅ | ✅ |
| `membership:review` | 成员申请审核 | ✅ | ✅ |
| `ticket:read` | 工单查看 | ✅ | ✅ |
| `ticket:write` | 工单回复/关闭 | ✅ | ✅ |
| `billing:read` | 账本查看 | ✅ | ✅ |
| `billing:adjust` | 余额调整/结算 | ✅ | ✅ |

### 2.3 与用户端权限的关系

两套权限体系完全独立，互不干扰：

| 维度 | 用户端（招聘系统） | 平台管理端 |
|---|---|---|
| 认证入口 | `/api/v1/auth/**` | `/api/v1/platform/auth/**`（新增） |
| 身份表 | `users` | `platform_admins` |
| 角色存储 | `company_memberships.role` / `workspace_memberships.role` | `platform_admins.role` |
| 角色值 | COMPANY_OWNER, COMPANY_ADMIN, RECRUITER 等 | SUPER_ADMIN, PLATFORM_OPERATOR |
| 权限校验 | 业务层 `requireCompanyAdmin()` 等 | `PlatformAdminGuard.require(permission)` |

SecurityConfiguration 中 `/api/v1/platform/**` 设为 permitAll，由平台自己的 Filter 独立鉴权。

---

## 3. 数据库设计

### 3.1 platform_admins 表

```sql
CREATE TABLE platform_admins (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    display_name VARCHAR(80) NOT NULL,
    role VARCHAR(24) NOT NULL DEFAULT 'PLATFORM_OPERATOR',  -- SUPER_ADMIN / PLATFORM_OPERATOR
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',            -- ACTIVE / DISABLED
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
```

### 3.2 platform_menus 表

```sql
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
```

### 3.3 support_tickets 表

```sql
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
```

### 3.4 support_ticket_messages 表

```sql
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
```

### 3.5 迁移规划

| 迁移文件 | 内容 |
|---|---|
| V3__platform_admin.sql | platform_admins + platform_menus 表 |
| V4__support_tickets.sql | support_tickets + support_ticket_messages 表 |

---

## 4. API 设计

### 4.1 管理员管理 API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/platform/admins` | `admin:manage` | 管理员列表 |
| GET | `/platform/admins/{adminId}` | `admin:manage` | 管理员详情 |
| POST | `/platform/admins` | `admin:manage` | 新增管理员 |
| PUT | `/platform/admins/{adminId}` | `admin:manage` | 编辑管理员（角色、状态） |
| POST | `/platform/admins/{adminId}/disable` | `admin:manage` | 禁用管理员 |

### 4.2 菜单管理 API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/platform/menus` | `menu:manage` | 菜单树列表 |
| GET | `/platform/menus/{menuId}` | `menu:manage` | 菜单详情 |
| POST | `/platform/menus` | `menu:manage` | 新增菜单 |
| PUT | `/platform/menus/{menuId}` | `menu:manage` | 编辑菜单 |
| DELETE | `/platform/menus/{menuId}` | `menu:manage` | 删除菜单 |
| PUT | `/platform/menus/{menuId}/sort` | `menu:manage` | 调整排序 |

公开接口（管理员登录后调用）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/platform/me/menus` | 当前管理员可见菜单树（根据角色过滤） |

### 4.3 用户管理 API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/platform/users` | `user:read` | 用户列表（分页、搜索、状态筛选） |
| GET | `/platform/users/{userId}` | `user:read` | 用户详情（含认证状态、企业、工作空间） |
| POST | `/platform/users/{userId}/disable` | `user:write` | 禁用用户 |
| POST | `/platform/users/{userId}/enable` | `user:write` | 启用用户 |

用户列表查询参数：`q`（搜索名称/手机号）、`status`、`verification`、`page`、`size`

### 4.4 企业管理 API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/platform/companies` | `company:read` | 企业列表（分页、搜索、状态筛选） |
| GET | `/platform/companies/{companyId}` | `company:read` | 企业详情（成员、工作空间、账本概要） |
| POST | `/platform/companies/{companyId}/status` | `company:write` | 修改企业管理状态 |

企业列表查询参数：`q`（搜索名称）、`verification_status`、`management_status`、`page`、`size`

### 4.5 审核查询 API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/platform/reviews/personal` | `verification:review` | 个人认证待审核列表 |
| GET | `/platform/reviews/company-verifications` | `verification:review` | 企业认证待审核列表 |
| GET | `/platform/reviews/membership-applications` | `membership:review` | 成员申请待审核列表 |
| GET | `/platform/reviews/personal/{userId}` | `verification:review` | 个人认证详情 |
| GET | `/platform/reviews/company-verifications/{requestId}` | `verification:review` | 企业认证详情 |
| GET | `/platform/reviews/membership-applications/{applicationId}` | `membership:review` | 成员申请详情 |

审批操作复用现有 PlatformReviewController 接口。

### 4.6 工单 API（平台端）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/platform/tickets` | `ticket:read` | 工单列表（分页、筛选、搜索） |
| GET | `/platform/tickets/{ticketId}` | `ticket:read` | 工单详情（含全部消息） |
| POST | `/platform/tickets` | `ticket:write` | 创建工单（平台侧代用户创建） |
| POST | `/platform/tickets/{ticketId}/messages` | `ticket:write` | 回复工单 |
| POST | `/platform/tickets/{ticketId}/assign` | `ticket:write` | 分配工单 |
| POST | `/platform/tickets/{ticketId}/status` | `ticket:write` | 修改工单状态 |
| POST | `/platform/tickets/{ticketId}/close` | `ticket:write` | 关闭工单 |

工单列表查询参数：`status`、`category`、`priority`、`assigned_to`、`q`、`page`、`size`

### 4.7 工单 API（用户端）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/me/tickets` | 我的工单列表 |
| GET | `/api/v1/me/tickets/{ticketId}` | 工单详情 |
| POST | `/api/v1/me/tickets` | 创建工单 |
| POST | `/api/v1/me/tickets/{ticketId}/messages` | 回复工单 |

---

## 5. 前端页面结构

### 5.1 应用入口

独立应用 `apps/admin`，不混入招聘业务端 `apps/web`。

```
apps/admin/
├── src/
│   ├── app/
│   │   ├── layout.tsx                  # 平台管理布局（侧边栏 + 顶栏 + 内容区）
│   │   ├── page.tsx                    # 首页/仪表盘
│   │   ├── login/
│   │   │   └── page.tsx                # 平台管理员登录页
│   │   ├── users/
│   │   │   ├── page.tsx                # 用户列表
│   │   │   └── [userId]/
│   │   │       └── page.tsx            # 用户详情
│   │   ├── companies/
│   │   │   ├── page.tsx                # 企业列表
│   │   │   └── [companyId]/
│   │   │       └── page.tsx            # 企业详情
│   │   ├── reviews/
│   │   │   ├── page.tsx                # 审核中心（Tab 切换）
│   │   │   └── [type]/[id]/
│   │   │       └── page.tsx            # 审核详情
│   │   ├── tickets/
│   │   │   ├── page.tsx                # 工单列表
│   │   │   ├── new/
│   │   │   │   └── page.tsx            # 新建工单
│   │   │   └── [ticketId]/
│   │   │       └── page.tsx            # 工单详情（对话式）
│   │   ├── billing/
│   │   │   └── page.tsx                # 账本管理
│   │   └── settings/
│   │       ├── admins/
│   │       │   ├── page.tsx            # 管理员列表
│   │       │   └── [adminId]/
│   │       │       └── page.tsx        # 管理员详情/编辑
│   │       └── menus/
│   │           └── page.tsx            # 菜单管理
│   ├── components/
│   │   ├── layout/
│   │   │   ├── admin-shell.tsx         # 管理端外壳（侧边栏导航）
│   │   │   └── permission-guard.tsx    # 权限守卫组件
│   │   └── ui/
│   │       └── ...                     # 复用 shadcn 风格组件
│   └── lib/
│       ├── admin-api-client.ts         # 平台管理 API 客户端
│       └── admin-auth.tsx              # 平台管理员认证上下文
```

### 5.2 页面布局

```
┌──────────────────────────────────────────────┐
│  Top Bar: 当前管理员 | 退出登录              │
├──────────┬───────────────────────────────────┤
│          │                                   │
│ Sidebar  │         Content Area              │
│          │                                   │
│ · 首页   │                                   │
│ · 用户   │                                   │
│ · 企业   │                                   │
│ · 审核   │  (根据权限显示对应内容)           │
│ · 工单   │                                   │
│ · 账本   │                                   │
│ · 设置   │                                   │
│   - 管理员│                                   │
│   - 菜单  │                                   │
└──────────┴───────────────────────────────────┘
```

### 5.3 预置菜单数据

| 一级菜单 | 二级菜单 | 路径 | 权限 | 运营可见 |
|---|---|---|---|---|
| 首页 | - | `/` | - | ✅ |
| 用户管理 | - | `/users` | `user:read` | ✅ |
| 企业管理 | - | `/companies` | `company:read` | ✅ |
| 审核中心 | 个人认证 | `/reviews/personal` | `verification:review` | ✅ |
| 审核中心 | 企业认证 | `/reviews/company` | `verification:review` | ✅ |
| 审核中心 | 成员申请 | `/reviews/membership` | `membership:review` | ✅ |
| 工单管理 | - | `/tickets` | `ticket:read` | ✅ |
| 账本管理 | - | `/billing` | `billing:read` | ✅ |
| 系统设置 | 管理员管理 | `/settings/admins` | `admin:manage` | - |
| 系统设置 | 菜单管理 | `/settings/menus` | `menu:manage` | - |

### 5.4 关键页面说明

**首页仪表盘**：顶部 4 个统计卡片（待审核数、今日新用户、今日新企业、待处理工单数），下方最近审核列表 + 最近工单列表。

**审核中心**：顶部 3 个 Tab（个人认证 / 企业认证 / 成员申请），每个 Tab 下表格列表，每行有「查看详情」「通过」「拒绝」按钮。点击「拒绝」弹出拒绝原因输入框。

**工单详情**：对话式布局，消息气泡区分用户发言和平台回复，底部输入框回复。右侧面板显示工单元信息（状态、分类、优先级、指派人）。

**菜单管理**：树形列表，支持新增/编辑/删除菜单项、拖拽调整排序、切换「运营可见」开关。

---

## 6. 后端模块规划

```
com.intelligentrecruitment.platform/
├── admin/                    # 管理员管理
│   ├── api/PlatformAdminController.java
│   └── application/PlatformAdminService.java
├── menu/                     # 菜单管理
│   ├── api/PlatformMenuController.java
│   └── application/MenuService.java
├── review/                   # 审核列表查询（复用现有 TenancyService 审批逻辑）
│   ├── api/PlatformReviewQueryController.java
│   └── application/ReviewQueryService.java
├── ticket/                   # 工单系统
│   ├── api/PlatformTicketController.java
│   ├── api/UserTicketController.java     # 用户端工单
│   └── application/TicketService.java
└── shared/
    └── security/PlatformAdminGuard.java   # 升级：增加权限校验 + 管理员身份解析
```

---

## 7. SecurityConfiguration 调整

```java
.requestMatchers("/api/v1/platform/**").permitAll()  // 平台接口由 PlatformAdminFilter 独立鉴权
```

平台端 `/api/v1/platform/**` 从用户 JWT 鉴权中排除，由平台自己的 Filter 处理：
- 解析平台管理员 token
- 查 `platform_admins` 表获取角色
- 调用 `PlatformAdminGuard.require(permissionCode)` 校验权限

---

## 8. 实施优先级

| 优先级 | 模块 | 理由 |
|---|---|---|
| P0 | 权限模型 + 管理员登录 | 所有功能的基础，替换当前共享密钥方案 |
| P0 | 审核列表查询 | 已有审批接口，补充列表即可让审核工作可用 |
| P1 | 用户/企业管理 | 配合审核使用的管理功能 |
| P1 | 工单系统 | MVP 最简版（创建、回复、关闭） |
| P1 | 菜单管理 | 控制运营角色可见菜单 |
| P2 | 仪表盘首页 | 统计卡片，锦上添花 |