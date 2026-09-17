"use client";

// 审核中心：企业注册审核（对接后端企业注册审核接口）
import { useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/lib/use-permission";

type Registration = {
  registration_id: string;
  legal_name: string;
  credit_code: string;
  contact_name: string;
  status: string;
  rejection_reason: string | null;
  created_at: string;
  reviewed_at: string | null;
  tenant_id: string | null;
};

export default function ReviewsPage() {
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_REVIEW_EDIT");
  const [status, setStatus] = useState("PENDING_REVIEW");

  const { data, isLoading } = useQuery({
    queryKey: ["enterprise-registrations", status],
    queryFn: () => adminApiFetch<Registration[]>(`/platform/recruitment/enterprise-registrations?status=${status}`),
  });

  const approveMutation = useMutation({
    mutationFn: (id: string) =>
      adminApiFetch(`/platform/recruitment/enterprise-registrations/${id}/approve`, { method: "POST" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["enterprise-registrations"] }),
  });

  const rejectMutation = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      adminApiFetch(`/platform/recruitment/enterprise-registrations/${id}/reject`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["enterprise-registrations"] }),
  });

  const items = data ?? [];

  function getStatusBadge(s: string) {
    if (s === "PENDING_REVIEW") return <Badge variant="warning">待审核</Badge>;
    if (s === "APPROVED" || s === "TRIAL_ACTIVE") return <Badge variant="success">已通过</Badge>;
    if (s === "REJECTED") return <Badge variant="danger">已拒绝</Badge>;
    return <Badge>{s}</Badge>;
  }

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-800">审核中心</h1>
        <p className="mt-1 text-sm text-slate-500">处理企业注册申请</p>
      </div>

      <div className="mb-4 flex gap-2">
        <button
          onClick={() => setStatus("PENDING_REVIEW")}
          className={`rounded-lg px-4 py-2 text-sm font-medium ${status === "PENDING_REVIEW" ? "bg-blue-600 text-white" : "bg-slate-100 text-slate-600"}`}
        >
          待审核
        </button>
        <button
          onClick={() => setStatus("APPROVED")}
          className={`rounded-lg px-4 py-2 text-sm font-medium ${status === "APPROVED" ? "bg-blue-600 text-white" : "bg-slate-100 text-slate-600"}`}
        >
          已通过
        </button>
        <button
          onClick={() => setStatus("REJECTED")}
          className={`rounded-lg px-4 py-2 text-sm font-medium ${status === "REJECTED" ? "bg-blue-600 text-white" : "bg-slate-100 text-slate-600"}`}
        >
          已拒绝
        </button>
      </div>

      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : items.length === 0 ? (
          <div className="p-8 text-center text-sm text-slate-400">暂无审核记录</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3">企业名称</th>
                <th className="px-4 py-3">信用代码</th>
                <th className="px-4 py-3">联系人</th>
                <th className="px-4 py-3">状态</th>
                <th className="px-4 py-3">申请时间</th>
                <th className="px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.registration_id} className="border-b border-slate-100 hover:bg-slate-50">
                  <td className="px-4 py-3 text-sm font-medium text-slate-800">
                    <Link href={`/reviews/enterprise-registrations/${item.registration_id}`} className="text-blue-600 hover:underline">
                      {item.legal_name}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-sm text-slate-500">{item.credit_code}</td>
                  <td className="px-4 py-3 text-sm text-slate-600">{item.contact_name}</td>
                  <td className="px-4 py-3">{getStatusBadge(item.status)}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{item.created_at}</td>
                  <td className="px-4 py-3">
                    {item.status === "PENDING_REVIEW" && canEdit && (
                      <div className="flex gap-2">
                        <Button size="sm" variant="confirm" onClick={() => approveMutation.mutate(item.registration_id)} disabled={approveMutation.isPending}>
                          通过
                        </Button>
                        <Button
                          size="sm"
                          variant="danger"
                          onClick={() => {
                            const reason = prompt("请输入拒绝原因：");
                            if (reason) rejectMutation.mutate({ id: item.registration_id, reason });
                          }}
                          disabled={rejectMutation.isPending}
                        >
                          拒绝
                        </Button>
                      </div>
                    )}
                    {item.status === "REJECTED" && item.rejection_reason && (
                      <span className="text-xs text-red-600">原因：{item.rejection_reason}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
