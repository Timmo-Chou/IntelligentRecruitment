"use client";

// 管理员管理页面：列表 + 创建 + 审核 + 禁用
import { Plus, Pencil, Ban, CheckCircle, XCircle } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { Badge } from "@/components/ui/badge";
import { getDisplayLabel } from "@/lib/display-label";
import { AdminErrorNotice } from "@/components/admin-error-notice";

type Admin = {
  id: string;
  username: string;
  display_name: string;
  role: string;
  status: string;
  phone: string;
  register_reason: string;
  created_at: string;
};

export default function AdminsPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_ADMIN_EDIT");
  const [showDialog, setShowDialog] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);
  const [form, setForm] = useState({
    username: "",
    password: "",
    displayName: "",
    phone: "",
    role: "OPERATOR",
  });

  const { data: admins, isLoading, error: loadError } = useQuery({
    queryKey: ["admins"],
    queryFn: () => adminApiFetch<Admin[]>("/platform/admins"),
  });

  const createMutation = useMutation({
    mutationFn: () =>
      adminApiFetch("/platform/admins", { method: "POST", body: JSON.stringify(form) }),
    onSuccess: () => {
      setActionError(null);
      setShowDialog(false);
      setForm({ username: "", password: "", displayName: "", phone: "", role: "OPERATOR" });
      queryClient.invalidateQueries({ queryKey: ["admins"] });
    },
    onError: setActionError,
  });

  const approveMutation = useMutation({
    mutationFn: ({ id, role }: { id: string; role: string }) =>
      adminApiFetch(`/platform/admins/${id}/approve`, { method: "POST", body: JSON.stringify({ role }) }),
    onSuccess: () => { setActionError(null); return queryClient.invalidateQueries({ queryKey: ["admins"] }); },
    onError: setActionError,
  });

  const rejectMutation = useMutation({
    mutationFn: (id: string) =>
      adminApiFetch(`/platform/admins/${id}/reject`, { method: "POST" }),
    onSuccess: () => { setActionError(null); return queryClient.invalidateQueries({ queryKey: ["admins"] }); },
    onError: setActionError,
  });

  const disableMutation = useMutation({
    mutationFn: (id: string) =>
      adminApiFetch(`/platform/admins/${id}/disable`, { method: "POST" }),
    onSuccess: () => { setActionError(null); return queryClient.invalidateQueries({ queryKey: ["admins"] }); },
    onError: setActionError,
  });

  function getRoleBadge(role: string) {
    return <Badge variant={role === "SUPER_ADMIN" ? "danger" : "info"}>{getDisplayLabel("admin.role", role, "管理员角色暂未识别")}</Badge>;
  }

  function getStatusBadge(status: string) {
    const variant = status === "ACTIVE" ? "success" : status === "DISABLED" ? "danger" : "warning";
    return <Badge variant={variant}>{getDisplayLabel("admin.status", status, "管理员状态暂未识别")}</Badge>;
  }

  const items = admins ?? [];

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-800">管理员管理</h1>
          <p className="mt-1 text-sm text-slate-500">管理系统管理员账号</p>
        </div>
        {canEdit && (
          <button onClick={() => setShowDialog(true)}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700">
            <Plus className="h-4 w-4" /> 新增管理员
          </button>
        )}
      </div>
      {loadError && <div className="mb-4"><AdminErrorNotice error={loadError} fallback="管理员列表加载失败，请稍后重试。" /></div>}
      {actionError != null && !showDialog && <AdminErrorNotice error={actionError} fallback="管理员操作暂未成功，请稍后重试。" className="mb-4 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" />}

      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : loadError ? (
          <div className="p-8 text-center text-sm text-slate-400">无法显示管理员列表</div>
        ) : items.length === 0 ? (
          <div className="p-8 text-center text-sm text-slate-400">暂无管理员</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3">用户名</th>
                <th className="px-4 py-3">姓名</th>
                <th className="px-4 py-3">角色</th>
                <th className="px-4 py-3">状态</th>
                <th className="px-4 py-3">注册理由</th>
                <th className="px-4 py-3">创建时间</th>
                <th className="px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((admin) => (
                <tr key={admin.id} className="border-b border-slate-100 hover:bg-slate-50">
                  <td className="px-4 py-3 text-sm font-medium text-slate-800">{admin.username}</td>
                  <td className="px-4 py-3 text-sm text-slate-600">{admin.display_name}</td>
                  <td className="px-4 py-3">{getRoleBadge(admin.role)}</td>
                  <td className="px-4 py-3">{getStatusBadge(admin.status)}</td>
                  <td className="px-4 py-3 text-sm text-slate-500 max-w-xs truncate">{admin.register_reason || "-"}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{admin.created_at}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <button onClick={() => router.push(`/settings/admins/${admin.id}`)}
                        className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-700">
                        <Pencil className="h-3 w-3" /> 权限
                      </button>
                      {canEdit && admin.status === "PENDING_REVIEW" && (
                        <>
                          <button onClick={() => approveMutation.mutate({ id: admin.id, role: "OPERATOR" })}
                            className="flex items-center gap-1 text-xs text-green-600 hover:text-green-700">
                            <CheckCircle className="h-3 w-3" /> 通过
                          </button>
                          <button onClick={() => rejectMutation.mutate(admin.id)}
                            className="flex items-center gap-1 text-xs text-red-600 hover:text-red-700">
                            <XCircle className="h-3 w-3" /> 驳回
                          </button>
                        </>
                      )}
                      {canEdit && admin.status === "ACTIVE" && admin.role !== "SUPER_ADMIN" && (
                        <button onClick={() => { if (confirm("禁用该管理员？")) disableMutation.mutate(admin.id); }}
                          className="flex items-center gap-1 text-xs text-red-600 hover:text-red-700">
                          <Ban className="h-3 w-3" /> 禁用
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {showDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
            <h2 className="mb-4 text-lg font-bold text-slate-800">新增管理员</h2>
            <div className="space-y-4">
              {actionError != null && <AdminErrorNotice error={actionError} fallback="管理员创建暂未成功，请稍后重试。" className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" />}
              <div>
                <label className="mb-1 block text-sm font-medium text-slate-700">用户名</label>
                <input value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })}
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-slate-700">密码（至少8位）</label>
                <input type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })}
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-slate-700">姓名</label>
                <input value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })}
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-slate-700">手机号</label>
                <input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })}
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-slate-700">角色</label>
                <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm">
                  <option value="OPERATOR">运营人员</option>
                  <option value="ADMIN">管理员</option>
                  <option value="SUPER_ADMIN">超级管理员</option>
                </select>
              </div>
              <div className="flex justify-end gap-3">
                <button onClick={() => setShowDialog(false)}
                  className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 hover:bg-slate-50">取消</button>
                <button onClick={() => createMutation.mutate()} disabled={createMutation.isPending}
                  className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50">
                  {createMutation.isPending ? "创建中…" : "创建"}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
