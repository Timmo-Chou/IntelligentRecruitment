"use client";

// 收款账户配置：管理多个收款账户（银行/支付宝/微信）
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { Badge } from "@/components/ui/badge";
import { Landmark, Plus, Trash2, Edit2 } from "lucide-react";

type RechargeAccount = {
  id: string;
  account_type: string;
  account_name: string;
  account_number: string;
  bank_name: string;
  qr_code_url: string;
  is_default: boolean;
  status: string;
};

export default function RechargeSettingsPage() {
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_RECHARGE_EDIT");
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState({
    accountType: "BANK",
    accountName: "",
    accountNumber: "",
    bankName: "",
    qrCodeUrl: "",
    isDefault: false,
  });

  const { data, isLoading } = useQuery({
    queryKey: ["recharge-settings"],
    queryFn: () => adminApiFetch<RechargeAccount[]>("/platform/recharge-settings"),
  });

  const createMutation = useMutation({
    mutationFn: () =>
      adminApiFetch("/platform/recharge-settings", { method: "POST", body: JSON.stringify(form) }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ["recharge-settings"] }); resetForm(); },
  });

  const updateMutation = useMutation({
    mutationFn: (id: string) =>
      adminApiFetch(`/platform/recharge-settings/${id}`, { method: "PUT", body: JSON.stringify(form) }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ["recharge-settings"] }); resetForm(); },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => adminApiFetch(`/platform/recharge-settings/${id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["recharge-settings"] }),
  });

  function resetForm() {
    setForm({ accountType: "BANK", accountName: "", accountNumber: "", bankName: "", qrCodeUrl: "", isDefault: false });
    setEditingId(null);
    setShowForm(false);
  }

  function startEdit(item: RechargeAccount) {
    setEditingId(item.id);
    setForm({
      accountType: item.account_type,
      accountName: item.account_name,
      accountNumber: item.account_number,
      bankName: item.bank_name || "",
      qrCodeUrl: item.qr_code_url || "",
      isDefault: item.is_default,
    });
    setShowForm(true);
  }

  function handleSubmit() {
    if (editingId) updateMutation.mutate(editingId);
    else createMutation.mutate();
  }

  function getTypeLabel(t: string) {
    const map: Record<string, string> = { BANK: "银行", ALIPAY: "支付宝", WECHAT: "微信" };
    return map[t] ?? t;
  }

  const items = data ?? [];

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold text-slate-800">
            <Landmark className="text-brand" /> 收款账户配置
          </h1>
          <p className="mt-1 text-sm text-slate-500">配置用户端展示的收款账户</p>
        </div>
        {canEdit && (
          <button onClick={() => { setShowForm(true); setEditingId(null); }}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700">
            <Plus className="h-4 w-4" /> 新增账户
          </button>
        )}
      </div>

      {showForm && (
        <div className="mb-6 max-w-2xl rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="mb-4 text-base font-semibold text-slate-700">{editingId ? "编辑账户" : "新增账户"}</h2>
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-slate-700">账户类型</label>
                <select value={form.accountType} onChange={(e) => setForm({ ...form, accountType: e.target.value })}
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm">
                  <option value="BANK">银行</option>
                  <option value="ALIPAY">支付宝</option>
                  <option value="WECHAT">微信</option>
                </select>
              </div>
              <div className="flex items-end">
                <label className="flex items-center gap-2 text-sm text-slate-700">
                  <input type="checkbox" checked={form.isDefault} onChange={(e) => setForm({ ...form, isDefault: e.target.checked })} />
                  设为默认
                </label>
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-700">账户名称</label>
              <input value={form.accountName} onChange={(e) => setForm({ ...form, accountName: e.target.value })}
                placeholder="如：某某科技有限公司" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-700">账号</label>
              <input value={form.accountNumber} onChange={(e) => setForm({ ...form, accountNumber: e.target.value })}
                placeholder="银行卡号/支付宝账号/微信号" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            </div>
            {form.accountType === "BANK" && (
              <div>
                <label className="mb-1 block text-sm font-medium text-slate-700">开户银行</label>
                <input value={form.bankName} onChange={(e) => setForm({ ...form, bankName: e.target.value })}
                  placeholder="如：中国工商银行" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </div>
            )}
            {(form.accountType === "ALIPAY" || form.accountType === "WECHAT") && (
              <div>
                <label className="mb-1 block text-sm font-medium text-slate-700">收款码 URL</label>
                <input value={form.qrCodeUrl} onChange={(e) => setForm({ ...form, qrCodeUrl: e.target.value })}
                  placeholder="收款码图片地址" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </div>
            )}
            <div className="flex gap-2">
              <button onClick={handleSubmit}
                disabled={createMutation.isPending || updateMutation.isPending}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50">
                {editingId ? "保存修改" : "创建"}
              </button>
              <button onClick={resetForm} className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 hover:bg-slate-50">
                取消
              </button>
            </div>
          </div>
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : items.length === 0 ? (
          <div className="p-8 text-center text-sm text-slate-400">暂无收款账户</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3">类型</th>
                <th className="px-4 py-3">账户名称</th>
                <th className="px-4 py-3">账号</th>
                <th className="px-4 py-3">默认</th>
                <th className="px-4 py-3">状态</th>
                <th className="px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id} className="border-b border-slate-100 hover:bg-slate-50">
                  <td className="px-4 py-3"><Badge variant="info">{getTypeLabel(item.account_type)}</Badge></td>
                  <td className="px-4 py-3 text-sm font-medium text-slate-800">{item.account_name}</td>
                  <td className="px-4 py-3 text-sm text-slate-600">{item.account_number}</td>
                  <td className="px-4 py-3">{item.is_default && <Badge variant="success">默认</Badge>}</td>
                  <td className="px-4 py-3">
                    <Badge variant={item.status === "ACTIVE" ? "success" : "neutral"}>
                      {item.status === "ACTIVE" ? "启用" : "停用"}
                    </Badge>
                  </td>
                  <td className="px-4 py-3">
                    {canEdit && (
                      <div className="flex gap-2">
                        <button onClick={() => startEdit(item)} className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-700">
                          <Edit2 className="h-3 w-3" /> 编辑
                        </button>
                        <button onClick={() => deleteMutation.mutate(item.id)} disabled={deleteMutation.isPending}
                          className="flex items-center gap-1 text-xs text-red-600 hover:text-red-700">
                          <Trash2 className="h-3 w-3" /> 删除
                        </button>
                      </div>
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
