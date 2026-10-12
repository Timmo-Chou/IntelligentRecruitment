"use client";

// 用户详情页：基本信息 + 所属企业 + 状态切换
import { ArrowLeft, User, Building2 } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { Badge } from "@/components/ui/badge";
import { getDisplayLabel } from "@/lib/display-label";
import { AdminErrorNotice } from "@/components/admin-error-notice";

type Membership = {
  id: string;
  tenant_id: string;
  role: string;
  status: string;
  joined_at: string;
  tenant_name: string;
};

type UserDetail = {
  id: string;
  display_name: string;
  phone: string;
  email: string | null;
  status: string;
  created_at: string;
  updated_at: string | null;
  memberships: Membership[];
};

export default function UserDetailPage() {
  const params = useParams();
  const userId = params.userId as string;
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_USER_EDIT");
  const [actionError, setActionError] = useState<unknown>(null);
  const [actionMessage, setActionMessage] = useState<string | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ["user-detail", userId],
    queryFn: () => adminApiFetch<UserDetail>(`/platform/users/${userId}`),
    enabled: !!userId,
  });

  const toggleStatusMutation = useMutation({
    mutationFn: (status: string) =>
      adminApiFetch(`/platform/users/${userId}/status`, {
        method: "POST",
        body: JSON.stringify({ status }),
      }),
    onSuccess: () => { setActionError(null); setActionMessage("用户状态已更新"); return queryClient.invalidateQueries({ queryKey: ["user-detail", userId] }); },
    onError: (error: Error) => { setActionMessage(null); setActionError(error); },
  });

  if (isLoading) return <div className="p-8 text-center text-sm text-slate-400">加载中…</div>;
  if (error) return <div className="p-8"><AdminErrorNotice error={error} fallback="用户信息加载暂未成功，请稍后重试。" className="rounded-lg bg-red-50 p-3 text-sm text-red-700" /></div>;
  if (!data) return <div className="p-8 text-center text-sm text-slate-500">用户信息暂不可用。</div>;

  function getStatusBadge(status: string) {
    return <Badge variant={status === "ACTIVE" ? "success" : "danger"}>{getDisplayLabel("user.status", status, "用户状态暂未识别")}</Badge>;
  }

  function getRoleBadge(role: string) {
    return <Badge variant={role === "OWNER" ? "danger" : "neutral"}>{getDisplayLabel("role.builtin", role, "角色名称暂未配置")}</Badge>;
  }

  return (
    <div>
      {actionError != null && <AdminErrorNotice error={actionError} fallback="用户状态更新暂未成功，请稍后重试。" className="mb-4 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" />}
      {actionMessage && <p role="status" className="mb-4 rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{actionMessage}</p>}
      <Link href="/users" className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700">
        <ArrowLeft className="h-4 w-4" /> 返回用户列表
      </Link>

      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-blue-50">
            <User className="h-6 w-6 text-blue-600" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-slate-800">{data.display_name}</h1>
            <div className="mt-1 flex items-center gap-2">
              <span className="text-sm text-slate-500">{data.phone}</span>
              {getStatusBadge(data.status)}
            </div>
          </div>
        </div>
        {canEdit && (
          <button
            onClick={() => {
              const next = data.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
              if (confirm(`确定要${next === "ACTIVE" ? "启用" : "禁用"}该用户吗？`)) {
                toggleStatusMutation.mutate(next);
              }
            }}
            disabled={toggleStatusMutation.isPending}
            className={`rounded-lg px-4 py-2 text-sm font-semibold text-white hover:opacity-90 disabled:opacity-50 ${
              data.status === "ACTIVE" ? "bg-red-600" : "bg-green-600"
            }`}
          >
            {data.status === "ACTIVE" ? "禁用用户" : "启用用户"}
          </button>
        )}
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* 基本信息 */}
        <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="mb-4 text-base font-semibold text-slate-700">基本信息</h2>
          <dl className="space-y-3 text-sm">
            <Info label="用户编号" value={data.id} />
            <Info label="姓名" value={data.display_name} />
            <Info label="手机号" value={data.phone} />
            <Info label="邮箱" value={data.email} />
            <Info label="注册时间" value={data.created_at} />
            <Info label="最后更新" value={data.updated_at} />
          </dl>
        </div>

        {/* 所属企业 */}
        <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <div className="mb-4 flex items-center gap-2">
            <Building2 className="h-5 w-5 text-slate-400" />
            <h2 className="text-base font-semibold text-slate-700">所属企业</h2>
          </div>
          {data.memberships.length === 0 ? (
            <p className="text-sm text-slate-400">未加入任何企业</p>
          ) : (
            <table className="w-full">
              <thead>
                <tr className="border-b border-slate-200 text-left text-xs font-semibold uppercase text-slate-500">
                  <th className="py-2">企业名称</th>
                  <th className="py-2">角色</th>
                  <th className="py-2">状态</th>
                  <th className="py-2">加入时间</th>
                </tr>
              </thead>
              <tbody>
                {data.memberships.map((m) => (
                  <tr key={m.id} className="border-b border-slate-100">
                    <td className="py-2 text-sm font-medium text-slate-800">
                      <Link href={`/companies/${m.tenant_id}`} className="text-blue-600 hover:underline">
                        {m.tenant_name}
                      </Link>
                    </td>
                    <td className="py-2">{getRoleBadge(m.role)}</td>
                    <td className="py-2"><Badge variant={m.status === "ACTIVE" ? "success" : "neutral"}>{getDisplayLabel("member.status", m.status, "成员状态暂未识别")}</Badge></td>
                    <td className="py-2 text-sm text-slate-500">{m.joined_at}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}

function Info({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex justify-between">
      <dt className="text-slate-500">{label}</dt>
      <dd className="font-medium text-slate-800 break-all text-right">{value || "-"}</dd>
    </div>
  );
}
