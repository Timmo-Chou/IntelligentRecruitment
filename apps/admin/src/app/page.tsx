"use client";

// 管理后台首页 - 仪表盘
import {
  FileCheck,
  Users,
  Building2,
  MessageSquare,
  Clock,
  ArrowRight,
} from "lucide-react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";

// 仪表盘统计数据
type DashboardStats = {
  pendingReviews: number;
  newUsersToday: number;
  newCompaniesToday: number;
  pendingTickets: number;
};

// 最近审核记录
type RecentReview = {
  id: string;
  type: string;
  targetName: string;
  createdAt: string;
  status: string;
};

// 最近工单记录
type RecentTicket = {
  id: string;
  title: string;
  category: string;
  priority: string;
  status: string;
  createdAt: string;
};

export default function DashboardPage() {
  // 获取仪表盘统计
  const { data: stats, isLoading: statsLoading } = useQuery({
    queryKey: ["dashboard-stats"],
    queryFn: () => adminApiFetch<DashboardStats>("/platform/dashboard/stats"),
  });

  // 获取最近审核
  const { data: reviews, isLoading: reviewsLoading } = useQuery({
    queryKey: ["dashboard-recent-reviews"],
    queryFn: () => adminApiFetch<RecentReview[]>("/platform/reviews?page=1&pageSize=5"),
  });

  // 获取最近工单
  const { data: tickets, isLoading: ticketsLoading } = useQuery({
    queryKey: ["dashboard-recent-tickets"],
    queryFn: () => adminApiFetch<RecentTicket[]>("/platform/tickets?page=1&pageSize=5"),
  });

  // 统计卡片定义
  const statCards = [
    {
      label: "待审核数",
      value: stats?.pendingReviews ?? "--",
      icon: FileCheck,
      color: "bg-amber-50 text-amber-600",
      href: "/reviews",
    },
    {
      label: "今日新用户",
      value: stats?.newUsersToday ?? "--",
      icon: Users,
      color: "bg-blue-50 text-blue-600",
      href: "/users",
    },
    {
      label: "今日新企业",
      value: stats?.newCompaniesToday ?? "--",
      icon: Building2,
      color: "bg-green-50 text-green-600",
      href: "/companies",
    },
    {
      label: "待处理工单",
      value: stats?.pendingTickets ?? "--",
      icon: MessageSquare,
      color: "bg-purple-50 text-purple-600",
      href: "/tickets",
    },
  ];

  // 获取审核类型的中文显示
  function getReviewTypeLabel(type: string) {
    const map: Record<string, string> = {
      PERSONAL: "个人认证",
      COMPANY: "企业认证",
      MEMBERSHIP: "成员申请",
    };
    return map[type] ?? type;
  }

  // 获取状态 Badge
  function getStatusBadge(status: string) {
    if (status === "PENDING" || status === "OPEN") return <Badge variant="warning">待处理</Badge>;
    if (status === "APPROVED" || status === "RESOLVED") return <Badge variant="success">已通过</Badge>;
    if (status === "REJECTED" || status === "CLOSED") return <Badge variant="neutral">已关闭</Badge>;
    return <Badge>{status}</Badge>;
  }

  // 获取优先级 Badge
  function getPriorityBadge(priority: string) {
    if (priority === "URGENT") return <Badge variant="danger">紧急</Badge>;
    if (priority === "HIGH") return <Badge variant="warning">高</Badge>;
    if (priority === "MEDIUM") return <Badge variant="info">中</Badge>;
    return <Badge variant="neutral">低</Badge>;
  }

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-800">首页</h1>
        <p className="mt-1 text-sm text-slate-500">平台运营数据概览</p>
      </div>

      {/* 统计卡片 */}
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
              <p className="text-2xl font-bold text-slate-800">
                {statsLoading ? "..." : value}
              </p>
            </div>
          </Link>
        ))}
      </div>

      {/* 下方两栏：最近审核 + 最近工单 */}
      <div className="grid gap-6 lg:grid-cols-2">
        {/* 最近审核 */}
        <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-base font-bold text-slate-700">最近审核</h2>
            <Link
              href="/reviews"
              className="flex items-center gap-1 text-xs font-semibold text-blue-600 hover:underline"
            >
              查看全部 <ArrowRight className="h-3 w-3" />
            </Link>
          </div>
          {reviewsLoading ? (
            <p className="py-8 text-center text-sm text-slate-400">加载中…</p>
          ) : reviews && reviews.length > 0 ? (
            <div className="divide-y divide-slate-100">
              {reviews.map((item) => (
                <div key={item.id} className="flex items-center justify-between py-3">
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-slate-700">
                      {getReviewTypeLabel(item.type)} - {item.targetName}
                    </p>
                    <p className="mt-0.5 flex items-center gap-1 text-xs text-slate-400">
                      <Clock className="h-3 w-3" />
                      {item.createdAt}
                    </p>
                  </div>
                  {getStatusBadge(item.status)}
                </div>
              ))}
            </div>
          ) : (
            <p className="py-8 text-center text-sm text-slate-400">暂无审核记录</p>
          )}
        </section>

        {/* 最近工单 */}
        <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-base font-bold text-slate-700">最近工单</h2>
            <Link
              href="/tickets"
              className="flex items-center gap-1 text-xs font-semibold text-blue-600 hover:underline"
            >
              查看全部 <ArrowRight className="h-3 w-3" />
            </Link>
          </div>
          {ticketsLoading ? (
            <p className="py-8 text-center text-sm text-slate-400">加载中…</p>
          ) : tickets && tickets.length > 0 ? (
            <div className="divide-y divide-slate-100">
              {tickets.map((item) => (
                <div key={item.id} className="flex items-center justify-between py-3">
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium text-slate-700">
                      {item.title}
                    </p>
                    <div className="mt-0.5 flex items-center gap-2 text-xs text-slate-400">
                      <Clock className="h-3 w-3" />
                      {item.createdAt}
                      <span className="text-slate-300">|</span>
                      {getPriorityBadge(item.priority)}
                    </div>
                  </div>
                  {getStatusBadge(item.status)}
                </div>
              ))}
            </div>
          ) : (
            <p className="py-8 text-center text-sm text-slate-400">暂无工单记录</p>
          )}
        </section>
      </div>
    </div>
  );
}