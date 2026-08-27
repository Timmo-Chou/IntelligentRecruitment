"use client";

// 审核中心：Tab 切换个人认证 / 企业认证 / 成员申请
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { adminApiFetch } from "@/lib/admin-api-client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

// 个人认证审核项
type PersonalReviewItem = {
  id: string;
  userId: string;
  realNameMasked: string;
  verificationStatus: string;
  createdAt: string;
  userDisplayName: string | null;
};

// 企业认证审核项
type CompanyReviewItem = {
  id: string;
  applicantUserId: string;
  legalName: string;
  displayName: string;
  requestType: string;
  status: string;
  createdAt: string;
};

// 成员申请审核项
type MembershipReviewItem = {
  id: string;
  companyId: string;
  applicantUserId: string;
  companyName: string;
  status: string;
  createdAt: string;
};

type PageResponse<T> = {
  items: T[];
  total: number;
  page: number;
  size: number;
};

const tabs = [
  { key: "PERSONAL", label: "个人认证", endpoint: "/platform/reviews/personal" },
  { key: "COMPANY", label: "企业认证", endpoint: "/platform/reviews/company-verifications" },
  { key: "MEMBERSHIP", label: "成员申请", endpoint: "/platform/reviews/membership-applications" },
] as const;

export default function ReviewsPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [activeTab, setActiveTab] = useState<(typeof tabs)[number]["key"]>("PERSONAL");
  const [page, setPage] = useState(1);

  const currentTab = tabs.find((t) => t.key === activeTab)!;

  const { data, isLoading } = useQuery({
    queryKey: ["reviews", activeTab, page],
    queryFn: async () => {
      const params = new URLSearchParams();
      params.set("page", String(page));
      params.set("size", "20");
      const url = `${currentTab.endpoint}?${params.toString()}`;

      if (activeTab === "PERSONAL") {
        return adminApiFetch<PageResponse<PersonalReviewItem>>(url);
      } else if (activeTab === "COMPANY") {
        return adminApiFetch<PageResponse<CompanyReviewItem>>(url);
      } else {
        return adminApiFetch<PageResponse<MembershipReviewItem>>(url);
      }
    },
  });

  const items = data?.items ?? [];

  // 审核操作
  const reviewMutation = useMutation({
    mutationFn: async ({
      id,
      userId,
      action,
      reason,
    }: {
      id: string;
      userId?: string;
      action: "APPROVE" | "REJECT";
      reason?: string;
    }) => {
      let endpoint = "";
      if (activeTab === "PERSONAL") {
        // 个人认证审批用 userId
        const uid = userId || id;
        endpoint = `/platform/personal-verifications/${uid}/${action === "APPROVE" ? "approve" : "reject"}`;
      } else if (activeTab === "COMPANY") {
        endpoint = `/platform/company-verifications/${id}/${action === "APPROVE" ? "approve" : "reject"}`;
      } else {
        endpoint = `/platform/company-membership-applications/${id}/${action === "APPROVE" ? "approve" : "reject"}`;
      }
      return adminApiFetch(endpoint, {
        method: "POST",
        body: JSON.stringify(
          action === "APPROVE"
            ? { reviewer: "平台管理员" }
            : { reviewer: "平台管理员", reason: reason ?? "" },
        ),
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["reviews"] });
    },
    onError: (err: Error) => {
      alert("操作失败：" + err.message);
    },
  });

  function getStatusBadge(status: string) {
    if (status === "PENDING") return <Badge variant="warning">待审核</Badge>;
    if (status === "APPROVED" || status === "VERIFIED") return <Badge variant="success">已通过</Badge>;
    if (status === "REJECTED") return <Badge variant="danger">已拒绝</Badge>;
    return <Badge>{status}</Badge>;
  }

  // 根据不同类型获取显示信息
  function getTargetName(item: PersonalReviewItem | CompanyReviewItem | MembershipReviewItem): string {
    if (activeTab === "PERSONAL") {
      const p = item as PersonalReviewItem;
      return p.realNameMasked || p.userDisplayName || p.userId;
    }
    if (activeTab === "COMPANY") {
      const c = item as CompanyReviewItem;
      return c.legalName || c.displayName;
    }
    const m = item as MembershipReviewItem;
    return m.companyName || m.companyId;
  }

  function getSubmitterName(item: PersonalReviewItem | CompanyReviewItem | MembershipReviewItem): string {
    if (activeTab === "PERSONAL") {
      return (item as PersonalReviewItem).userDisplayName ?? (item as PersonalReviewItem).userId;
    }
    if (activeTab === "COMPANY") {
      return (item as CompanyReviewItem).applicantUserId;
    }
    return (item as MembershipReviewItem).applicantUserId;
  }

  function getItemStatus(item: PersonalReviewItem | CompanyReviewItem | MembershipReviewItem): string {
    if (activeTab === "PERSONAL") return (item as PersonalReviewItem).verificationStatus;
    if (activeTab === "COMPANY") return (item as CompanyReviewItem).status;
    return (item as MembershipReviewItem).status;
  }

  function getReviewTypePath(): string {
    if (activeTab === "PERSONAL") return "personal";
    if (activeTab === "COMPANY") return "company";
    return "membership";
  }

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-800">审核中心</h1>
        <p className="mt-1 text-sm text-slate-500">处理个人认证、企业认证和成员申请</p>
      </div>

      {/* Tab 切换 */}
      <div className="mb-4 flex border-b border-slate-200">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => { setActiveTab(tab.key); setPage(1); }}
            className={`px-5 py-3 text-sm font-semibold transition ${
              activeTab === tab.key
                ? "border-b-2 border-blue-600 text-blue-600"
                : "text-slate-500 hover:text-slate-700"
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* 审核列表 */}
      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
        {isLoading ? (
          <div className="p-8 text-center text-sm text-slate-400">加载中…</div>
        ) : items.length === 0 ? (
          <div className="p-8 text-center text-sm text-slate-400">暂无待审核项</div>
        ) : (
          <table className="w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs font-semibold uppercase text-slate-500">
                <th className="px-4 py-3">对象</th>
                <th className="px-4 py-3">提交人</th>
                <th className="px-4 py-3">状态</th>
                <th className="px-4 py-3">提交时间</th>
                <th className="px-4 py-3">操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id} className="border-b border-slate-100">
                  <td className="px-4 py-3 text-sm font-medium text-slate-800">{getTargetName(item)}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{getSubmitterName(item)}</td>
                  <td className="px-4 py-3">{getStatusBadge(getItemStatus(item))}</td>
                  <td className="px-4 py-3 text-sm text-slate-500">{item.createdAt}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <Button
                        size="sm"
                        variant="confirm"
                        onClick={() =>
                          reviewMutation.mutate({
                            id: item.id,
                            userId: (item as PersonalReviewItem).userId,
                            action: "APPROVE",
                          })
                        }
                        disabled={reviewMutation.isPending}
                      >
                        通过
                      </Button>
                      <Button
                        size="sm"
                        variant="danger"
                        onClick={() => {
                          const reason = prompt("请输入拒绝原因：");
                          if (reason) {
                            reviewMutation.mutate({
                              id: item.id,
                              userId: (item as PersonalReviewItem).userId,
                              action: "REJECT",
                              reason,
                            });
                          }
                        }}
                        disabled={reviewMutation.isPending}
                      >
                        拒绝
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => {
                          const detailId = activeTab === "PERSONAL"
                            ? (item as PersonalReviewItem).userId
                            : item.id;
                          router.push(`/reviews/${getReviewTypePath()}/${detailId}`);
                        }}
                      >
                        查看详情
                      </Button>
                    </div>
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