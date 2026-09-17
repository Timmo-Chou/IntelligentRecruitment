"use client";

// 管理后台首页 - 仪表盘
import {
  FileCheck,
  Users,
  Building2,
  MessageSquare,
} from "lucide-react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";

// 简单统计：从现有接口获取计数
export default function DashboardPage() {
  // 企业数量
  const { data: tenants } = useQuery({
    queryKey: ["dashboard-tenants"],
    queryFn: () => adminApiFetch<{ total: number }>("/platform/recruitment/tenants?page=1&size=1"),
  });

  // 用户数量
  const { data: users } = useQuery({
    queryKey: ["dashboard-users"],
    queryFn: () => adminApiFetch<{ total: number }>("/platform/users?page=1&size=1"),
  });

  // 统计卡片
  const statCards = [
    { label: "企业总数", value: tenants?.total ?? "--", icon: Building2, color: "bg-green-50 text-green-600", href: "/companies" },
    { label: "用户总数", value: users?.total ?? "--", icon: Users, color: "bg-blue-50 text-blue-600", href: "/users" },
    { label: "审核中心", value: "→", icon: FileCheck, color: "bg-amber-50 text-amber-600", href: "/reviews" },
    { label: "工单管理", value: "→", icon: MessageSquare, color: "bg-purple-50 text-purple-600", href: "/tickets" },
  ];

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-800">首页</h1>
        <p className="mt-1 text-sm text-slate-500">平台运营数据概览</p>
      </div>

      <div className="mb-8 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {statCards.map(({ label, value, icon: Icon, color, href }) => (
          <Link
            key={label}
            href={href}
            className="flex items-center gap-4 rounded-xl border border-slate-200 bg-white p-5 shadow-sm transition hover:border-blue-200 hover:shadow-md"
          >
            <div className={`flex h-12 w-12 items-center justify-center rounded-xl ${color}`}>
              <Icon className="h-6 w-6" />
            </div>
            <div>
              <p className="text-sm text-slate-500">{label}</p>
              <p className="text-2xl font-bold text-slate-800">{value}</p>
            </div>
          </Link>
        ))}
      </div>

      <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="mb-2 text-base font-bold text-slate-700">快捷操作</h2>
        <p className="text-sm text-slate-500">通过左侧菜单访问各功能模块：用户管理、企业管理、审核中心、工单管理、账本管理等。</p>
      </div>
    </div>
  );
}
