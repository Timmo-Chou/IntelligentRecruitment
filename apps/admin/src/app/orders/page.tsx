"use client";

// 订单管理页面：个人订单列表 + 支付状态更新
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { Badge } from "@/components/ui/badge";
import { Search } from "lucide-react";

type Order = {
  id: string;
  order_no: string;
  user_id: string;
  product_id: string;
  amount: number;
  payment_status: string;
  created_at: string;
};

type PageResponse = {
  items: Order[];
  total: number;
  page: number;
  size: number;
};

export default function OrdersPage() {
  const [statusFilter, setStatusFilter] = useState("");
  const [page, setPage] = useState(1);
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_ORDER_EDIT");

  const { data, isLoading } = useQuery({
    queryKey: ["orders", statusFilter, page],
    queryFn: () => {
      const params = new URLSearchParams();
      if (statusFilter) params.set("status", statusFilter);
      params.set("page", String(page));
      params.set("size", "20");
      return adminApiFetch<PageResponse>(`/platform/recruitment/personal-orders?${params.toString()}`);
    },
  });

  const updatePaymentMutation = useMutation({
    mutationFn: ({ orderId, status }: { orderId: string; status: string }) =>
      adminApiFetch(`/platform/recruitment/personal-orders/${orderId}/payment-status`, {
        method: "POST",
        body: JSON.stringify({ status }),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["orders"] }),
  });

  const orders = data?.items ?? [];
  const total = data?.total ?? 0;
  const totalPages = Math.ceil(total / 20);

  function getPaymentBadge(status: string) {
    if (status === "PAID") return <Badge variant="success">已支付</Badge>;
    if (status === "PENDING") return <Badge variant="warning">待支付</Badge>;
    if (status === "FAILED") return <Badge variant="danger">支付失败</Badge>;
    if (status === "CANCELLED") return <Badge variant="neutral">已取消</Badge>;
    return <Badge>{status}</Badge>;
  }

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-800">订单管理</h1>
        <p className="mt-1 text-sm text-slate-500">管理个人用户的充值订单</p>
      </div>

      {/* 筛选栏 */}
      <div className="mb-4 flex gap-3">
        <select
          value={statusFilter}
          onChange={(e) => { setStatusFilter(e.target.value); setPage(1); }}
          className="h-10 rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-700"
        >
          <option value="">全部状态</option>
          <option value="PENDING">待支付</option>
          <option value="PAID">已支付</option>
          <option value="FAILED">支付失败</option>
          <option value="CANCELLED">已取消</option>
        </select>
      </div>

      {/* 订单表格 */}
      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : orders.length === 0 ? (
          <div className="p-8 text-center text-sm text-slate-400">暂无订单</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3">订单号</th>
                <th className="px-4 py-3">用户ID</th>
                <th className="px-4 py-3">金额</th>
                <th className="px-4 py-3">支付状态</th>
                <th className="px-4 py-3">创建时间</th>
                <th className="px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => (
                <tr key={o.id} className="border-b border-slate-100 hover:bg-slate-50">
                  <td className="px-4 py-3 text-sm font-mono text-slate-600">{o.order_no ?? o.id}</td>
                  <td className="px-4 py-3 text-sm text-slate-600">{o.user_id}</td>
                  <td className="px-4 py-3 text-sm font-medium text-slate-800">¥{(o.amount ?? 0).toFixed(2)}</td>
                  <td className="px-4 py-3">{getPaymentBadge(o.payment_status)}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{o.created_at}</td>
                  <td className="px-4 py-3">
                    {o.payment_status === "PENDING" && canEdit && (
                      <button
                        onClick={() => updatePaymentMutation.mutate({ orderId: o.id, status: "PAID" })}
                        className="text-xs text-green-600 hover:text-green-700"
                      >
                        标记已支付
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* 分页 */}
      {totalPages > 1 && (
        <div className="mt-4 flex items-center justify-between">
          <p className="text-sm text-slate-500">共 {total} 条，第 {page}/{totalPages} 页</p>
          <div className="flex gap-2">
            <button onClick={() => setPage((p) => Math.max(1, p - 1))} disabled={page <= 1}
              className="rounded-lg border border-slate-300 px-3 py-1 text-sm disabled:opacity-40">上一页</button>
            <button onClick={() => setPage((p) => Math.min(totalPages, p + 1))} disabled={page >= totalPages}
              className="rounded-lg border border-slate-300 px-3 py-1 text-sm disabled:opacity-40">下一页</button>
          </div>
        </div>
      )}
    </div>
  );
}
