"use client";

// 权益与权限页面：管理企业权益覆盖
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { KeyRound, Save } from "lucide-react";

export default function EntitlementsPage() {
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_ENTITLEMENT_EDIT");
  const [tenantId, setTenantId] = useState("");
  const [featureCode, setFeatureCode] = useState("");
  const [action, setAction] = useState("ENABLE");
  const [limitValue, setLimitValue] = useState("");
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState<string | null>(null);

  const addOverrideMutation = useMutation({
    mutationFn: () =>
      adminApiFetch(`/platform/recruitment/tenants/${tenantId}/entitlement-overrides`, {
        method: "POST",
        body: JSON.stringify({
          featureCode,
          action,
          limitValue: limitValue ? Number(limitValue) : null,
          reason,
          startsAt: new Date().toISOString(),
          endsAt: null,
        }),
      }),
    onSuccess: () => {
      setMessage("权益覆盖已添加");
      setTenantId(""); setFeatureCode(""); setLimitValue(""); setReason("");
    },
    onError: (e: unknown) => setMessage(`操作失败：${e instanceof Error ? e.message : "操作失败，请稍后重试"}`),
  });

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-800">权益与权限</h1>
        <p className="mt-1 text-sm text-slate-500">为企业配置权益覆盖（启用/禁用/设限）</p>
      </div>

      <div className="max-w-2xl rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="mb-4 flex items-center gap-2">
          <KeyRound className="h-5 w-5 text-blue-600" />
          <h2 className="text-base font-semibold text-slate-700">添加权益覆盖</h2>
        </div>

        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-slate-700">企业ID (Tenant ID)</label>
            <input value={tenantId} onChange={(e) => setTenantId(e.target.value)}
              placeholder="请输入企业ID" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-slate-700">功能编码</label>
            <input value={featureCode} onChange={(e) => setFeatureCode(e.target.value)}
              placeholder="如：AI_RESUME_PARSE" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-700">操作类型</label>
              <select value={action} onChange={(e) => setAction(e.target.value)}
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm">
                <option value="ENABLE">启用</option>
                <option value="DISABLE">禁用</option>
                <option value="SET_LIMIT">设限</option>
              </select>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-700">限制值（可选）</label>
              <input value={limitValue} onChange={(e) => setLimitValue(e.target.value)} type="number"
                placeholder="设限时填写" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            </div>
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-slate-700">原因</label>
            <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2}
              placeholder="请说明操作原因" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
          </div>

          {message && (
            <div className={`rounded-lg px-4 py-2 text-sm ${message.includes("失败") ? "bg-red-50 text-red-700" : "bg-green-50 text-green-700"}`}>
              {message}
            </div>
          )}

          <button
            onClick={() => addOverrideMutation.mutate()}
            disabled={!canEdit || !tenantId || !featureCode || !reason || addOverrideMutation.isPending}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
          >
            <Save className="h-4 w-4" />
            {!canEdit ? "无编辑权限" : addOverrideMutation.isPending ? "提交中…" : "添加覆盖"}
          </button>
        </div>
      </div>
    </div>
  );
}
