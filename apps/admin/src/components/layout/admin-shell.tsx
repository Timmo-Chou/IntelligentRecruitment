"use client";

// 管理后台主布局：侧边栏 + 顶部栏 + 内容区
import {
  LayoutDashboard,
  Users,
  Building2,
  FileCheck,
  MessageSquare,
  Wallet,
  Landmark,
  Settings,
  Shield,
  Menu,
  LogOut,
  X,
  Package,
  ShoppingCart,
  KeyRound,
} from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { type ReactNode, useState } from "react";
import { useAdminAuth } from "@/lib/admin-auth";

// 侧边栏导航项定义：保留权限码供后续细粒度权限控制使用
type NavItem = {
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  href: string;
  permission?: string;
};

const navItems: NavItem[] = [
  { label: "首页", icon: LayoutDashboard, href: "/" },
  { label: "用户管理", icon: Users, href: "/users", permission: "ADMIN_USER_VIEW" },
  { label: "企业管理", icon: Building2, href: "/companies", permission: "ADMIN_TENANT_VIEW" },
  { label: "审核中心", icon: FileCheck, href: "/reviews", permission: "ADMIN_REVIEW_VIEW" },
  { label: "工单管理", icon: MessageSquare, href: "/tickets", permission: "ADMIN_TICKET_VIEW" },
  { label: "账本管理", icon: Wallet, href: "/billing", permission: "ADMIN_BILLING_VIEW" },
  { label: "收款账户", icon: Landmark, href: "/recharge-settings", permission: "ADMIN_RECHARGE_VIEW" },
  { label: "产品运营", icon: Package, href: "/products", permission: "ADMIN_PRODUCT_VIEW" },
  { label: "订单管理", icon: ShoppingCart, href: "/orders", permission: "ADMIN_ORDER_VIEW" },
  { label: "权益与权限", icon: KeyRound, href: "/entitlements", permission: "ADMIN_ENTITLEMENT_VIEW" },
  { label: "菜单设置", icon: Menu, href: "/settings/menus", permission: "ADMIN_MENU_VIEW" },
  { label: "系统设置", icon: Settings, href: "/settings/admins", permission: "ADMIN_ADMIN_VIEW" },
];

export function AdminShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { admin, logout } = useAdminAuth();
  const [mobileOpen, setMobileOpen] = useState(false);

  // 当前阶段统一展示菜单；页面操作权限仍由各功能页和后端控制。
  const visibleItems = navItems;

  function isActive(href: string) {
    if (href === "/") return pathname === "/";
    return pathname.startsWith(href);
  }

  return (
    <div className="flex min-h-screen bg-[#f7fafc]">
      {mobileOpen && (
        <div className="fixed inset-0 z-40 bg-black/40 lg:hidden" onClick={() => setMobileOpen(false)} />
      )}

      <aside
        className={cn(
          "fixed inset-y-0 left-0 z-50 flex w-60 flex-col bg-white shadow-lg transition-transform lg:static lg:translate-x-0",
          mobileOpen ? "translate-x-0" : "-translate-x-full",
        )}
      >
        <div className="flex h-16 items-center justify-between border-b border-slate-200 px-5">
          <Link href="/" className="flex items-center gap-2.5" onClick={() => setMobileOpen(false)}>
            <Shield className="h-6 w-6 text-brand" />
            <span className="text-lg font-bold text-slate-800">平台管理</span>
          </Link>
          <button className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 lg:hidden" onClick={() => setMobileOpen(false)}>
            <X className="h-5 w-5" />
          </button>
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-4">
          {visibleItems.map(({ label, icon: Icon, href }) => {
            const active = isActive(href);
            return (
              <Link
                key={href}
                href={href}
                onClick={() => setMobileOpen(false)}
                className={cn(
                  "flex items-center gap-3 rounded-xl px-4 py-3 text-sm font-medium transition-colors",
                  active ? "bg-blue-50 text-blue-700" : "text-slate-600 hover:bg-slate-50 hover:text-slate-900",
                )}
              >
                <Icon className="h-5 w-5" />
                {label}
              </Link>
            );
          })}
        </nav>

        <div className="border-t border-slate-200 p-3">
          <button
            onClick={logout}
            className="flex w-full items-center gap-3 rounded-xl px-4 py-3 text-sm font-medium text-slate-500 transition-colors hover:bg-red-50 hover:text-red-600"
          >
            <LogOut className="h-5 w-5" />
            退出登录
          </button>
        </div>
      </aside>

      <div className="flex flex-1 flex-col">
        <header className="flex h-16 items-center justify-between border-b border-slate-200 bg-white px-4 lg:px-6">
          <button className="rounded-lg p-2 text-slate-500 hover:bg-slate-100 lg:hidden" onClick={() => setMobileOpen(true)}>
            <Menu className="h-5 w-5" />
          </button>
          <h1 className="text-lg font-semibold text-slate-700">平台管理</h1>
          <div className="flex items-center gap-3">
            {admin && (
              <div className="hidden items-center gap-2 lg:flex">
                <div className="flex h-8 w-8 items-center justify-center rounded-full bg-blue-100 text-sm font-semibold text-blue-700">
                  {admin.displayName.slice(0, 1)}
                </div>
                <div className="text-right">
                  <div className="text-sm font-medium text-slate-700">{admin.displayName}</div>
                  <div className="text-xs text-slate-400">{roleLabel(admin.role)}</div>
                </div>
              </div>
            )}
            <button
              onClick={logout}
              className="hidden items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium text-slate-500 hover:bg-red-50 hover:text-red-600 lg:flex"
            >
              <LogOut className="h-4 w-4" />
              退出登录
            </button>
          </div>
        </header>

        <main className="flex-1 overflow-y-auto p-4 lg:p-6">{children}</main>
      </div>
    </div>
  );
}

function roleLabel(role: string) {
  switch (role) {
    case "SUPER_ADMIN": return "超级管理员";
    case "ADMIN": return "管理员";
    case "OPERATOR": return "运营人员";
    default: return role;
  }
}

function cn(...inputs: (string | false | null | undefined)[]) {
  return inputs.filter(Boolean).join(" ");
}
