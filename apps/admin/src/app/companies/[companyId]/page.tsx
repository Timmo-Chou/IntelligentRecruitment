"use client";

// 企业详情页面：完整注册信息展示
import { ArrowLeft, Building2, Users, Briefcase, Wallet, ShieldCheck, Hash, User, Calendar, CreditCard } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

type CompanyDetail = {
  companyId: string;
  legalName: string;
  displayName: string;
  creditCodeMasked: string;
  verificationStatus: string;
  managementStatus: string;
  ownerUserId: string;
  ownerDisplayName: string;
  members: {
    userId: string;
    displayName: string;
    role: string;
    status: string;
  }[];
  workspaces: {
    workspaceId: string;
    workspaceName: string;
    status: string;
    memberCount: number;
  }[];
  createdAt: string;
};

export default function CompanyDetailPage() {
  const params = useParams();
  const companyId = params.companyId as string;

  const { data: company, isLoading, error } = useQuery({
    queryKey: ["company", companyId],
    queryFn: () => adminApiFetch<CompanyDetail>(`/platform/companies/${companyId}`),
    enabled: !!companyId,
  });

  function getVerificationBadge(status: string) {
    if (status === "VERIFIED") return <Badge variant="success">已认证</Badge>;
    if (status === "PENDING") return <Badge variant="warning">待审核</Badge>;
    if (status === "REJECTED") return <Badge variant="danger">已拒绝</Badge>;
    return <Badge variant="neutral">未认证</Badge>;
  }

  function getStatusBadge(status: string) {
    if (status === "ACTIVE") return <Badge variant="success">正常</Badge>;
    if (status === "DISABLED") return <Badge variant="danger">已禁用</Badge>;
    return <Badge>{status}</Badge>;
  }

  if (isLoading) {
    return <div className="p-8 text-center text-sm text-slate-400">加载中…</div>;
  }

  if (error || !company) {
    return <div className="p-8 text-center text-sm text-red-500">加载失败，请稍后重试</div>;
  }

  return (
    <div>
      <Link
        href="/companies"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
      >
        <ArrowLeft className="h-4 w-4" />
        返回企业列表
      </Link>

      {/* 企业基本信息 */}
      <section className="mb-6 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-4">
            <div className="flex h-14 w-14 items-center justify-center rounded-xl bg-green-50 text-xl font-bold text-green-600">
              <Building2 className="h-7 w-7" />
            </div>
            <div>
              <h1 className="text-xl font-bold text-slate-800">{company.displayName || company.legalName}</h1>
              <p className="mt-1 text-sm text-slate-500">ID: {company.companyId}</p>
              <div className="mt-2 flex items-center gap-2">
                {getVerificationBadge(company.verificationStatus)}
                {getStatusBadge(company.managementStatus)}
              </div>
            </div>
          </div>
        </div>

        {/* 企业注册信息 */}
        <div className="mt-6 border-t border-slate-100 pt-4">
          <h3 className="mb-3 flex items-center gap-2 text-sm font-semibold text-slate-600">
            <ShieldCheck className="h-4 w-4 text-blue-500" />
            企业注册信息
          </h3>
          <div className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-3">
            <div className="flex items-start gap-2">
              <Building2 className="mt-0.5 h-4 w-4 text-slate-400" />
              <div>
                <span className="text-slate-400">企业全称</span>
                <p className="mt-0.5 font-medium text-slate-700">{company.legalName || "---"}</p>
              </div>
            </div>
            <div className="flex items-start gap-2">
              <Building2 className="mt-0.5 h-4 w-4 text-slate-400" />
              <div>
                <span className="text-slate-400">企业简称</span>
                <p className="mt-0.5 font-medium text-slate-700">{company.displayName || "---"}</p>
              </div>
            </div>
            <div className="flex items-start gap-2">
              <CreditCard className="mt-0.5 h-4 w-4 text-slate-400" />
              <div>
                <span className="text-slate-400">统一社会信用代码</span>
                <p className="mt-0.5 font-medium text-slate-700">{company.creditCodeMasked || "---"}</p>
              </div>
            </div>
            <div className="flex items-start gap-2">
              <User className="mt-0.5 h-4 w-4 text-slate-400" />
              <div>
                <span className="text-slate-400">所有者</span>
                <p className="mt-0.5 font-medium text-slate-700">{company.ownerDisplayName || company.ownerUserId || "---"}</p>
              </div>
            </div>
            <div className="flex items-start gap-2">
              <Hash className="mt-0.5 h-4 w-4 text-slate-400" />
              <div>
                <span className="text-slate-400">所有者ID</span>
                <p className="mt-0.5 font-mono text-xs text-slate-700">{company.ownerUserId || "---"}</p>
              </div>
            </div>
            <div className="flex items-start gap-2">
              <Calendar className="mt-0.5 h-4 w-4 text-slate-400" />
              <div>
                <span className="text-slate-400">创建时间</span>
                <p className="mt-0.5 font-medium text-slate-700">{company.createdAt}</p>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 成员列表 */}
      <section className="mb-6 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="mb-4 flex items-center gap-2 text-base font-bold text-slate-700">
          <Users className="h-5 w-5 text-blue-500" />
          成员列表
        </h2>
        {company.members && company.members.length > 0 ? (
          <div className="overflow-hidden rounded-lg border border-slate-100">
            <table className="w-full">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                  <th className="px-4 py-3">显示名</th>
                  <th className="px-4 py-3">角色</th>
                  <th className="px-4 py-3">状态</th>
                </tr>
              </thead>
              <tbody>
                {company.members.map((m) => (
                  <tr key={m.userId} className="border-b border-slate-50">
                    <td className="px-4 py-3 text-sm font-medium text-slate-700">{m.displayName}</td>
                    <td className="px-4 py-3 text-sm text-slate-500">{m.role}</td>
                    <td className="px-4 py-3 text-sm text-slate-500">{m.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="text-sm text-slate-400">暂无成员</p>
        )}
      </section>

      {/* 工作空间列表 */}
      <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <h2 className="mb-4 flex items-center gap-2 text-base font-bold text-slate-700">
          <Briefcase className="h-5 w-5 text-blue-500" />
          工作空间
        </h2>
        {company.workspaces && company.workspaces.length > 0 ? (
          <div className="overflow-hidden rounded-lg border border-slate-100">
            <table className="w-full">
              <thead>
                <tr className="border-b border-slate-100 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                  <th className="px-4 py-3">空间名称</th>
                  <th className="px-4 py-3">状态</th>
                  <th className="px-4 py-3">成员数</th>
                </tr>
              </thead>
              <tbody>
                {company.workspaces.map((w) => (
                  <tr key={w.workspaceId} className="border-b border-slate-50">
                    <td className="px-4 py-3 text-sm font-medium text-slate-700">{w.workspaceName}</td>
                    <td className="px-4 py-3 text-sm text-slate-500">{w.status}</td>
                    <td className="px-4 py-3 text-sm text-slate-500">{w.memberCount}</td>
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