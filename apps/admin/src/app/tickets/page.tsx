"use client";

// 工单列表页面：筛选 + 表格 + 新建工单
import { Search, Plus, ChevronLeft, ChevronRight } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

type Ticket = {
  id: string;
  ticketNumber: string;
  creatorUserId?: string;
  creatorName: string;
  companyId?: string;
  companyName?: string;
  title: string;
  category: string;
  priority: string;
  status: string;
  createdAt: string;
};

type PageResponse = {
  items: Ticket[];
  total: number;
  page: number;
  pageSize: number;
};

export default function TicketsPage() {
  const router = useRouter();
  const [statusFilter, setStatusFilter] = useState("");
  const [categoryFilter, setCategoryFilter] = useState("");
  const [page, setPage] = useState(1);

  const { data, isLoading, error } = useQuery({
    queryKey: ["tickets", statusFilter, categoryFilter, page],
    queryFn: async () => {
      const params = new URLSearchParams();
      if (statusFilter) params.set("status", statusFilter);
      if (categoryFilter) params.set("category", categoryFilter);
      params.set("page", String(page));
      params.set("pageSize", "20");
      return adminApiFetch<PageResponse>(`/platform/tickets?${params.toString()}`);
    },
  });

  const tickets = data?.items ?? [];
  const total = data?.total ?? 0;
  const totalPages = Math.ceil(total / 20);

  function getStatusBadge(status: string) {
    if (status === "OPEN") return <Badge variant="warning">待处理</Badge>;
    if (status === "IN_PROGRESS") return <Badge variant="info">处理中</Badge>;
    if (status === "RESOLVED") return <Badge variant="success">已解决</Badge>;
    if (status === "CLOSED") return <Badge variant="neutral">已关闭</Badge>;
    return <Badge>{status}</Badge>;
  }

  function getPriorityBadge(priority: string) {
    if (priority === "URGENT") return <Badge variant="danger">紧急</Badge>;
    if (priority === "HIGH") return <Badge variant="warning">高</Badge>;
    if (priority === "NORMAL") return <Badge variant="info">普通</Badge>;
    if (priority === "MEDIUM") return <Badge variant="info">中</Badge>;
    return <Badge variant="neutral">低</Badge>;
  }

  function getCategoryLabel(category: string) {
    const map: Record<string, string> = {
      ACCOUNT: "账号问题",
      BILLING: "账单问题",
      TECHNICAL: "技术问题",
      FEEDBACK: "功能反馈",
      OTHER: "其他",
    };
    return map[category] ?? category;
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-800">工单管理</h1>
          <p className="mt-1 text-sm text-slate-500">管理平台用户提交的工单</p>
        </div>
        <Button onClick={() => router.push("/tickets/new")}>
          <Plus className="h-4 w-4" />
          新建工单
        </Button>
      </div>

      {/* 筛选栏 */}
      <div className="mb-4 flex flex-wrap gap-3">
        <select
          value={statusFilter}
          onChange={(e) => { setStatusFilter(e.target.value); setPage(1); }}
          className="h-10 rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-700 focus:border-brand focus:ring-3 focus:ring-brand/10"
        >
          <option value="">全部状态</option>
          <option value="OPEN">待处理</option>
          <option value="IN_PROGRESS">处理中</option>
          <option value="RESOLVED">已解决</option>
          <option value="CLOSED">已关闭</option>
        </select>
        <select
          value={categoryFilter}
          onChange={(e) => { setCategoryFilter(e.target.value); setPage(1); }}
          className="h-10 rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-700 focus:border-brand focus:ring-3 focus:ring-brand/10"
        >
          <option value="">全部分类</option>
          <option value="ACCOUNT">账号问题</option>
          <option value="BILLING">账单问题</option>
          <option value="TECHNICAL">技术问题</option>
          <option value="FEEDBACK">功能反馈</option>
          <option value="OTHER">其他</option>
        </select>
      </div>

      {/* 数据表格 */}
      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : error ? (
          <div className="p-8 text-center text-sm text-red-500">加载失败，请稍后重试</div>
        ) : tickets.length === 0 ? (
          <div className="p-8 text-center text-sm text-slate-400">暂无工单数据</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3">工单编号</th>
                <th className="px-4 py-3">标题</th>
                <th className="px-4 py-3">分类</th>
                <th className="px-4 py-3">优先级</th>
                <th className="px-4 py-3">状态</th>
                <th className="px-4 py-3">创建者</th>
                <th className="px-4 py-3">创建时间</th>
              </tr>
            </thead>
            <tbody>
              {tickets.map((ticket, i) => (
                <tr
                  key={ticket.id}
                  onClick={() => router.push(`/tickets/${ticket.id}`)}
                  className={`cursor-pointer border-b border-slate-100 transition hover:bg-blue-50/50 ${
                    i % 2 === 0 ? "bg-white" : "bg-slate-50/50"
                  }`}
                >
                  <td className="px-4 py-3 text-sm font-mono text-slate-500">
                    {ticket.ticketNumber}
                  </td>
                  <td className="px-4 py-3 text-sm font-medium text-slate-800">{ticket.title}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">
                    {getCategoryLabel(ticket.category)}
                  </td>
                  <td className="px-4 py-3">{getPriorityBadge(ticket.priority)}</td>
                  <td className="px-4 py-3">{getStatusBadge(ticket.status)}</td>
                  <td className="px-4 py-3 text-sm">
                    <div className="font-medium text-slate-700">{ticket.creatorName}</div>
                    {ticket.companyName && (
                      <div className="text-xs text-slate-400">🏢 {ticket.companyName}</div>
                    )}
                  </td>
                  <td className="px-4 py-3 text-sm text-slate-500">{ticket.createdAt}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* 分页 */}
      {totalPages > 1 && (
        <div className="mt-4 flex items-center justify-between">
          <p className="text-sm text-slate-500">
            共 {total} 条记录，第 {page}/{totalPages} 页
          </p>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              disabled={page <= 1}
              className="flex h-8 w-8 items-center justify-center rounded-lg border border-slate-300 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-40"
            >
              <ChevronLeft className="h-4 w-4" />
            </button>
            {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => {
              const pageNum = i + 1;
              return (
                <button
                  key={pageNum}
                  onClick={() => setPage(pageNum)}
                  className={`flex h-8 w-8 items-center justify-center rounded-lg text-sm font-medium transition ${
                    pageNum === page
                      ? "bg-blue-600 text-white"
                      : "border border-slate-300 bg-white text-slate-600 hover:bg-slate-50"
                  }`}
                >
                  {pageNum}
                </button>
              );
            })}
            <button
              onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
              disabled={page >= totalPages}
              className="flex h-8 w-8 items-center justify-center rounded-lg border border-slate-300 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-40"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}