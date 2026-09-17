"use client";

// 审核详情页：企业注册审核详情 + 通过/拒绝操作
import { ArrowLeft, FileCheck, CheckCircle, XCircle } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { Badge } from "@/components/ui/badge";

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

export default function ReviewDetailPage() {
  const params = useParams();
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_REVIEW_EDIT");
  const registrationId = params.id as string;

  // 从审核列表中获取该条记录的详情
  const { data, isLoading } = useQuery({
    queryKey: ["enterprise-registrations"],
    queryFn: () => adminApiFetch<Registration[]>("/platform/recruitment/enterprise-registrations"),
  });

  const item = data?.find((r) => r.registration_id === registrationId);

  const approveMutation = useMutation({
    mutationFn: () =>
      adminApiFetch(`/platform/recruitment/enterprise-registrations/${registrationId}/approve`, { method: "POST" }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["enterprise-registrations"] });
      alert("已通过审核");
    },
    onError: (e: Error) => alert("操作失败：" + e.message),
  });

  const [rejectReason, setRejectReason] = useState("");
  const [showReject, setShowReject] = useState(false);
  const rejectMutation = useMutation({
    mutationFn: () =>
      adminApiFetch(`/platform/recruitment/enterprise-registrations/${registrationId}/reject`, {
        method: "POST",
        body: JSON.stringify({ reason: rejectReason }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["enterprise-registrations"] });
      setShowReject(false);
      setRejectReason("");
      alert("已拒绝");
    },
    onError: (e: Error) => alert("操作失败：" + e.message),
  });

  if (isLoading) return <div className="p-8 text-center text-sm text-slate-400">加载中…</div>;
  if (!item) return <div className="p-8 text-center text-sm text-red-500">审核记录不存在</div>;

  function getStatusBadge(status: string) {
    if (status === "PENDING_REVIEW") return <Badge variant="warning">待审核</Badge>;
    if (status === "APPROVED" || status === "TRIAL_ACTIVE") return <Badge variant="success">已通过</Badge>;
    if (status === "REJECTED") return <Badge variant="danger">已拒绝</Badge>;
    return <Badge>{status}</Badge>;
  }

  return (
    <div>
      <Link href="/reviews" className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700">
        <ArrowLeft className="h-4 w-4" /> 返回审核列表
      </Link>

      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-amber-50">
            <FileCheck className="h-6 w-6 text-amber-600" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-slate-800">{item.legal_name}</h1>
            <div className="mt-1 flex items-center gap-2">
              <span className="text-sm text-slate-500">{item.credit_code}</span>
              {getStatusBadge(item.status)}
            </div>
          </div>
        </div>
      </div>

      <div className="max-w-2xl rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="mb-4 text-base font-semibold text-slate-700">企业注册信息</h2>
        <dl className="space-y-3 text-sm">
          <Info label="企业名称" value={item.legal_name} />
          <Info label="统一社会信用代码" value={item.credit_code} />
          <Info label="联系人" value={item.contact_name} />
          <Info label="申请时间" value={item.created_at} />
          {item.reviewed_at && <Info label="审核时间" value={item.reviewed_at} />}
          {item.rejection_reason && (
            <div className="rounded-lg bg-red-50 px-4 py-3">
              <dt className="text-xs text-red-500">拒绝原因</dt>
              <dd className="mt-1 text-sm text-red-700">{item.rejection_reason}</dd>
            </div>
          )}
        </dl>

        {item.status === "PENDING_REVIEW" && canEdit && (
          <div className="mt-6 flex gap-3">
            <button onClick={() => approveMutation.mutate()} disabled={approveMutation.isPending}
              className="flex items-center gap-2 rounded-lg bg-green-600 px-4 py-2 text-sm font-semibold text-white hover:bg-green-700 disabled:opacity-50">
              <CheckCircle className="h-4 w-4" /> 通过审核
            </button>
            <button onClick={() => setShowReject(true)}
              className="flex items-center gap-2 rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white hover:bg-red-700">
              <XCircle className="h-4 w-4" /> 拒绝
            </button>
          </div>
        )}
      </div>

      {/* 拒绝弹窗 */}
      {showReject && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
            <h2 className="mb-4 text-lg font-bold text-slate-800">拒绝审核</h2>
            <label className="mb-1 block text-sm font-medium text-slate-700">拒绝原因</label>
            <textarea value={rejectReason} onChange={(e) => setRejectReason(e.target.value)} rows={3}
              placeholder="请输入拒绝原因" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            <div className="mt-4 flex justify-end gap-3">
              <button onClick={() => setShowReject(false)}
                className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 hover:bg-slate-50">取消</button>
              <button onClick={() => rejectMutation.mutate()} disabled={!rejectReason.trim() || rejectMutation.isPending}
                className="rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white hover:bg-red-700 disabled:opacity-50">
                {rejectMutation.isPending ? "处理中…" : "确认拒绝"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function Info({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex justify-between">
      <dt className="text-slate-500">{label}</dt>
      <dd className="font-medium text-slate-800 text-right">{value || "-"}</dd>
    </div>
  );
}
