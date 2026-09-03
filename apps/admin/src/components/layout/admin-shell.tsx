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
  Receipt,
  Settings,
  Shield,
  Menu,
  LogOut,
  X,
} from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { type ReactNode, useState } from "react";
import { useAdminAuth } from "@/lib/admin-auth";

// 侧边栏导航项定义
const navItems = [
  { label: "首页", icon: LayoutDashboard, href: "/" },
  { label: "用户管理", icon: Users, href: "/users" },
  { label: "企业管理", icon: Building2, href: "/companies" },
  { label: "审核中心", icon: FileCheck, href: "/reviews" },
  { label: "工单管理", icon: MessageSquare, href: "/tickets" },
  { label: "账本管理", icon: Wallet, href: "/billing" },
  { label: "收款账户", icon: Landmark, href: "/recharge-settings" },
  { label: "定价配置", icon: Receipt, href: "/pricing" },
  { label: "系统设置", icon: Settings, href: "/settings/admins" },
] as const;

export function AdminShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { logout } = useAdminAuth();
  const [mobileOpen, setMobileOpen] = useState(false);

  // 判断当前激活的导航项
  function isActive(href: string) {
    if (href === "/") return pathname === "/";
    return pathname.startsWith(href);
  }

  return (
    <div className="flex min-h-screen bg-[#f7fafc]">
      {/* 移动端遮罩层 */}
      {mobileOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/40 lg:hidden"
          onClick={() => setMobileOpen(false)}
        />
      )}

      {/* 侧边栏 */}
      <aside
        className={cn(
          "fixed inset-y-0 left-0 z-50 flex w-60 flex-col bg-white shadow-lg transition-transform lg:static lg:translate-x-0",
          mobileOpen ? "translate-x-0" : "-translate-x-full",
        )}
      >
        {/* 侧边栏头部 */}
        <div className="flex h-16 items-center justify-between border-b border-slate-200 px-5">
          <Link href="/" className="flex items-center gap-2.5" onClick={() => setMobileOpen(false)}>
            <Shield className="h-6 w-6 text-brand" />
            <span className="text-lg font-bold text-slate-800">平台管理</span>
          </Link>
          <button
            className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 lg:hidden"
            onClick={() => setMobileOpen(false)}
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* 导航菜单 */}
        <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-4">
          {navItems.map(({ label, icon: Icon, href }) => {
            const active = isActive(href);
            return (
              <Link
                key={href}
                href={href}
                onClick={() => setMobileOpen(false)}
                className={cn(
                  "flex items-center gap-3 rounded-xl px-4 py-3 text-sm font-medium transition-colors",
                  active
                    ? "bg-blue-50 text-blue-700"
                    : "text-slate-600 hover:bg-slate-50 hover:text-slate-900",
                )}
              >
                <Icon className="h-5 w-5" />
                {label}
              </Link>
            );
          })}
        </nav>

        {/* 底部退出按钮 */}
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

      {/* 右侧主内容区 */}
      <div className="flex flex-1 flex-col">
        {/* 顶部栏 */}
        <header className="flex h-16 items-center justify-between border-b border-slate-200 bg-white px-4 lg:px-6">
          <button
            className="rounded-lg p-2 text-slate-500 hover:bg-slate-100 lg:hidden"
            onClick={() => setMobileOpen(true)}
          >
            <Menu className="h-5 w-5" />
          </button>
          <h1 className="text-lg font-semibold text-slate-700">平台管理</h1>
          <div className="flex items-center gap-3">
            <button
              onClick={logout}
              className="hidden items-center gap-2 rounded-lg px-4 py-2 text-sm font-medium text-slate-500 hover:bg-red-50 hover:text-red-600 lg:flex"
            >
              <LogOut className="h-4 w-4" />
              退出登录
            </button>
          </div>
        </header>

        {/* 内容区 */}
        <main className="flex-1 overflow-y-auto p-4 lg:p-6">{children}</main>
      </div>
    </div>
  );
}

// 内部 cn 工具函数（避免循环依赖）
function cn(...inputs: (string | false | null | undefined)[]) {
  return inputs.filter(Boolean).join(" ");
}
