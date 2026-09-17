"use client";

// 企业列表页面：搜索 + 筛选 + 分页表格
import { Search, ChevronLeft, ChevronRight } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";

type Tenant = {
  id: string;
  legal_name: string;
  unified_social_credit_code: string;
  status: string;
  plan_product_name: string;
  seat_count: number;
  credit_balance: number;
  owner_name: string;
  owner_phone: string;
  created_at: string;
};

type PageResponse = {
  items: Tenant[];
  total: number;
  page: number;
  size: number;
};

export default function TenantsPage() {
  const router = useRouter();
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [page, setPage] = useState(1);

  const { data, isLoading, error } = useQuery({
    queryKey: ["tenants", search, statusFilter, page],
    queryFn: async () => {
      const params = new URLSearchParams();
      if (search) params.set("keyword", search);
      if (statusFilter) params.set("status", statusFilter);
      params.set("page", String(page));
      params.set("size", "20");
      return adminApiFetch<PageResponse>(`/platform/recruitment/tenants?${params.toString()}`);
    },
  });

  const tenants = data?.items ?? [];
  const total = data?.total ?? 0;
  const totalPages = Math.ceil(total / 20);

  function getStatusBadge(status: string) {
    if (status === "ACTIVE") return <Badge variant="success">正常</Badge>;
    if (status === "DISABLED") return <Badge variant="danger">已禁用</Badge>;
    if (status === "PENDING_REVIEW") return <Badge variant="warning">待审核</Badge>;
    return <Badge>{status}</Badge>;
  }

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-800">企业管理</h1>
        <p className="mt-1 text-sm text-slate-500">管理平台所有注册企业</p>
      </div>

      <div className="mb-4 flex flex-wrap gap-3">
        <div className="relative w-full max-w-sm">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <Input
            placeholder="搜索企业名称或信用代码…"
            value={search}
            onChange={(e) => { setSearch(e.target.value); setPage(1); }}
            className="pl-10"
          />
        </div>
        <select
          value={statusFilter}
          onChange={(e) => { setStatusFilter(e.target.value); setPage(1); }}
          className="h-10 rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-700"
        >
          <option value="">全部状态</option>
          <option value="ACTIVE">正常</option>
          <option value="PENDING_REVIEW">待审核</option>
          <option value="DISABLED">已禁用</option>
        </select>
      </div>

      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : error ? (
          <div className="p-8 text-center text-sm text-red-500">加载失败，请稍后重试</div>
        ) : tenants.length === 0 ? (
          <div className="p-8 text-center text-sm text-slate-400">暂无企业数据</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3">企业名称</th>
                <th className="px-4 py-3">信用代码</th>
                <th className="px-4 py-3">套餐</th>
                <th className="px-4 py-3">状态</th>
                <th className="px-4 py-3">积分余额</th>
                <th className="px-4 py-3">企业负责人</th>
                <th className="px-4 py-3">创建时间</th>
              </tr>
            </thead>
            <tbody>
              {tenants.map((tenant, i) => (
                <tr
                  key={tenant.id}
                  onClick={() => router.push(`/companies/${tenant.id}`)}
                  className={`cursor-pointer border-b border-slate-100 transition hover:bg-blue-50/50 ${i % 2 === 0 ? "bg-white" : "bg-slate-50/50"}`}
                >
                  <td className="px-4 py-3 text-sm font-medium text-slate-800">{tenant.legal_name}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{tenant.unified_social_credit_code}</td>
                  <td className="px-4 py-3 text-sm text-slate-600">{tenant.plan_product_name || "无"}</td>
                  <td className="px-4 py-3">{getStatusBadge(tenant.status)}</td>
                  <td className="px-4 py-3 text-sm text-slate-600">{tenant.credit_balance ?? 0}</td>
                  <td className="px-4 py-3 text-sm text-slate-600">
                    {tenant.owner_name || "未设置"}
                    {tenant.owner_phone && <span className="text-slate-400"> · {tenant.owner_phone}</span>}
                  </td>
                  <td className="px-4 py-3 text-sm text-slate-500">{tenant.created_at}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {totalPages > 1 && (
        <div className="mt-4 flex items-center justify-between">
          <p className="text-sm text-slate-500">共 {total} 条记录，第 {page}/{totalPages} 页</p>
          <div className="flex items-center gap-2">
            <button onClick={() => setPage((p) => Math.max(1, p - 1))} disabled={page <= 1}
              className="flex h-8 w-8 items-center justify-center rounded-lg border border-slate-300 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-40">
              <ChevronLeft className="h-4 w-4" />
            </button>
            {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => {
              const pageNum = i + 1;
              return (
                <button key={pageNum} onClick={() => setPage(pageNum)}
                  className={`flex h-8 w-8 items-center justify-center rounded-lg text-sm font-medium transition ${
                    pageNum === page ? "bg-blue-600 text-white" : "border border-slate-300 bg-white text-slate-600 hover:bg-slate-50"
                  }`}>{pageNum}</button>
              );
            })}
            <button onClick={() => setPage((p) => Math.min(totalPages, p + 1))} disabled={page >= totalPages}
              className="flex h-8 w-8 items-center justify-center rounded-lg border border-slate-300 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-40">
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
