"use client";

// 用户详情页面：完整注册信息展示
import { ArrowLeft, ShieldCheck, Building2, Briefcase, UserCog, Hash, Phone, Calendar, Clock, AlertCircle } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

// 用户详情类型
type UserDetail = {
  userId: string;
  displayName: string;
  phone: string;
  status: string;
  verificationStatus: string;
  realNameMasked: string | null;
  identityHash: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  rejectionReason: string | null;
  companies: {
    companyId: string;
    companyName: string;
    role: string;
    status: string;
  }[];
  workspaces: {
    workspaceId: string;
    workspaceName: string;
    role: string;
    status: string;
  }[];
  createdAt: string;
};

export default function UserDetailPage() {
  const params = useParams();
  const router = useRouter();
  const queryClient = useQueryClient();
  const userId = params.userId as string;

  const [actionLoading, setActionLoading] = useState(false);

  const { data: user, isLoading, error } = useQuery({
    queryKey: ["user", userId],
    queryFn: () => adminApiFetch<UserDetail>(`/platform/users/${userId}`),
    enabled: !!userId,
  });

  // 启用/禁用切换
  const toggleStatus = useMutation({
    mutationFn: async () => {
      const newStatus = user?.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
      return adminApiFetch(`/platform/users/${userId}/status`, {
        method: "PUT",
        body: JSON.stringify({ status: newStatus }),
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["user", userId] });
    },
    onError: (err: Error) => {
      alert("操作失败：" + err.message);
    },
  });

  const getStatusBadge = (status: string) => {
    if (status === "ACTIVE") return <Badge variant="success">正常</Badge>;
    if (status === "DISABLED") return <Badge variant="danger">已禁用</Badge>;
    return <Badge>{status}</Badge>;
  };

  const getVerificationBadge = (status: string) => {
    if (status === "VERIFIED") return <Badge variant="success">已认证</Badge>;
    if (status === "PENDING") return <Badge variant="warning">待认证</Badge>;
    if (status === "REJECTED") return <Badge variant="danger">已拒绝</Badge>;
    return <Badge variant="neutral">未认证</Badge>;
  };

  if (isLoading) {
    return <div className="p-8 text-center text-sm text-slate-400">加载中…</div>;
  }

  if (error || !user) {
    return <div className="p-8 text-center text-sm text-red-500">加载失败，请稍后重试</div>;
  }

  return (
    <div>
      {/* 返回按钮 */}
      <Link
        href="/users"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
      >
        <ArrowLeft className="h-4 w-4" />
        返回用户列表
      </Link>

      {/* 用户基本信息 */}
      <section className="mb-6 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-4">
            <div className="flex h-14 w-14 items-center justify-center rounded-full bg-blue-50 text-xl font-bold text-blue-600">
              {user.displayName?.charAt(0) ?? "U"}
            </div>
            <div>
              <h1 className="text-xl font-bold text-slate-800">{user.displayName}</h1>
              <p className="mt-1 text-sm text-slate-500">ID: {user.userId}</p>
              <div className="mt-2 flex items-center gap-2">
                {getStatusBadge(user.status)}
                {getVerificationBadge(user.verificationStatus)}
              </div>
            </div>
          </div>
          <Button
            variant={user.status === "ACTIVE" ? "danger" : "confirm"}
            size="sm"
            onClick={() => toggleStatus.mutate()}
            disabled={toggleStatus.isPending}
          >
            {toggleStatus.isPending ? "处理中…" : user.status === "ACTIVE" ? "禁用账号" : "启用账号"}
          </Button>
        </div>
        <div className="mt-4 grid grid-cols-2 gap-4 text-sm sm:grid-cols-3">
          <div className="flex items-start gap-2">
            <Phone className="mt-0.5 h-4 w-4 text-slate-400" />
            <div>
              <span className="text-slate-400">手机号(后四位)</span>
              <p className="mt-0.5 font-medium text-slate-700">{user.phone || "---"}</p>
            </div>
          </div>
          <div className="flex items-start gap-2">
            <Calendar className="mt-0.5 h-4 w-4 text-slate-400" />
            <div>
              <span className="text-slate-400">注册时间</span>
              <p className="mt-0.5 font-medium text-slate-700">{user.createdAt}</p>
            </div>
          </div>
        </div>
      </section>

      {/* 实名认证信息 */}
      <section className="mb-6 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="mb-4 flex items-center gap-2 text-base font-bold text-slate-700">
          <ShieldCheck className="h-5 w-5 text-blue-500" />
          实名认证信息
        </h2>
        {user.realNameMasked || user.identityHash ? (
          <div className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-3">
            <div className="flex items-start gap-2">
              <UserCog className="mt-0.5 h-4 w-4 text-slate-400" />
              <div>
                <span className="text-slate-400">实名信息</span>
                <p className="mt-0.5 font-medium text-slate-700">{user.realNameMasked || "---"}</p>
              </div>
            </div>
            <div className="flex items-start gap-2">
              <Hash className="mt-0.5 h-4 w-4 text-slate-400" />
              <div>
                <span className="text-slate-400">身份哈希</span>
                <p className="mt-0.5 font-mono text-xs text-slate-700 break-all">{user.identityHash || "---"}</p>
              </div>
            </div>
            <div>
              <span className="text-slate-400">认证状态</span>
              <p className="mt-0.5">{getVerificationBadge(user.verificationStatus)}</p>
            </div>
            {user.reviewedBy && (
              <div className="flex items-start gap-2">
                <UserCog className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">审核人</span>
                  <p className="mt-0.5 font-medium text-slate-700">{user.reviewedBy}</p>
                </div>
              </div>
            )}
            {user.reviewedAt && (
              <div className="flex items-start gap-2">
                <Clock className="mt-0.5 h-4 w-4 text-slate-400" />
                <div>
                  <span className="text-slate-400">审核时间</span>
                  <p className="mt-0.5 font-medium text-slate-700">{user.reviewedAt}</p>
                </div>
              </div>
            )}
            {user.rejectionReason && (
              <div className="col-span-2 flex items-start gap-2">
                <AlertCircle className="mt-0.5 h-4 w-4 text-red-400" />
                <div>
                  <span className="text-slate-400">拒绝原因</span>
                  <p className="mt-0.5 font-medium text-red-600">{user.rejectionReason}</p>
                </div>
              </div>
            )}
          </div>
        ) : (
          <p className="text-sm text-slate-400">该用户尚未完成实名认证</p>
        )}
      </section>

      {/* 所属企业 */}
      <section className="mb-6 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="mb-4 flex items-center gap-2 text-base font-bold text-slate-700">
          <Building2 className="h-5 w-5 text-blue-500" />
          所属企业
        </h2>
        {user.companies && user.companies.length > 0 ? (
          <div className="overflow-hidden rounded-lg border border-slate-100">
            <table className="w-full">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                  <th className="px-4 py-3">企业名称</th>
                  <th className="px-4 py-3">角色</th>
                  <th className="px-4 py-3">状态</th>
                </tr>
              </thead>
              <tbody>
                {user.companies.map((c) => (
                  <tr key={c.companyId} className="border-b border-slate-50">
                    <td className="px-4 py-3 text-sm font-medium text-slate-700">{c.companyName}</td>
                    <td className="px-4 py-3 text-sm text-slate-500">{c.role}</td>
                    <td className="px-4 py-3 text-sm text-slate-500">{c.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="text-sm text-slate-400">该用户未加入任何企业</p>
        )}
      </section>

      {/* 工作空间 */}
      <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="mb-4 flex items-center gap-2 text-base font-bold text-slate-700">
          <Briefcase className="h-5 w-5 text-blue-500" />
          工作空间
        </h2>
        {user.workspaces && user.workspaces.length > 0 ? (
          <div className="overflow-hidden rounded-lg border border-slate-100">
            <table className="w-full">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                  <th className="px-4 py-3">空间名称</th>
                  <th className="px-4 py-3">角色</th>
                  <th className="px-4 py-3">状态</th>
                </tr>
              </thead>
              <tbody>
                {user.workspaces.map((w) => (
                  <tr key={w.workspaceId} className="border-b border-slate-50">
                    <td className="px-4 py-3 text-sm font-medium text-slate-700">{w.workspaceName}</td>
                    <td className="px-4 py-3 text-sm text-slate-500">{w.role}</td>
                    <td className="px-4 py-3 text-sm text-slate-500">{w.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="text-sm text-slate-400">暂无工作空间</p>
        )}
      </section>
    </div>
  );
}