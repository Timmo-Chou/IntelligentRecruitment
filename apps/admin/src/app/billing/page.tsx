"use client";

// 账本管理页面：积分调整记录 + 企业积分调整
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { Badge } from "@/components/ui/badge";
import { Wallet, Plus } from "lucide-react";

type CreditAdjustment = {
  id: string;
  tenant_id: string;
  adjustment_type: string;
  amount: number;
  reason: string;
  created_at: string;
  expires_at: string | null;
};

export default function BillingPage() {
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_BILLING_EDIT");
  const [showForm, setShowForm] = useState(false);
  const [tenantId, setTenantId] = useState("");
  const [adjustmentType, setAdjustmentType] = useState("CORRECTION_ADD");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ["credit-adjustments"],
    queryFn: () => adminApiFetch<CreditAdjustment[]>("/platform/recruitment/credit-adjustments"),
  });

  const adjustMutation = useMutation({
    mutationFn: () =>
      adminApiFetch(`/platform/recruitment/tenants/${tenantId}/credit-adjustments`, {
        method: "POST",
        body: JSON.stringify({
          adjustmentType,
          amount: Number(amount),
          reason,
          expiresAt: null,
        }),
      }),
    onSuccess: () => {
      setMessage("积分调整成功");
      setTenantId(""); setAmount(""); setReason("");
      setShowForm(false);
      queryClient.invalidateQueries({ queryKey: ["credit-adjustments"] });
    },
    onError: (e: unknown) => setMessage(`调整失败：${e instanceof Error ? e.message : "操作失败，请稍后重试"}`),
  });

  const items = data ?? [];

  function getTypeLabel(t: string) {
    const map: Record<string, string> = {
      COMPENSATION: "补偿",
      CORRECTION_ADD: "修正增加",
      CORRECTION_DEDUCT: "修正扣减",
      TRIAL_GRANT: "试用赠送",
    };
    return map[t] ?? t;
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-800">账本管理</h1>
          <p className="mt-1 text-sm text-slate-500">企业积分调整记录管理</p>
        </div>
        {canEdit && (
          <button
            onClick={() => { setShowForm(!showForm); setMessage(null); }}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700"
          >
            <Plus className="h-4 w-4" /> 积分调整
          </button>
        )}
      </div>

      {showForm && (
        <div className="mb-6 max-w-xl rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="mb-4 text-base font-semibold text-slate-700">企业积分调整</h2>
          <div className="space-y-4">
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-700">企业ID</label>
              <input value={tenantId} onChange={(e) => setTenantId(e.target.value)}
                placeholder="请输入企业ID" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-slate-700">调整类型</label>
                <select value={adjustmentType} onChange={(e) => setAdjustmentType(e.target.value)}
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm">
                  <option value="CORRECTION_ADD">修正增加</option>
                  <option value="CORRECTION_DEDUCT">修正扣减</option>
                  <option value="COMPENSATION">补偿</option>
                  <option value="TRIAL_GRANT">试用赠送</option>
                </select>
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-slate-700">积分数量</label>
                <input value={amount} onChange={(e) => setAmount(e.target.value)} type="number"
                  placeholder="请输入积分数量" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-700">原因</label>
              <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2}
                placeholder="请说明调整原因" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            </div>
            {message && (
              <div className={`rounded-lg px-4 py-2 text-sm ${message.includes("失败") ? "bg-red-50 text-red-700" : "bg-green-50 text-green-700"}`}>
                {message}
              </div>
            )}
            <button
              onClick={() => adjustMutation.mutate()}
              disabled={!tenantId || !amount || !reason || adjustMutation.isPending}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {adjustMutation.isPending ? "提交中…" : "提交调整"}
            </button>
          </div>
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : items.length === 0 ? (
          <div className="p-8 text-center text-sm text-slate-400">暂无调整记录</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3">企业ID</th>
                <th className="px-4 py-3">类型</th>
                <th className="px-4 py-3">数量</th>
                <th className="px-4 py-3">原因</th>
                <th className="px-4 py-3">时间</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id} className="border-b border-slate-100 hover:bg-slate-50">
                  <td className="px-4 py-3 text-sm font-mono text-slate-600">{item.tenant_id}</td>
                  <td className="px-4 py-3">
                    <Badge variant={item.amount > 0 ? "success" : "danger"}>{getTypeLabel(item.adjustment_type)}</Badge>
                  </td>
                  <td className={`px-4 py-3 text-sm font-medium ${item.amount > 0 ? "text-green-600" : "text-red-600"}`}>
                    {item.amount > 0 ? "+" : ""}{item.amount}
                  </td>
                  <td className="px-4 py-3 text-sm text-slate-600">{item.reason}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{item.created_at}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
