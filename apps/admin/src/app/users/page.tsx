"use client";

// 用户列表页面：搜索 + 筛选 + 分页表格
import { Search, ChevronLeft, ChevronRight } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";

// 用户数据类型
type User = {
  userId: string;
  displayName: string;
  phone: string;
  status: string;
  verificationStatus: string;
  createdAt: string;
};

type PageResponse = {
  items: User[];
  total: number;
  page: number;
  pageSize: number;
};

export default function UsersPage() {
  const router = useRouter();
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [page, setPage] = useState(1);

  const { data, isLoading, error } = useQuery({
    queryKey: ["users", search, statusFilter, page],
    queryFn: async () => {
      const params = new URLSearchParams();
      if (search) params.set("search", search);
      if (statusFilter) params.set("status", statusFilter);
      params.set("page", String(page));
      params.set("pageSize", "20");
      return adminApiFetch<PageResponse>(`/platform/users?${params.toString()}`);
    },
  });

  const users = data?.items ?? [];
  const total = data?.total ?? 0;
  const totalPages = Math.ceil(total / 20);

  // 获取状态 Badge
  function getStatusBadge(status: string) {
    if (status === "ACTIVE") return <Badge variant="success">正常</Badge>;
    if (status === "DISABLED") return <Badge variant="danger">已禁用</Badge>;
    return <Badge>{status}</Badge>;
  }

  // 获取认证状态 Badge
  function getVerificationBadge(status: string) {
    if (status === "VERIFIED") return <Badge variant="success">已认证</Badge>;
    if (status === "PENDING") return <Badge variant="warning">待认证</Badge>;
    if (status === "REJECTED") return <Badge variant="danger">已拒绝</Badge>;
    return <Badge variant="neutral">未认证</Badge>;
  }

  // 手机号后四位脱敏
  function maskPhone(phone: string) {
    return phone ? `****${phone.slice(-4)}` : "---";
  }

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-800">用户管理</h1>
        <p className="mt-1 text-sm text-slate-500">管理平台所有注册用户</p>
      </div>

      {/* 搜索和筛选栏 */}
      <div className="mb-4 flex flex-wrap gap-3">
        <div className="relative w-full max-w-sm">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <Input
            placeholder="搜索用户ID或显示名…"
            value={search}
            onChange={(e) => { setSearch(e.target.value); setPage(1); }}
            className="pl-10"
          />
        </div>
        <select
          value={statusFilter}
          onChange={(e) => { setStatusFilter(e.target.value); setPage(1); }}
          className="h-10 rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-700 focus:border-brand focus:ring-3 focus:ring-brand/10"
        >
          <option value="">全部状态</option>
          <option value="ACTIVE">正常</option>
          <option value="DISABLED">已禁用</option>
        </select>
      </div>

      {/* 数据表格 */}
      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : error ? (
          <div className="p-8 text-center text-sm text-red-500">加载失败，请稍后重试</div>
        ) : users.length === 0 ? (
          <div className="p-8 text-center text-sm text-slate-400">暂无用户数据</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3">用户ID</th>
                <th className="px-4 py-3">显示名</th>
                <th className="px-4 py-3">手机号</th>
                <th className="px-4 py-3">状态</th>
                <th className="px-4 py-3">认证状态</th>
                <th className="px-4 py-3">注册时间</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user, i) => (
                <tr
                  key={user.userId}
                  onClick={() => router.push(`/users/${user.userId}`)}
                  className={`cursor-pointer border-b border-slate-100 transition hover:bg-blue-50/50 ${
                    i % 2 === 0 ? "bg-white" : "bg-slate-50/50"
                  }`}
                >
                  <td className="px-4 py-3 text-sm font-mono text-slate-600">{user.userId}</td>
                  <td className="px-4 py-3 text-sm font-medium text-slate-800">{user.displayName}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{maskPhone(user.phone)}</td>
                  <td className="px-4 py-3">{getStatusBadge(user.status)}</td>
                  <td className="px-4 py-3">{getVerificationBadge(user.verificationStatus)}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{user.createdAt}</td>
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