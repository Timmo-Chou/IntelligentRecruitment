"use client";

// 企业详情页：基本信息 + 套餐 + 积分 + 成员列表 + Owner 转移
import { ArrowLeft, Building2, Users, CreditCard, UserCog } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { usePermission } from "@/lib/use-permission";
import { Badge } from "@/components/ui/badge";

type Member = {
  id: string;
  user_id: string;
  role: string;
  status: string;
  joined_at: string;
  display_name: string;
  phone: string;
};

type TenantDetail = {
  id: string;
  legal_name: string;
  unified_social_credit_code: string;
  status: string;
  contact_name: string;
  contact_phone: string;
  industry: string;
  scale: string;
  address: string;
  created_at: string;
  plan_product_name: string | null;
  seat_count: number | null;
  ends_at: string | null;
  credit_balance: number;
  members: Member[];
};

export default function TenantDetailPage() {
  const params = useParams();
  const tenantId = params.tenantId as string;
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const canEdit = hasPermission("ADMIN_TENANT_EDIT");
  const [showTransfer, setShowTransfer] = useState(false);
  const [newOwnerId, setNewOwnerId] = useState("");

  const { data, isLoading, error } = useQuery({
    queryKey: ["tenant-detail", tenantId],
    queryFn: () => adminApiFetch<TenantDetail>(`/platform/recruitment/tenants/${tenantId}`),
    enabled: !!tenantId,
  });

  const transferMutation = useMutation({
    mutationFn: () =>
      adminApiFetch(`/platform/tenants/${tenantId}/owner-transfer`, {
        method: "POST",
        body: JSON.stringify({ newOwnerUserId: newOwnerId }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["tenant-detail", tenantId] });
      setShowTransfer(false);
      setNewOwnerId("");
      alert("企业负责人已转移");
    },
    onError: (e: Error) => alert("转移失败：" + e.message),
  });

  if (isLoading) return <div className="p-8 text-center text-sm text-slate-400">加载中…</div>;
  if (error || !data) return <div className="p-8 text-center text-sm text-red-500">加载失败</div>;

  const owner = data.members.find((m) => m.role === "OWNER");
  const otherMembers = data.members.filter((m) => m.role !== "OWNER");

  function getStatusBadge(status: string) {
    if (status === "ACTIVE") return <Badge variant="success">正常</Badge>;
    if (status === "DISABLED") return <Badge variant="danger">已禁用</Badge>;
    return <Badge>{status}</Badge>;
  }

  function getRoleBadge(role: string) {
    if (role === "OWNER") return <Badge variant="danger">负责人</Badge>;
    if (role === "ADMIN") return <Badge variant="info">管理员</Badge>;
    return <Badge variant="neutral">成员</Badge>;
  }

  return (
    <div>
      <Link href="/companies" className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700">
        <ArrowLeft className="h-4 w-4" /> 返回企业列表
      </Link>

      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-blue-50">
            <Building2 className="h-6 w-6 text-blue-600" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-slate-800">{data.legal_name}</h1>
            <div className="mt-1 flex items-center gap-2">
              <span className="text-sm text-slate-500">{data.unified_social_credit_code}</span>
              {getStatusBadge(data.status)}
            </div>
          </div>
        </div>
        {canEdit && (
          <button onClick={() => setShowTransfer(true)}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700">
            <UserCog className="h-4 w-4" /> 转移负责人
          </button>
        )}
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        {/* 基本信息 */}
        <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm lg:col-span-2">
          <h2 className="mb-4 text-base font-semibold text-slate-700">基本信息</h2>
          <dl className="grid grid-cols-2 gap-4 text-sm">
            <Info label="联系人" value={data.contact_name} />
            <Info label="联系电话" value={data.contact_phone} />
            <Info label="行业" value={data.industry} />
            <Info label="规模" value={data.scale} />
            <Info label="地址" value={data.address} />
            <Info label="创建时间" value={data.created_at} />
          </dl>
        </div>

        {/* 套餐与积分 */}
        <div className="space-y-6">
          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <div className="mb-3 flex items-center gap-2">
              <CreditCard className="h-5 w-5 text-slate-400" />
              <h2 className="text-base font-semibold text-slate-700">套餐信息</h2>
            </div>
            <div className="space-y-2 text-sm">
              <div className="flex justify-between">
                <span className="text-slate-500">套餐</span>
                <span className="font-medium text-slate-800">{data.plan_product_name || "无"}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">席位</span>
                <span className="font-medium text-slate-800">{data.seat_count ?? "-"}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">到期时间</span>
                <span className="font-medium text-slate-800">{data.ends_at || "无"}</span>
              </div>
            </div>
          </div>
          <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <div className="mb-3 flex items-center gap-2">
              <CreditCard className="h-5 w-5 text-slate-400" />
              <h2 className="text-base font-semibold text-slate-700">积分余额</h2>
            </div>
            <p className="text-3xl font-bold text-blue-600">{data.credit_balance.toLocaleString()}</p>
          </div>
        </div>
      </div>

      {/* 成员列表 */}
      <div className="mt-6 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        <div className="border-b border-slate-200 px-6 py-4">
          <h2 className="flex items-center gap-2 text-base font-semibold text-slate-700">
            <Users className="h-5 w-5 text-slate-400" /> 成员列表
          </h2>
        </div>
        <table className="w-full">
          <thead>
            <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
              <th className="px-4 py-3">姓名</th>
              <th className="px-4 py-3">手机号</th>
              <th className="px-4 py-3">角色</th>
              <th className="px-4 py-3">状态</th>
              <th className="px-4 py-3">加入时间</th>
            </tr>
          </thead>
          <tbody>
            {data.members.length === 0 ? (
              <tr><td colSpan={5} className="p-8 text-center text-sm text-slate-400">暂无成员</td></tr>
            ) : (
              data.members.map((m) => (
                <tr key={m.id} className="border-b border-slate-100 hover:bg-slate-50">
                  <td className="px-4 py-3 text-sm font-medium text-slate-800">{m.display_name}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{m.phone}</td>
                  <td className="px-4 py-3">{getRoleBadge(m.role)}</td>
                  <td className="px-4 py-3"><Badge variant={m.status === "ACTIVE" ? "success" : "neutral"}>{m.status}</Badge></td>
                  <td className="px-4 py-3 text-sm text-slate-500">{m.joined_at}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Owner 转移弹窗 */}
      {showTransfer && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
            <h2 className="mb-4 text-lg font-bold text-slate-800">转移企业负责人</h2>
            <p className="mb-4 text-sm text-slate-500">
              当前负责人：<span className="font-medium text-slate-700">{owner?.display_name ?? "无"}</span>
            </p>
            <label className="mb-1 block text-sm font-medium text-slate-700">新负责人用户ID</label>
            <input value={newOwnerId} onChange={(e) => setNewOwnerId(e.target.value)}
              placeholder="请输入新负责人的用户ID" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
            <p className="mt-2 text-xs text-slate-400">转移后，新负责人将拥有企业最高权限，请谨慎操作。</p>
            <div className="mt-4 flex justify-end gap-3">
              <button onClick={() => setShowTransfer(false)}
                className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-600 hover:bg-slate-50">取消</button>
              <button onClick={() => transferMutation.mutate()} disabled={!newOwnerId || transferMutation.isPending}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50">
                {transferMutation.isPending ? "处理中…" : "确认转移"}
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
    <div>
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className="mt-1 font-medium text-slate-800">{value || "-"}</dd>
    </div>
  );
}
