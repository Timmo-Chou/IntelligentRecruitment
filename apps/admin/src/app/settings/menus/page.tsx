"use client";

// 菜单管理页面：列表 + 新增/编辑/删除
import { Plus, Pencil, Trash2 } from "lucide-react";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";

type MenuItem = {
  id: string;
  parent_id: string | null;
  code: string;
  name: string;
  path: string;
  icon: string;
  sort_order: number;
  permission_code: string;
  status: string;
};

export default function MenusPage() {
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_MENU_EDIT");
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState({
    parentId: "",
    code: "",
    name: "",
    path: "",
    icon: "",
    sortOrder: 0,
    permissionCode: "",
  });

  const { data, isLoading } = useQuery({
    queryKey: ["menus"],
    queryFn: () => adminApiFetch<MenuItem[]>("/platform/menus"),
  });

  const createMutation = useMutation({
    mutationFn: () =>
      adminApiFetch("/platform/menus", { method: "POST", body: JSON.stringify(form) }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ["menus"] }); resetForm(); },
  });

  const updateMutation = useMutation({
    mutationFn: (id: string) =>
      adminApiFetch(`/platform/menus/${id}`, { method: "PUT", body: JSON.stringify(form) }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ["menus"] }); resetForm(); },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => adminApiFetch(`/platform/menus/${id}`, { method: "DELETE" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["menus"] }),
  });

  function resetForm() {
    setForm({ parentId: "", code: "", name: "", path: "", icon: "", sortOrder: 0, permissionCode: "" });
    setEditingId(null);
    setShowForm(false);
  }

  function startEdit(item: MenuItem) {
    setEditingId(item.id);
    setForm({
      parentId: item.parent_id || "",
      code: item.code,
      name: item.name,
      path: item.path || "",
      icon: item.icon || "",
      sortOrder: item.sort_order,
      permissionCode: item.permission_code || "",
    });
    setShowForm(true);
  }

  function handleSubmit() {
    const body = { ...form, parentId: form.parentId || null };
    if (editingId) updateMutation.mutate(editingId);
    else createMutation.mutate();
  }

  const items = data ?? [];

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-800">菜单管理</h1>
          <p className="mt-1 text-sm text-slate-500">管理平台管理后台的菜单结构</p>
        </div>
        {canEdit && (
          <button onClick={() => { setShowForm(true); setEditingId(null); }}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700">
            <Plus className="h-4 w-4" /> 新增菜单
          </button>
        )}
      </div>

      {showForm && (
        <div className="mb-6 max-w-2xl rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="mb-4 text-base font-semibold text-slate-700">{editingId ? "编辑菜单" : "新增菜单"}</h2>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-700">菜单编码</label>
              <input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })}
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-700">菜单名称</label>
              <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })}
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-700">路径</label>
              <input value={form.path} onChange={(e) => setForm({ ...form, path: e.target.value })}
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-700">图标</label>
              <input value={form.icon} onChange={(e) => setForm({ ...form, icon: e.target.value })}
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-700">排序</label>
              <input type="number" value={form.sortOrder} onChange={(e) => setForm({ ...form, sortOrder: Number(e.target.value) })}
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-slate-700">权限码</label>
              <input value={form.permissionCode} onChange={(e) => setForm({ ...form, permissionCode: e.target.value })}
                placeholder="如：ADMIN_USER_VIEW" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            </div>
          </div>
          <div className="mt-4 flex gap-2">
            <button onClick={handleSubmit} disabled={createMutation.isPending || updateMutation.isPending}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50">
              {editingId ? "保存" : "创建"}
            </button>
            <button onClick={resetForm} className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 hover:bg-slate-50">
              取消
            </button>
          </div>
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : items.length === 0 ? (
          <div className="p-8 text-center text-sm text-slate-400">暂无菜单数据</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3">编码</th>
                <th className="px-4 py-3">名称</th>
                <th className="px-4 py-3">路径</th>
                <th className="px-4 py-3">图标</th>
                <th className="px-4 py-3">排序</th>
                <th className="px-4 py-3">权限码</th>
                <th className="px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id} className="border-b border-slate-100 hover:bg-slate-50">
                  <td className="px-4 py-3 text-sm font-mono text-slate-600">{item.code}</td>
                  <td className="px-4 py-3 text-sm font-medium text-slate-800">{item.name}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{item.path}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{item.icon}</td>
                  <td className="px-4 py-3 text-sm text-slate-600">{item.sort_order}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{item.permission_code}</td>
                  <td className="px-4 py-3">
                    {canEdit && (
                      <div className="flex gap-2">
                        <button onClick={() => startEdit(item)} className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-700">
                          <Pencil className="h-3 w-3" /> 编辑
                        </button>
                        <button onClick={() => { if (confirm("删除该菜单？")) deleteMutation.mutate(item.id); }}
                          disabled={deleteMutation.isPending} className="flex items-center gap-1 text-xs text-red-600 hover:text-red-700">
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
