"use client";

// 管理员权限配置页面
import { ArrowLeft } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { adminApiFetch } from "@/lib/admin-api-client";

// 后端权限目录中的 admin 端权限码
const allPermissions = [
  { code: "ADMIN_REVIEW_VIEW", label: "查看审核中心" },
  { code: "ADMIN_REVIEW_EDIT", label: "编辑审核中心" },
  { code: "ADMIN_PRODUCT_VIEW", label: "查看产品运营" },
  { code: "ADMIN_PRODUCT_EDIT", label: "编辑产品运营" },
  { code: "ADMIN_ORDER_VIEW", label: "查看订单管理" },
  { code: "ADMIN_ORDER_EDIT", label: "编辑订单管理" },
  { code: "ADMIN_ENTITLEMENT_VIEW", label: "查看权益与权限" },
  { code: "ADMIN_ENTITLEMENT_EDIT", label: "编辑权益与权限" },
  { code: "ADMIN_BILLING_VIEW", label: "查看账本调整" },
  { code: "ADMIN_BILLING_EDIT", label: "编辑账本调整" },
  { code: "ADMIN_USER_VIEW", label: "查看用户管理" },
  { code: "ADMIN_USER_EDIT", label: "编辑用户管理" },
  { code: "ADMIN_TENANT_VIEW", label: "查看企业管理" },
  { code: "ADMIN_TENANT_EDIT", label: "编辑企业管理" },
  { code: "ADMIN_TICKET_VIEW", label: "查看工单管理" },
  { code: "ADMIN_TICKET_EDIT", label: "编辑工单管理" },
  { code: "ADMIN_RECHARGE_VIEW", label: "查看收款账户" },
  { code: "ADMIN_RECHARGE_EDIT", label: "编辑收款账户" },
  { code: "ADMIN_MENU_VIEW", label: "查看菜单设置" },
  { code: "ADMIN_MENU_EDIT", label: "编辑菜单设置" },
  { code: "ADMIN_ADMIN_VIEW", label: "查看管理员管理" },
  { code: "ADMIN_ADMIN_EDIT", label: "编辑管理员管理" },
];

export default function AdminPermissionsPage() {
  const params = useParams();
  const router = useRouter();
  const queryClient = useQueryClient();
  const adminId = params.adminId as string;

  const { data: currentPerms, isLoading } = useQuery({
    queryKey: ["admin-permissions", adminId],
    queryFn: () => adminApiFetch<string[]>(`/platform/admins/${adminId}/permissions`),
    enabled: !!adminId,
  });

  const [selected, setSelected] = useState<string[]>([]);
  const [initialized, setInitialized] = useState(false);

  // 初始化选中状态
  if (currentPerms && !initialized) {
    setSelected(currentPerms);
    setInitialized(true);
  }

  const updateMutation = useMutation({
    mutationFn: (codes: string[]) =>
      adminApiFetch(`/platform/admins/${adminId}/permissions`, {
        method: "PUT",
        body: JSON.stringify({ permissionCodes: codes }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["admin-permissions", adminId] });
      alert("权限已保存");
    },
    onError: (e: Error) => alert("保存失败：" + e.message),
  });

  function toggle(code: string) {
    setSelected((prev) =>
      prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]
    );
  }

  if (isLoading) return <div className="p-8 text-center text-sm text-slate-400">加载中…</div>;

  return (
    <div>
      <Link href="/settings/admins" className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700">
        <ArrowLeft className="h-4 w-4" /> 返回管理员列表
      </Link>

      <h1 className="mb-6 text-2xl font-bold text-slate-800">配置管理员权限</h1>
      <p className="mb-4 text-sm text-slate-500">管理员ID: {adminId}</p>

      <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-base font-semibold text-slate-700">权限列表</h2>
          <div className="flex gap-2">
            <button onClick={() => setSelected(allPermissions.map((p) => p.code))}
              className="text-xs text-blue-600 hover:underline">全选</button>
            <button onClick={() => setSelected([])} className="text-xs text-slate-500 hover:underline">清空</button>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {allPermissions.map((p) => (
            <label key={p.code} className="flex cursor-pointer items-center gap-2 rounded-lg border border-slate-200 px-3 py-2 hover:bg-slate-50">
              <input type="checkbox" checked={selected.includes(p.code)} onChange={() => toggle(p.code)} />
              <span className="text-sm text-slate-700">{p.label}</span>
            </label>
          ))}
        </div>

        <div className="mt-6 flex justify-end">
          <button onClick={() => updateMutation.mutate(selected)} disabled={updateMutation.isPending}
            className="rounded-lg bg-blue-600 px-6 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50">
            {updateMutation.isPending ? "保存中…" : "保存权限"}
          </button>
        </div>
      </div>
    </div>
  );
}
